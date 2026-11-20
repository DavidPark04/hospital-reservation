package kr.dmu.hospital.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.mongodb.MongoException;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {
    private final MongoTemplate mongo;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final TransactionTemplate transaction;
    public AuthService(MongoTemplate mongo, PasswordEncoder passwords, String secret) {
        this(mongo, passwords, secret, new MongoTransactionManager(mongo.getMongoDatabaseFactory()));
    }
    @Autowired
    public AuthService(MongoTemplate mongo, PasswordEncoder passwords, @Value("${app.jwt-secret}") String secret, MongoTransactionManager transactionManager) {
        if (secret.length() < 32) throw new IllegalArgumentException("JWT_SECRET must be at least 32 characters.");
        this.mongo = mongo; this.passwords = passwords;
        transaction = new TransactionTemplate(transactionManager); transaction.setTimeout(20);
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder decoderValue = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build();
        decoderValue.setJwtValidator(JwtValidators.createDefaultWithIssuer("hospital-reservation-local"));
        decoder = decoderValue;
    }
    public Map<String, Object> register(Map<String, Object> body) {
        String name = text(body, "name"); String loginId = loginId(body); String password = password(body); String phone = phone(body); String email = email(body);
        if (name.isBlank() || name.length() > 40) throw new ApiException(400, "이름은 1~40자로 입력해 주세요.");
        String normalizedName = MemberIdentity.normalizedName(name);
        if (normalizedName.isBlank()) throw new ApiException(400, "이름은 1~40자로 입력해 주세요.");
        if (mongo.exists(Query.query(Criteria.where("loginId").is(loginId)), "users")) throw loginIdConflict();
        String normalizedEmail = MemberIdentity.normalizedEmail(email);
        requireAvailableContacts(normalizedName, phone, normalizedEmail);
        Document user = new Document("_id", UUID.randomUUID().toString()).append("name", name).append("loginId", loginId)
            .append("passwordHash", passwords.encode(password)).append("phone", phone).append("nameNormalized", normalizedName).append("memberIdentityClaimed", true)
            .append("email", email).append("emailNormalized", normalizedEmail).append("emailSource", "USER").append("emailVerified", false)
            .append("role", "USER").append("authProvider", "LOCAL").append("tokenVersion", 0).append("createdAt", new Date());
        try { mongo.insert(user, "users"); }
        catch (DuplicateKeyException error) {
            if (mongo.exists(Query.query(Criteria.where("loginId").is(loginId)), "users")) throw loginIdConflict();
            requireAvailableContacts(normalizedName, phone, normalizedEmail);
            throw new ApiException(409, "이미 등록된 회원 정보입니다. 다시 확인해 주세요.");
        }
        return issue(user);
    }
    public Map<String, Object> login(Map<String, Object> body) {
        String loginId = loginId(body); String password = text(body, "password");
        if (password.length() > 128) throw new ApiException(400, "입력 정보를 확인해 주세요.");
        Document user = mongo.findOne(Query.query(Criteria.where("loginId").is(loginId)), Document.class, "users");
        if (user == null || user.getString("passwordHash") == null || !passwords.matches(password, user.getString("passwordHash"))) throw new ApiException(401, "아이디 또는 비밀번호가 맞지 않습니다.");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(user.getString("_id"))), new Update().set("lastLoginAt", new Date()), "users");
        return issue(user);
    }
    Map<String, Object> issueSocialSession(String userId, int tokenVersion) {
        return issueSocialSession(userId, tokenVersion, false);
    }
    Map<String, Object> issueSocialSession(String id, int tokenVersion, boolean signupPending) {
        Document document = mongo.findById(id, Document.class, signupPending ? SocialSignups.COLLECTION : "users");
        if (document == null || document.getInteger("tokenVersion", 0) != tokenVersion
                || (signupPending ? !SocialSignups.live(document) : !MemberIdentity.complete(document))) throw signupExpired();
        return issue(document, signupPending);
    }
    public Map<String, Object> completeSocialProfile(Map<String, Object> body) {
        UserPrincipal current = CurrentUser.require();
        if (!(current.getAuthProvider().equals("GOOGLE") || current.getAuthProvider().equals("KAKAO"))) {
            throw new ApiException(403, "소셜 로그인 회원만 추가 정보를 등록할 수 있습니다.");
        }
        if (current.isProfileComplete()) throw new ApiException(409, "이미 회원 정보 등록을 완료했습니다.");
        if (!current.isSignupPending()) throw signupExpired();
        Document activeSignup = mongo.findById(current.getId(), Document.class, SocialSignups.COLLECTION);
        if (!SocialSignups.live(activeSignup) || activeSignup.getInteger("tokenVersion", 0) != current.getTokenVersion()) throw signupExpired();
        String name = text(body, "name");
        String email = email(body);
        String phone = phone(body);
        if (name.isBlank() || name.length() > 40 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(400, "이름은 1~40자로 입력해 주세요.");
        }
        String normalizedName = MemberIdentity.normalizedName(name);
        if (normalizedName.isBlank()) throw new ApiException(400, "이름은 1~40자로 입력해 주세요.");
        String normalizedEmail = MemberIdentity.normalizedEmail(email);
        requireAvailableContacts(normalizedName, phone, normalizedEmail);
        for (int attempt = 0; attempt < 8; attempt++) {
            try {
                Document member = transaction.execute(status -> {
                    Query pending = Query.query(Criteria.where("_id").is(current.getId()).and("tokenVersion").is(current.getTokenVersion())
                        .and("expiresAt").gt(new Date()));
                    Document signup = mongo.findOne(pending, Document.class, SocialSignups.COLLECTION);
                    if (!SocialSignups.live(signup)) throw signupExpired();
                    if (mongo.exists(Query.query(Criteria.where("socialIdentity").is(signup.getString("socialIdentity"))), "users")) throw signupExpired();
                    requireAvailableContacts(normalizedName, phone, normalizedEmail);
                    String provider = signup.getString("authProvider");
                    Document user = new Document("_id", UUID.randomUUID().toString())
                        .append("loginId", provider.toLowerCase(Locale.ROOT) + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 22))
                        .append("authProvider", provider).append("socialSubject", signup.getString("socialSubject")).append("socialIdentity", signup.getString("socialIdentity"))
                        .append("name", name).append("email", email).append("emailNormalized", normalizedEmail).append("phone", phone).append("emailSource", "USER").append("emailVerified", false)
                        .append("nameNormalized", normalizedName).append("memberIdentityClaimed", true).append("profileComplete", true)
                        .append("profileCompletedAt", new Date()).append("createdAt", new Date()).append("role", "USER").append("tokenVersion", 0);
                    mongo.insert(user, "users");
                    if (mongo.remove(pending, SocialSignups.COLLECTION).getDeletedCount() != 1) throw signupExpired();
                    return user;
                });
                return issue(member);
            } catch (DuplicateKeyException error) {
                requireAvailableContacts(normalizedName, phone, normalizedEmail);
                if (attempt == 7) throw signupExpired();
            } catch (RuntimeException error) {
                if (error instanceof ApiException || !retryable(error) || attempt == 7) throw error;
            }
        }
        throw new ApiException(503, "가입 요청을 처리하지 못했습니다. 다시 로그인해 주세요.");
    }
    private Map<String, Object> issue(Document user) { return issue(user, false); }
    private Map<String, Object> issue(Document user, boolean signupPending) {
        Instant now = Instant.now();
        Instant expiry = signupPending ? user.getDate("expiresAt").toInstant() : now.plusSeconds(8 * 60 * 60);
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("hospital-reservation-local").subject(user.getString("_id"))
            .issuedAt(now).expiresAt(expiry).id(UUID.randomUUID().toString()).claim("purpose", signupPending ? "SIGNUP" : "MEMBER")
            .claim("ver", user.getInteger("tokenVersion", 0)).audience(List.of("hospital-web")).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return Map.of("token", token, "user", (signupPending ? UserPrincipal.forSignup(user) : new UserPrincipal(user)).toPublic());
    }
    public UserPrincipal authenticate(String token) {
        Jwt jwt = decoder.decode(token);
        if (!jwt.getAudience().contains("hospital-web")) throw new JwtException("Invalid audience");
        String purpose = jwt.getClaimAsString("purpose");
        boolean signupPending = "SIGNUP".equals(purpose);
        if (!signupPending && purpose != null && !"MEMBER".equals(purpose)) throw new JwtException("Invalid token purpose");
        Document user = mongo.findById(jwt.getSubject(), Document.class, signupPending ? SocialSignups.COLLECTION : "users");
        Number version = jwt.getClaim("ver");
        if (user == null || version == null || version.intValue() != user.getInteger("tokenVersion", 0)
                || (signupPending ? !SocialSignups.live(user) : !MemberIdentity.complete(user))) throw new JwtException("Expired session");
        return signupPending ? UserPrincipal.forSignup(user) : new UserPrincipal(user);
    }
    public void logout() {
        UserPrincipal current = CurrentUser.require();
        if (current.isSignupPending()) {
            mongo.remove(Query.query(Criteria.where("_id").is(current.getId()).and("tokenVersion").is(current.getTokenVersion())), SocialSignups.COLLECTION);
        } else mongo.updateFirst(Query.query(Criteria.where("_id").is(current.getId())), new Update().inc("tokenVersion", 1), "users");
    }
    private static String loginId(Map<String, Object> body) {
        String loginId = text(body, "loginId");
        if (!loginId.matches("[A-Za-z0-9_-]{4,30}")) throw new ApiException(400, "아이디는 영문, 숫자, 밑줄(_), 하이픈(-)을 사용해 4~30자로 입력해 주세요.");
        return loginId.toLowerCase(Locale.ROOT);
    }
    private static String password(Map<String, Object> body) {
        String password = text(body, "password");
        if (password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) throw new ApiException(400, "비밀번호는 8자 이상, 72바이트 이하로 입력해 주세요.");
        return password;
    }
    private static String phone(Map<String, Object> body) {
        String phone = MemberIdentity.normalizedPhone(text(body, "phone"));
        if (!phone.matches("0[0-9]{8,10}")) throw new ApiException(400, "전화번호는 0으로 시작하는 9~11자리 숫자로 입력해 주세요.");
        return phone;
    }
    private static String email(Map<String, Object> body) {
        String email = text(body, "email");
        if (!MemberIdentity.validEmail(email)) throw new ApiException(400, "올바른 이메일 주소를 입력해 주세요.");
        return email;
    }
    private static ApiException loginIdConflict() { return new ApiException(409, "이미 사용 중인 아이디입니다."); }
    private static ApiException signupExpired() { return new ApiException(401, "가입 인증 시간이 지났어요. 소셜 로그인을 다시 진행해 주세요."); }
    private static boolean retryable(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoException mongoError && (mongoError.hasErrorLabel("TransientTransactionError") || mongoError.getCode() == 112 || mongoError.getCode() == 251)) return true;
        }
        return false;
    }
    private void requireAvailableContacts(String normalizedName, String phone, String normalizedEmail) {
        Document existing = mongo.findOne(Query.query(Criteria.where("memberIdentityClaimed").is(true).and("phone").is(phone)), Document.class, "users");
        if (existing != null) throw contactConflict(existing, normalizedName, phone);
        existing = mongo.findOne(Query.query(Criteria.where("memberIdentityClaimed").is(true).and("emailNormalized").is(normalizedEmail)), Document.class, "users");
        if (existing != null) throw contactConflict(existing, normalizedName, phone);
    }
    private static ApiException contactConflict(Document existing, String normalizedName, String phone) {
        String existingName = existing.get("name") instanceof String name ? MemberIdentity.normalizedName(name) : "";
        String existingPhone = existing.get("phone") instanceof String value ? MemberIdentity.normalizedPhone(value) : "";
        if (normalizedName.equals(existingName) && phone.equals(existingPhone)) {
            return new MemberIdentityConflict(existing.getString("authProvider"));
        }
        return new ApiException(409, "이미 가입된 정보가 있어요. 입력 정보를 확인해 주세요.");
    }
    private static String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String)) throw new ApiException(400, "필수 입력 항목을 확인해 주세요.");
        return key.equals("password") ? (String) value : ((String) value).trim();
    }
}
