package kr.dmu.hospital.member;

import com.mongodb.MongoException;
import kr.dmu.hospital.booking.BookingService;
import kr.dmu.hospital.auth.ContactVerificationService;
import kr.dmu.hospital.auth.HospitalAccountService;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class MemberService {
    private static final int RETRIES = 8;
    private final MongoTemplate mongo;
    private final BookingService booking;
    private final TransactionTemplate transaction;
    private final PasswordEncoder passwords;
    private final ContactVerificationService verification;

    public MemberService(MongoTemplate mongo, BookingService booking, MongoTransactionManager transactionManager, PasswordEncoder passwords) {
        this(mongo, booking, transactionManager, passwords, new ContactVerificationService(mongo));
    }
    @Autowired
    public MemberService(MongoTemplate mongo, BookingService booking, MongoTransactionManager transactionManager,
            PasswordEncoder passwords, ContactVerificationService verification) {
        this.mongo = mongo;
        this.booking = booking;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setTimeout(20);
        this.passwords = passwords;
        this.verification = verification;
    }

    public List<Document> list(String adminId) {
        requireAdmin(adminId);
        return memberDocuments(Query.query(memberSelection("all", ""))
                .with(Sort.by(Sort.Direction.DESC, "createdAt", "_id")));
    }

    public Document search(String adminId, String type, String search, int page, int pageSize) {
        requireAdmin(adminId);
        if (type == null || !List.of("personal", "hospital", "all").contains(type) || search == null || search.length() > 100
                || page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ApiException(400, "회원 유형, 검색어와 페이지 정보를 확인해 주세요.");
        }
        String text = Normalizer.normalize(search, Normalizer.Form.NFC).strip();
        Criteria selection = memberSelection(type, text);
        long total = mongo.count(Query.query(selection), "users");
        int actualPage = (int) Math.min(page, Math.max(1L, (total + pageSize - 1) / pageSize));
        Query query = Query.query(selection).with(Sort.by(Sort.Direction.DESC, "createdAt", "_id"))
                .skip((long) (actualPage - 1) * pageSize).limit(pageSize);
        long personalCount = mongo.count(Query.query(memberSelection("personal", "")), "users");
        long hospitalCount = mongo.count(Query.query(memberSelection("hospital", "")), "users");
        return new Document("items", memberDocuments(query)).append("total", total)
                .append("page", actualPage).append("pageSize", pageSize)
                .append("personalCount", personalCount).append("hospitalCount", hospitalCount);
    }

    private Criteria memberSelection(String type, String text) {
        Criteria completed = new Criteria().orOperator(
                Criteria.where("authProvider").is(null),
                Criteria.where("authProvider").is("LOCAL"),
                Criteria.where("authProvider").in("GOOGLE", "KAKAO").and("profileComplete").is(true));
        Criteria personal = Criteria.where("role").ne("HOSPITAL");
        Criteria hospital = Criteria.where("role").is("HOSPITAL");
        if (!text.isEmpty()) {
            Criteria name = Criteria.where("name").regex(Pattern.compile(Pattern.quote(text), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            Pattern number = contactNumberPattern(text);
            Criteria personalMatch = number == null ? name : new Criteria().orOperator(name, Criteria.where("phone").regex(number));
            Criteria hospitalMatch = number == null ? Criteria.where("_id").exists(false) : Criteria.where("businessNumber").regex(number);
            personal = new Criteria().andOperator(personal, personalMatch);
            hospital = new Criteria().andOperator(hospital, hospitalMatch);
        }
        if ("personal".equals(type)) return new Criteria().andOperator(completed, personal);
        if ("hospital".equals(type)) return new Criteria().andOperator(completed, hospital);
        if (text.isEmpty()) return completed;
        return new Criteria().andOperator(completed, new Criteria().orOperator(personal, hospital));
    }

    private Pattern contactNumberPattern(String text) {
        if (!text.matches("[0-9\\s()\\-]+")) return null;
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;
        // Normalized fields and older formatted contact fields match the same search.
        return Pattern.compile(String.join("[\\s()\\-]*", digits.split("")));
    }

    private List<Document> memberDocuments(Query query) {
        query.fields().include("name").include("loginId").include("role").include("createdAt")
                .include("authProvider").include("email").include("phone").include("profileComplete")
                .include("hospitalId").include("businessNumber").include("testUser");
        List<Document> users = mongo.find(query, Document.class, "users");
        List<Document> result = new ArrayList<>();
        for (Document user : users) {
            Date createdAt = user.getDate("createdAt");
            String provider = user.getString("authProvider");
            if (!"GOOGLE".equals(provider) && !"KAKAO".equals(provider)) provider = "LOCAL";
            boolean complete = "LOCAL".equals(provider) || Boolean.TRUE.equals(user.getBoolean("profileComplete"));
            String name = complete ? user.getString("name") : "";
            Document member = new Document("id", user.getString("_id")).append("name", name == null ? "" : name)
                    .append("loginId", user.getString("loginId")).append("role", user.getString("role"))
                    .append("authProvider", provider).append("profileComplete", complete)
                    .append("createdAt", createdAt == null ? null : createdAt.toInstant().toString());
            String email = user.getString("email");
            if (complete && email != null && !email.isBlank()) member.append("email", email);
            String phone = user.getString("phone");
            if (complete && phone != null && !phone.isBlank()) member.append("phone", phone);
            if ("HOSPITAL".equals(user.getString("role"))) {
                member.append("hospitalId", user.getString("hospitalId")).append("businessNumber", user.getString("businessNumber"));
            }
            if (Boolean.TRUE.equals(user.getBoolean("testUser"))) member.append("testUser", true);
            result.add(member);
        }
        return result;
    }

    public Document delete(String adminId, String userId) {
        return deleteMember(adminId, userId, false, null, null, null, null);
    }

    public Document withdraw(String userId, String password) {
        return withdrawVerified(userId, password, null);
    }

    public Document withdrawVerified(String userId, String password, String phoneVerificationToken) {
        return deleteMember(userId, userId, true, password, null, null, phoneVerificationToken);
    }

    /** Called only after a fresh OAuth callback has verified this exact provider identity. */
    public Document withdrawSocial(String userId, String expectedIdentity, int expectedTokenVersion) {
        return withdrawSocial(userId, expectedIdentity, expectedTokenVersion, null);
    }
    public Document withdrawSocial(String userId, String expectedIdentity, int expectedTokenVersion, String phoneVerificationToken) {
        if (expectedIdentity == null || expectedIdentity.isBlank()) {
            throw new ApiException(403, "가입한 소셜 계정으로 다시 인증해 주세요.");
        }
        return deleteMember(userId, userId, true, null, expectedIdentity, expectedTokenVersion, phoneVerificationToken);
    }

    private Document deleteMember(String actorId, String userId, boolean withdrawal, String password, String socialIdentity,
            Integer expectedTokenVersion, String phoneVerificationToken) {
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            try {
                return transaction.execute(status -> {
                    if (!withdrawal) {
                        requireAdmin(actorId);
                        if (actorId.equals(userId)) {
                            throw new ApiException(403, "현재 로그인한 관리자 계정은 삭제할 수 없습니다.");
                        }
                    }
                    Document user = mongo.findById(userId, Document.class, "users");
                    if (user == null) {
                        if (withdrawal) throw new ApiException(401, "로그인이 필요합니다.");
                        throw new ApiException(404, "회원을 찾을 수 없습니다.");
                    }
                    boolean hospital = "HOSPITAL".equals(user.getString("role"));
                    if (!"USER".equals(user.getString("role")) && !hospital) {
                        throw new ApiException(403, withdrawal ? "관리자 계정은 탈퇴할 수 없습니다." : "관리자 계정은 삭제할 수 없습니다.");
                    }
                    if (withdrawal) {
                        if (hospital) {
                            if (socialIdentity != null || !"LOCAL".equals(user.getString("authProvider"))) {
                                throw new ApiException(403, "병원 계정의 현재 비밀번호로 다시 확인해 주세요.");
                            }
                            verifyPassword(user, password);
                        } else {
                            if (socialIdentity != null) verifySocialIdentity(user, socialIdentity, expectedTokenVersion);
                            else if (user.getString("authProvider") == null || "LOCAL".equals(user.getString("authProvider"))) verifyPassword(user, password);
                            else if (!Boolean.TRUE.equals(user.getBoolean("profileComplete"))) {
                                throw new ApiException(403, "가입을 완료한 회원만 탈퇴할 수 있어요.");
                            }
                            verification.consume(phoneVerificationToken, "WITHDRAWAL", "PHONE",
                                    user.getString("phone"), userId, user.getInteger("tokenVersion", 0));
                        }
                    }
                    // Booking mutations also write this user document, so an in-flight
                    // reservation must finish before deletion or retry after it and fail.
                    long deleted = mongo.remove(Query.query(Criteria.where("_id").is(userId)
                            .and("role").is(user.getString("role"))), "users").getDeletedCount();
                    if (deleted != 1) {
                        throw new ApiException(409, "회원 정보가 변경되었습니다. 다시 확인해 주세요.");
                    }
                    int canceled = hospital ? deleteHospitalRegistration(user, actorId) : booking.cancelMemberReservations(userId, actorId);
                    mongo.remove(Query.query(Criteria.where("userId").is(userId)), "requests");
                    mongo.remove(Query.query(Criteria.where("userId").is(userId)), "social_login_tickets");
                    mongo.remove(Query.query(Criteria.where("userId").is(userId)), "social_withdrawal_proofs");
                    mongo.remove(Query.query(Criteria.where("userId").is(userId)), "contactVerificationProofs");
                    return new Document("message", withdrawal ? "회원 탈퇴가 완료되었습니다." : hospital ? "병원 회원을 삭제했습니다." : "회원을 삭제했습니다.").append("deletedUserId", userId)
                            .append("canceledReservations", canceled);
                });
            } catch (RuntimeException error) {
                if (error instanceof ApiException || !retryable(error)
                        || TransactionSynchronizationManager.isActualTransactionActive()) {
                    throw error;
                }
                if (attempt == RETRIES - 1) {
                    throw new ApiException(409, "예약 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.");
                }
                pause(attempt);
            }
        }
        throw new ApiException(409, "회원 정보를 확인한 뒤 다시 시도해 주세요.");
    }

    private int deleteHospitalRegistration(Document user, String adminId) {
        String hospitalId = user.getString("hospitalId");
        String businessNumber = user.getString("businessNumber");
        if (hospitalId == null || businessNumber == null
                || mongo.remove(Query.query(Criteria.where("_id").is(hospitalId).and("userId").is(user.getString("_id"))
                        .and("businessNumber").is(businessNumber)), HospitalAccountService.REGISTRATIONS).getDeletedCount() != 1) {
            throw new ApiException(409, "병원 가입 정보를 확인하지 못해 삭제를 취소했습니다. 다시 확인해 주세요.");
        }
        int canceled = booking.closeHospitalBookings(hospitalId, adminId);
        mongo.remove(Query.query(new Criteria().orOperator(Criteria.where("businessNumber").is(businessNumber),
                Criteria.where("hospitalId").is(hospitalId))), HospitalAccountService.VERIFICATIONS);
        return canceled;
    }

    private void verifyPassword(Document user, String password) {
        String provider = user.getString("authProvider");
        if (provider != null && !"LOCAL".equals(provider)) {
            throw new ApiException(403, "가입한 소셜 계정으로 다시 인증한 뒤 탈퇴해 주세요.");
        }
        if (password == null || password.isEmpty()) {
            throw new ApiException(400, "현재 비밀번호를 입력해 주세요.");
        }
        String hash = user.getString("passwordHash");
        if (password.getBytes(StandardCharsets.UTF_8).length > 72 || hash == null || !passwords.matches(password, hash)) {
            throw new ApiException(403, "현재 비밀번호가 맞지 않습니다.");
        }
    }

    private void verifySocialIdentity(Document user, String expectedIdentity, Integer expectedTokenVersion) {
        String provider = user.getString("authProvider");
        if (!("GOOGLE".equals(provider) || "KAKAO".equals(provider))
                || !Boolean.TRUE.equals(user.getBoolean("profileComplete"))
                || !expectedIdentity.equals(user.getString("socialIdentity"))
                || !expectedIdentity.equals(provider + ":" + user.getString("socialSubject"))
                || expectedTokenVersion == null || user.getInteger("tokenVersion", 0) != expectedTokenVersion) {
            throw new ApiException(403, "가입한 소셜 계정으로 다시 인증해 주세요.");
        }
    }

    private void requireAdmin(String adminId) {
        Document admin = mongo.findById(adminId, Document.class, "users");
        if (admin == null) {
            throw new ApiException(401, "로그인이 필요합니다.");
        }
        if (!"ADMIN".equals(admin.getString("role"))) {
            throw new ApiException(403, "관리자만 이용할 수 있습니다.");
        }
    }

    private boolean retryable(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof MongoException mongoError
                    && (mongoError.hasErrorLabel("TransientTransactionError")
                    || mongoError.hasErrorLabel("UnknownTransactionCommitResult")
                    || mongoError.getCode() == 112 || mongoError.getCode() == 251)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private void pause(int attempt) {
        try {
            Thread.sleep(15L * (attempt + 1));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "요청이 중단되었습니다. 회원 정보를 확인해 주세요.");
        }
    }
}
