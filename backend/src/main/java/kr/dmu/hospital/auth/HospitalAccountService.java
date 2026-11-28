package kr.dmu.hospital.auth;

import com.mongodb.MongoException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class HospitalAccountService {
    public static final String REGISTRATIONS = "hospitalRegistrations";
    public static final String VERIFICATIONS = "hospitalSignupVerifications";
    private final MongoTemplate mongo;
    private final PasswordEncoder passwords;
    private final AuthService auth;
    private final ComwelClient comwel;
    private final TransactionTemplate transaction;
    private final SecureRandom random = new SecureRandom();

    public HospitalAccountService(MongoTemplate mongo, PasswordEncoder passwords, AuthService auth,
                                  ComwelClient comwel, MongoTransactionManager manager) {
        this.mongo = mongo; this.passwords = passwords; this.auth = auth; this.comwel = comwel;
        transaction = new TransactionTemplate(manager); transaction.setTimeout(20);
    }

    public Map<String, Object> lookup(Map<String, Object> body) {
        String number = businessNumber(body);
        requireUnregistered(number, null);
        List<Map<String, String>> businesses = comwel.lookup(number);
        Map<String, List<Map<String, String>>> businessesByPostcode = new LinkedHashMap<>();
        for (Map<String, String> business : businesses) {
            if (!number.equals(business.get("saeopjaDrno"))) continue;
            String postcode = business.getOrDefault("post", "").trim();
            if (postcode.isEmpty() || business.getOrDefault("saeopjangNm", "").isBlank()) continue;
            businessesByPostcode.computeIfAbsent(postcode, key -> new ArrayList<>()).add(business);
        }
        List<Document> direct = new ArrayList<>();
        List<Document> affiliated = new ArrayList<>();
        for (var group : businessesByPostcode.entrySet()) {
            for (Document candidate : activeHospitalsAtPostcode(group.getKey())) {
                if (HospitalBusinessMatch.matches(group.getValue(), candidate, number)) direct.add(candidate);
                else if (HospitalBusinessMatch.isChoiceCandidate(group.getValue(), candidate, number)) affiliated.add(candidate);
            }
        }
        Date expiresAt = Date.from(Instant.now().plusSeconds(900));
        if (direct.size() == 1) {
            Document hospital = direct.get(0);
            requireUnregistered(number, hospital.getString("_id"));
            return verifiedHospital(number, hospital, expiresAt);
        }
        List<Document> candidates = direct.isEmpty() ? affiliated : direct;
        if (candidates.isEmpty()) throw new ApiException(404, "사업자 정보와 일치하는 병원을 찾지 못했어요. 사업자번호와 병원 등록 정보를 확인해 주세요.");
        List<String> ids = candidates.stream().map(h -> h.getString("_id")).toList();
        var registeredIds = new HashSet<>(mongo.findDistinct(Query.query(Criteria.where("_id").in(ids)), "_id", REGISTRATIONS, String.class));
        candidates.removeIf(h -> registeredIds.contains(h.getString("_id")));
        if (candidates.isEmpty()) throw alreadyRegistered();
        candidates.sort(Comparator.comparing((Document h) -> h.getString("name"))
                .thenComparing(h -> h.getString("address")).thenComparing(h -> h.getString("_id")));
        List<Document> snapshots = candidates.stream().map(HospitalAccountService::catalogInformation).toList();
        String token = storeProof(number, new Document("candidates", snapshots), expiresAt);
        return Map.of("businessNumber", number, "candidates", candidates.stream().map(HospitalAccountService::basicInformation).toList(),
                "selectionToken", token);
    }

    public Map<String, Object> select(Map<String, Object> body) {
        String number = businessNumber(body);
        String token = text(body, "selectionToken");
        String hospitalId = text(body, "hospitalId");
        if (!token.matches("[A-Za-z0-9_-]{43}") || hospitalId.isEmpty()) throw invalidSelection();
        return inTransaction(() -> {
            Query offered = Query.query(Criteria.where("_id").is(digest(token)).and("businessNumber").is(number)
                    .and("expiresAt").gt(new Date()).and("candidates.id").is(hospitalId));
            Document selection = mongo.findAndRemove(offered, Document.class, VERIFICATIONS);
            if (selection == null) throw invalidSelection();
            requireUnregistered(number, hospitalId);
            Document hospital = mongo.findOne(Query.query(Criteria.where("_id").is(hospitalId).and("active").is(true)), Document.class, "hospitals");
            if (hospital == null || !selection.getList("candidates", Document.class).contains(catalogInformation(hospital))) {
                throw hospitalChanged();
            }
            return verifiedHospital(number, hospital, selection.getDate("expiresAt"));
        });
    }

    private Map<String, Object> verifiedHospital(String number, Document hospital, Date expiresAt) {
        String token = storeProof(number, new Document("hospitalId", hospital.getString("_id"))
                .append("hospital", catalogInformation(hospital)), expiresAt);
        return Map.of("businessNumber", number, "hospital", basicInformation(hospital), "verificationToken", token);
    }

    private String storeProof(String number, Document details, Date expiresAt) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        mongo.insert(details.append("_id", digest(token)).append("businessNumber", number)
                .append("createdAt", new Date()).append("expiresAt", expiresAt), VERIFICATIONS);
        return token;
    }

    private List<Document> activeHospitalsAtPostcode(String postcode) {
        Query query = Query.query(Criteria.where("apiFields.postNo").is(postcode).and("active").is(true));
        query.fields().include("name", "address", "tel", "active", "apiFields.postNo");
        return mongo.find(query, Document.class, "hospitals");
    }

    public Map<String, Object> register(Map<String, Object> body) {
        String number = businessNumber(body);
        String password = rawPassword(body);
        AuthService.requireValidPassword(password);
        String token = text(body, "verificationToken");
        if (!token.matches("[A-Za-z0-9_-]{43}")) throw verificationExpired();
        String hash = passwords.encode(password);
        try {
            Document user = inTransaction(() -> {
                Document proof = mongo.findOne(Query.query(Criteria.where("_id").is(digest(token)).and("businessNumber").is(number)
                        .and("hospitalId").exists(true).and("expiresAt").gt(new Date())), Document.class, VERIFICATIONS);
                if (proof == null) throw verificationExpired();
                String hospitalId = proof.getString("hospitalId");
                requireUnregistered(number, hospitalId);
                Document hospital = mongo.findOne(Query.query(Criteria.where("_id").is(hospitalId).and("active").is(true)), Document.class, "hospitals");
                if (hospital == null || !catalogInformation(hospital).equals(proof.get("hospital", Document.class))) {
                    throw hospitalChanged();
                }
                Document basic = basicInformation(hospital);
                String userId = UUID.randomUUID().toString();
                Date now = new Date();
                Document member = new Document("_id", userId).append("loginId", "hospital:" + number)
                        .append("businessNumber", number).append("hospitalId", hospitalId).append("name", basic.getString("name"))
                        .append("phone", basic.getString("tel")).append("passwordHash", hash).append("role", "HOSPITAL")
                        .append("authProvider", "LOCAL").append("profileComplete", true).append("tokenVersion", 0).append("createdAt", now);
                mongo.insert(new Document("_id", hospitalId).append("businessNumber", number).append("userId", userId)
                        .append("status", "ACTIVE").append("basicInformation", basic).append("createdAt", now), REGISTRATIONS);
                mongo.insert(member, "users");
                if (mongo.remove(Query.query(Criteria.where("_id").is(proof.getString("_id"))), VERIFICATIONS).getDeletedCount() != 1) throw verificationExpired();
                return member;
            });
            return auth.issueHospitalSession(user);
        } catch (DuplicateKeyException error) {
            throw alreadyRegistered();
        }
    }

    private <T> T inTransaction(Supplier<T> work) {
        for (int attempt = 0; ; attempt++) {
            try {
                return transaction.execute(status -> work.get());
            } catch (RuntimeException error) {
                if (error instanceof ApiException || !retryable(error) || attempt == 3) throw error;
            }
        }
    }

    public Map<String, Object> login(Map<String, Object> body) {
        String number = businessNumber(body);
        String password = rawPassword(body);
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw invalidLogin();
        Document user = mongo.findOne(Query.query(Criteria.where("role").is("HOSPITAL").and("businessNumber").is(number)), Document.class, "users");
        if (user == null || user.getString("passwordHash") == null || !passwords.matches(password, user.getString("passwordHash"))) throw invalidLogin();
        if (!AuthService.hasHospitalRegistration(mongo, user)) throw invalidLogin();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(user.getString("_id"))), new Update().set("lastLoginAt", new Date()), "users");
        return auth.issueHospitalSession(user);
    }

    private void requireUnregistered(String number, String hospitalId) {
        Criteria selection = hospitalId == null ? Criteria.where("businessNumber").is(number)
                : new Criteria().orOperator(Criteria.where("businessNumber").is(number), Criteria.where("_id").is(hospitalId));
        if (mongo.exists(Query.query(selection), REGISTRATIONS)
                || mongo.exists(Query.query(Criteria.where("loginId").is("hospital:" + number)), "users")) {
            throw alreadyRegistered();
        }
    }

    public static Document basicInformation(Document hospital) {
        return catalogInformation(hospital).append("name", HospitalName.parse(hospital.getString("name")).facility());
    }

    // The verification snapshot includes the original catalog name so a change of operator still invalidates it.
    private static Document catalogInformation(Document hospital) {
        Document fields = hospital.get("apiFields", Document.class);
        return new Document("id", hospital.getString("_id")).append("name", hospital.getString("name"))
                .append("address", hospital.getString("address")).append("postalCode", fields == null || fields.getString("postNo") == null ? "" : fields.getString("postNo"))
                .append("tel", hospital.getString("tel") == null ? "" : hospital.getString("tel"));
    }

    static String businessNumber(Map<String, Object> body) {
        String raw = text(body, "businessNumber");
        if (!raw.matches("[0-9 -]{10,16}")) throw new ApiException(400, "사업자등록번호 10자리를 입력해 주세요.");
        String number = raw.replace("-", "").replace(" ", "");
        if (!number.matches("[0-9]{10}")) throw new ApiException(400, "사업자등록번호 10자리를 입력해 주세요.");
        return number;
    }
    private static String text(Map<String, Object> body, String key) { return body != null && body.get(key) instanceof String value ? value.trim() : ""; }
    private static String rawPassword(Map<String, Object> body) { return body != null && body.get("password") instanceof String value ? value : ""; }
    private static ApiException invalidLogin() { return new ApiException(401, "사업자등록번호 또는 비밀번호가 맞지 않습니다."); }
    private static ApiException alreadyRegistered() { return new ApiException(409, "이미 가입된 병원이에요. 병원 로그인으로 진행해 주세요."); }
    private static ApiException hospitalChanged() { return new ApiException(409, "병원 기본정보가 변경됐어요. 사업자번호를 다시 조회해 주세요."); }
    private static ApiException invalidSelection() { return new ApiException(400, "조회한 병원 후보를 확인할 수 없어요. 사업자번호를 다시 조회해 주세요."); }
    private static ApiException verificationExpired() { return new ApiException(400, "사업자번호 조회가 만료됐어요. 다시 조회한 뒤 가입해 주세요."); }
    private static String digest(String token) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable"); }
    }
    private static boolean retryable(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoException mongoError && (mongoError.hasErrorLabel("TransientTransactionError") || mongoError.getCode() == 112 || mongoError.getCode() == 251)) return true;
        }
        return false;
    }
}
