package kr.dmu.hospital.auth;

import com.mongodb.MongoException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ContactVerificationService {
    public static final String CHALLENGES = "contactVerificationChallenges";
    public static final String PROOFS = "contactVerificationProofs";
    public static final String LIMITS = "contactVerificationLimits";
    public static final String PHONE_RECEIPTS = "phoneVerificationReceipts";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final MongoTemplate mongo;
    private final VerificationMailSender mail;
    private final FirebasePhoneVerifier phones;
    private final String apiKey;
    private final String authDomain;
    private final String project;
    private final Clock clock;
    private final TransactionTemplate transaction;

    @Autowired public ContactVerificationService(MongoTemplate mongo, VerificationMailSender mail, FirebasePhoneVerifier phones,
            @Value("${app.verification.firebase-api-key:}") String apiKey,
            @Value("${app.verification.firebase-auth-domain:}") String authDomain,
            @Value("${app.verification.firebase-project-id:}") String project) {
        this(mongo, mail, phones, apiKey, authDomain, project, Clock.systemUTC());
    }

    /** Existing service callers still enforce stored proofs when providers are not configured. */
    public ContactVerificationService(MongoTemplate mongo) {
        this(mongo, new VerificationMailSender("", ""), new FirebasePhoneVerifier(""), "", "", "", Clock.systemUTC());
    }

    ContactVerificationService(MongoTemplate mongo, VerificationMailSender mail, FirebasePhoneVerifier phones,
            String apiKey, String authDomain, String project, Clock clock) {
        this.mongo = mongo; this.mail = mail; this.phones = phones;
        this.apiKey = apiKey.trim(); this.authDomain = authDomain.trim(); this.project = project.trim(); this.clock = clock;
        transaction = new TransactionTemplate(new MongoTransactionManager(mongo.getMongoDatabaseFactory()));
        transaction.setTimeout(20);
    }

    public Map<String, Object> configuration() {
        boolean phoneEnabled = phones.enabled() && !apiKey.isBlank() && !authDomain.isBlank();
        return Map.of("emailEnabled", mail.enabled(), "phoneEnabled", phoneEnabled,
                "firebase", Map.of("apiKey", apiKey, "authDomain", authDomain, "projectId", project));
    }

    public Map<String, Object> sendEmail(Map<String, Object> body, String remoteAddress) {
        String purpose = text(body, "purpose");
        if (!List.of("SIGNUP", "EMAIL_CHANGE").contains(purpose)) throw invalidPurpose();
        String email = MemberIdentity.normalizedEmail(text(body, "email"));
        if (!MemberIdentity.validEmail(email)) throw new ApiException(400, "올바른 이메일 주소를 입력해 주세요.");
        Actor actor = actor(purpose, email, "", "EMAIL");
        if (!mail.enabled()) throw new ApiException(503, "이메일 인증 설정을 확인해 주세요.");
        Instant now = clock.instant();
        reserveWindow("email:" + email, 5, now);
        reserveWindow("address:" + remoteAddress, 20, now);
        String cooldown = "cooldown:" + SocialLoginTickets.hash(email);
        try {
            mongo.findAndModify(Query.query(Criteria.where("_id").is(cooldown).and("availableAt").lte(Date.from(now))),
                    new Update().set("availableAt", Date.from(now.plusSeconds(60))).set("expiresAt", Date.from(now.plusSeconds(900))),
                    FindAndModifyOptions.options().upsert(true).returnNew(true), Document.class, LIMITS);
        } catch (DuplicateKeyException error) { throw rateLimited(); }
        String challengeId = SocialLoginTickets.randomValue();
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        Query oldChallenges = Query.query(Criteria.where("purpose").is(purpose).and("contact").is(email).and("userId").is(actor.userId()));
        mongo.remove(oldChallenges, CHALLENGES);
        Document challenge = bound(challengeId, purpose, "EMAIL", email, actor, now.plusSeconds(300))
                .append("codeHash", SocialLoginTickets.hash(challengeId + ":" + code)).append("attempts", 0);
        mongo.insert(challenge, CHALLENGES);
        try { mail.sendCode(email, code); }
        catch (RuntimeException error) {
            mongo.remove(Query.query(Criteria.where("_id").is(challenge.getString("_id"))), CHALLENGES);
            throw new ApiException(503, "인증 메일을 보내지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
        return Map.of("challengeId", challengeId, "expiresIn", 300, "retryAfter", 60);
    }

    public Map<String, Object> confirmEmail(Map<String, Object> body) {
        String id = text(body, "challengeId");
        String code = text(body, "code");
        if (!validToken(id) || !code.matches("[0-9]{6}")) throw invalidEmail();
        Document challenge = mongo.findById(SocialLoginTickets.hash(id), Document.class, CHALLENGES);
        if (challenge == null) throw invalidEmail();
        Actor current = actor(challenge.getString("purpose"), challenge.getString("contact"), "", "EMAIL");
        Query live = Query.query(Criteria.where("_id").is(challenge.getString("_id"))
                .and("userId").is(current.userId()).and("tokenVersion").is(current.version())
                .and("expiresAt").gt(Date.from(clock.instant())).and("attempts").lt(5));
        Document attempted = mongo.findAndModify(live, new Update().inc("attempts", 1),
                FindAndModifyOptions.options().returnNew(true), Document.class, CHALLENGES);
        if (attempted == null || !MessageDigest.isEqual(attempted.getString("codeHash").getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                SocialLoginTickets.hash(id + ":" + code).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) throw invalidEmail();
        return inTransaction(() -> {
            Document verified = mongo.findAndRemove(Query.query(Criteria.where("_id").is(challenge.getString("_id"))
                    .and("userId").is(current.userId()).and("tokenVersion").is(current.version())
                    .and("expiresAt").gt(Date.from(clock.instant()))), Document.class, CHALLENGES);
            if (verified == null) throw invalidEmail();
            return proof(verified.getString("purpose"), "EMAIL", verified.getString("contact"), current);
        });
    }

    public Map<String, Object> confirmPhone(Map<String, Object> body) {
        String purpose = text(body, "purpose");
        if (!List.of("SIGNUP", "PASSWORD_CHANGE", "WITHDRAWAL", "PASSWORD_RESET").contains(purpose)) throw invalidPurpose();
        FirebasePhoneVerifier.PhoneIdentity identity = phones.verify(text(body, "idToken"));
        Actor actor = actor(purpose, identity.phone(), text(body, "loginId"), "PHONE");
        try {
            return inTransaction(() -> {
                String receipt = SocialLoginTickets.hash(identity.subject() + ":" + identity.authenticatedAt().getEpochSecond());
                mongo.insert(new Document("_id", receipt).append("expiresAt", Date.from(clock.instant().plusSeconds(900))), PHONE_RECEIPTS);
                Map<String, Object> result = new LinkedHashMap<>(proof(purpose, "PHONE", identity.phone(), actor));
                if ("SIGNUP".equals(purpose)) {
                    Criteria completed = new Criteria().orOperator(Criteria.where("authProvider").is(null),
                            Criteria.where("authProvider").is("LOCAL"),
                            Criteria.where("authProvider").in("GOOGLE", "KAKAO").and("profileComplete").is(true));
                    Query account = Query.query(new Criteria().andOperator(Criteria.where("role").is("USER"),
                            Criteria.where("phone").is(identity.phone()), completed));
                    account.fields().include("authProvider");
                    Document existing = mongo.findOne(account, Document.class, "users");
                    if (existing != null) {
                        String provider = existing.getString("authProvider");
                        result.put("existingAuthProvider", provider == null ? "LOCAL" : provider);
                    }
                }
                return result;
            });
        } catch (DuplicateKeyException error) {
            throw new ApiException(400, "이미 사용한 문자 인증이에요. 인증번호를 다시 받아 인증해 주세요.");
        }
    }

    /** Call inside the transaction that changes the member so failed changes retain their proofs. */
    public void consume(String token, String purpose, String channel, String contact, String userId, int tokenVersion) {
        if (!validToken(token)) throw verificationRequired();
        Query query = Query.query(Criteria.where("_id").is(SocialLoginTickets.hash(token)).and("purpose").is(purpose)
                .and("channel").is(channel).and("contact").is(contact).and("userId").is(userId)
                .and("tokenVersion").is(tokenVersion).and("expiresAt").gt(Date.from(clock.instant())));
        if (mongo.findAndRemove(query, Document.class, PROOFS) == null) throw verificationRequired();
    }

    private Actor actor(String purpose, String contact, String loginId, String channel) {
        UserPrincipal current = CurrentUser.optional();
        if (purpose.equals("SIGNUP")) {
            if (current == null) return new Actor("", 0);
            if (current.isSignupPending() && "USER".equals(current.getRole())) return new Actor(current.getId(), current.getTokenVersion());
            throw new ApiException(403, "로그아웃 후 회원가입을 진행해 주세요.");
        }
        if (purpose.equals("PASSWORD_RESET")) {
            Document user = mongo.findOne(Query.query(Criteria.where("loginId").is(loginId.toLowerCase(Locale.ROOT))
                    .and("role").is("USER").and("phone").is(contact)), Document.class, "users");
            if (user == null || (user.getString("authProvider") != null && !"LOCAL".equals(user.getString("authProvider")))) {
                throw new ApiException(400, "아이디와 인증한 휴대전화 정보를 확인해 주세요.");
            }
            return new Actor(user.getString("_id"), user.getInteger("tokenVersion", 0));
        }
        if (current == null) throw new ApiException(401, "로그인이 필요합니다.");
        if (!"USER".equals(current.getRole()) || !current.isProfileComplete() || current.isSignupPending()) throw invalidPurpose();
        Document user = mongo.findById(current.getId(), Document.class, "users");
        if (user == null || user.getInteger("tokenVersion", 0) != current.getTokenVersion()) {
            throw new ApiException(401, "로그인 정보가 변경됐어요. 다시 로그인해 주세요.");
        }
        if ("PHONE".equals(channel) && !contact.equals(user.getString("phone"))) {
            throw new ApiException(400, "회원정보에 등록된 휴대전화로 인증해 주세요.");
        }
        if (purpose.equals("PASSWORD_CHANGE") && !"LOCAL".equals(current.getAuthProvider())) throw invalidPurpose();
        return new Actor(current.getId(), current.getTokenVersion());
    }

    private Map<String, Object> proof(String purpose, String channel, String contact, Actor actor) {
        String token = SocialLoginTickets.randomValue();
        mongo.insert(bound(token, purpose, channel, contact, actor, clock.instant().plusSeconds(600)), PROOFS);
        return Map.of("verificationToken", token, "expiresIn", 600, "contact", contact);
    }

    private Document bound(String token, String purpose, String channel, String contact, Actor actor, Instant expiresAt) {
        return new Document("_id", SocialLoginTickets.hash(token)).append("purpose", purpose).append("channel", channel)
                .append("contact", contact).append("userId", actor.userId()).append("tokenVersion", actor.version())
                .append("createdAt", Date.from(clock.instant())).append("expiresAt", Date.from(expiresAt));
    }

    private void reserveWindow(String scope, int limit, Instant now) {
        long window = now.getEpochSecond() / 900;
        String id = "window:" + SocialLoginTickets.hash(scope + ":" + window);
        try {
            mongo.findAndModify(Query.query(Criteria.where("_id").is(id).and("count").lt(limit)),
                    new Update().inc("count", 1).setOnInsert("expiresAt", Date.from(Instant.ofEpochSecond((window + 1) * 900))),
                    FindAndModifyOptions.options().upsert(true).returnNew(true), Document.class, LIMITS);
        } catch (DuplicateKeyException error) { throw rateLimited(); }
    }

    private <T> T inTransaction(Supplier<T> operation) {
        for (int attempt = 0; ; attempt++) {
            try { return transaction.execute(status -> operation.get()); }
            catch (RuntimeException error) {
                if (attempt == 3 || error instanceof ApiException || !retryable(error)) throw error;
            }
        }
    }

    private static boolean retryable(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoException mongo && (mongo.hasErrorLabel("TransientTransactionError") || mongo.getCode() == 112 || mongo.getCode() == 251)) return true;
        }
        return false;
    }
    private static String text(Map<String, Object> body, String key) { return body != null && body.get(key) instanceof String value ? value.trim() : ""; }
    private static boolean validToken(String token) { return token != null && token.matches("[A-Za-z0-9_-]{43}"); }
    private static ApiException invalidPurpose() { return new ApiException(400, "인증 요청을 확인해 주세요."); }
    private static ApiException rateLimited() { return new ApiException(429, "인증 요청이 많아요. 잠시 후 다시 시도해 주세요."); }
    private static ApiException invalidEmail() { return new ApiException(400, "인증번호가 맞지 않거나 만료됐어요. 다시 확인해 주세요."); }
    private static ApiException verificationRequired() { return new ApiException(400, "이메일 또는 문자 인증이 필요해요. 다시 인증해 주세요."); }
    private record Actor(String userId, int version) { }
}
