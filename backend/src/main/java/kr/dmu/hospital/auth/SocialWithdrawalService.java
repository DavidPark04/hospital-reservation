package kr.dmu.hospital.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.mongodb.MongoException;
import java.time.Clock;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import kr.dmu.hospital.common.ApiException;
import kr.dmu.hospital.member.MemberService;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** A new provider authorization proves the same account before an explicit withdrawal POST. */
@Service
public class SocialWithdrawalService {
    static final String COLLECTION = "social_withdrawal_proofs";
    static final String COOKIE_NAME = "hospital_withdrawal_proof";
    static final String REQUEST_ATTRIBUTE = SocialWithdrawalService.class.getName() + ".intent";
    private final MongoTemplate mongo;
    private final SocialLoginSettings settings;
    private final MongoAuthorizationRequests requests;
    private final MemberService members;
    private final Clock clock;
    private final TransactionTemplate transaction;

    @Autowired public SocialWithdrawalService(MongoTemplate mongo, SocialLoginSettings settings,
            MongoAuthorizationRequests requests, MemberService members) {
        this(mongo, settings, requests, members, Clock.systemUTC());
    }
    SocialWithdrawalService(MongoTemplate mongo, SocialLoginSettings settings,
            MongoAuthorizationRequests requests, MemberService members, Clock clock) {
        this.mongo = mongo; this.settings = settings; this.requests = requests; this.members = members; this.clock = clock;
        this.transaction = new TransactionTemplate(new MongoTransactionManager(mongo.getMongoDatabaseFactory()));
        this.transaction.setTimeout(20);
    }

    public Map<String, String> start(String userId, HttpServletRequest request, HttpServletResponse response) {
        Document user = requireMember(userId);
        String provider = user.getString("authProvider").toLowerCase(Locale.ROOT);
        if (settings.registration(provider) == null) throw new ApiException(503, "소셜 로그인 설정을 확인한 뒤 다시 시도해 주세요.");
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(settings::registration,
            "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(builder -> builder.attributes(values -> {
                values.put("withdrawal_user_id", userId);
                values.put("withdrawal_identity", user.getString("socialIdentity"));
                values.put("withdrawal_version", Integer.toString(user.getInteger("tokenVersion", 0)));
            })
            .additionalParameters(values -> values.put("prompt", "kakao".equals(provider) ? "login" : "select_account")));
        OAuth2AuthorizationRequest authorization = resolver.resolve(request, provider);
        mongo.remove(Query.query(Criteria.where("userId").is(userId)), COLLECTION);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
        requests.saveAuthorizationRequest(authorization, request, response);
        return Map.of("authorizationUrl", authorization.getAuthorizationRequestUri());
    }

    public boolean isWithdrawal(HttpServletRequest request) { return request.getAttribute(REQUEST_ATTRIBUTE) instanceof Document; }

    /** Called only after Spring has validated the browser-bound OAuth state and provider response. */
    public String verify(HttpServletRequest request, String provider, Map<String, Object> attributes) {
        if (!(request.getAttribute(REQUEST_ATTRIBUTE) instanceof Document intent)) throw expired();
        String identity = SocialMemberService.identity(provider, attributes);
        if (!identity.equals(intent.getString("withdrawal_identity"))) {
            throw new ApiException(403, "탈퇴하려는 계정과 다른 소셜 계정입니다.");
        }
        Document user = requireMember(intent.getString("withdrawal_user_id"));
        if (!identity.equals(user.getString("socialIdentity"))
                || !Integer.toString(user.getInteger("tokenVersion", 0)).equals(intent.getString("withdrawal_version"))) throw expired();
        if (mongo.count(Query.query(Criteria.where("expiresAt").gt(Date.from(clock.instant()))), COLLECTION) >= 5000) {
            throw new ApiException(503, "인증 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        String value = SocialLoginTickets.randomValue();
        mongo.insert(new Document("_id", SocialLoginTickets.hash(value)).append("userId", user.getString("_id"))
            .append("socialIdentity", identity).append("tokenVersion", user.getInteger("tokenVersion", 0))
            .append("expiresAt", Date.from(clock.instant().plusSeconds(300))), COLLECTION);
        return value;
    }

    public Document confirm(String userId, String value) {
        return confirm(userId, value, null);
    }
    public Document confirm(String userId, String value, String phoneVerificationToken) {
        for (int attempt = 0; attempt < 8; attempt++) {
            try { return transaction.execute(status -> consumeAndWithdraw(userId, value, phoneVerificationToken)); }
            catch (RuntimeException error) {
                if (error instanceof ApiException || !retryable(error)) throw error;
                if (attempt == 7) throw new ApiException(409, "예약 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.");
                try { Thread.sleep(15L * (attempt + 1)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ApiException(503, "요청이 중단되었습니다. 다시 시도해 주세요.");
                }
            }
        }
        throw new ApiException(409, "잠시 후 다시 시도해 주세요.");
    }

    private Document consumeAndWithdraw(String userId, String value, String phoneVerificationToken) {
        Document user = requireMember(userId);
        if (!valid(value)) throw expired();
        Document proof = mongo.findAndRemove(Query.query(Criteria.where("_id").is(SocialLoginTickets.hash(value))
            .and("userId").is(userId).and("socialIdentity").is(user.getString("socialIdentity"))
            .and("tokenVersion").is(user.getInteger("tokenVersion", 0))
            .and("expiresAt").gt(Date.from(clock.instant()))), Document.class, COLLECTION);
        if (proof == null) throw expired();
        return members.withdrawSocial(userId, proof.getString("socialIdentity"), proof.getInteger("tokenVersion"), phoneVerificationToken);
    }

    public void cancel(String userId, String value) {
        if (valid(value)) mongo.remove(Query.query(Criteria.where("_id").is(SocialLoginTickets.hash(value)).and("userId").is(userId)), COLLECTION);
    }

    public ResponseCookie cookie(String value, long seconds) {
        return ResponseCookie.from(COOKIE_NAME, value).httpOnly(true).secure(settings.secureCookies())
            .sameSite("Strict").path("/api/auth/social/withdrawal").maxAge(seconds).build();
    }

    private Document requireMember(String userId) {
        Document user = mongo.findById(userId, Document.class, "users");
        if (user == null) throw new ApiException(401, "로그인이 필요합니다.");
        if (!"USER".equals(user.getString("role"))) throw new ApiException(403, "관리자 계정은 탈퇴할 수 없습니다.");
        String provider = user.getString("authProvider");
        if (!("GOOGLE".equals(provider) || "KAKAO".equals(provider))
                || !Boolean.TRUE.equals(user.getBoolean("profileComplete"))
                || user.getString("socialIdentity") == null) {
            throw new ApiException(403, "소셜 가입 계정만 소셜 인증으로 탈퇴할 수 있습니다.");
        }
        return user;
    }
    private boolean valid(String value) { return value != null && value.matches("[A-Za-z0-9_-]{43}"); }
    private boolean retryable(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoException mongoError && (mongoError.hasErrorLabel("TransientTransactionError")
                    || mongoError.getCode() == 112 || mongoError.getCode() == 251)) return true;
        }
        return false;
    }
    private ApiException expired() { return new ApiException(403, "탈퇴 인증이 만료되었어요. 소셜 계정을 다시 확인해 주세요."); }
}
