package kr.dmu.hospital.auth;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtException;

import static org.junit.jupiter.api.Assertions.*;

/** Every test uses a disposable database on loopback MongoDB; never Atlas. */
@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class PasswordChangeMongoTest {
    private MongoClient client;
    private MongoTemplate mongo;
    private AuthService auth;
    private String database;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private static final String SECRET = "local-password-test-signing-key-at-least-32-characters";
    private static final String CURRENT = "CurrentPassword2026!";
    private static final String NEXT = "ChangedPassword2026!";

    @BeforeEach void setUp() {
        String uri = System.getenv("MONGODB_TEST_URI");
        assertTrue(uri.startsWith("mongodb://"));
        for (String host : new ConnectionString(uri).getHosts()) {
            assertTrue(host.matches("(localhost|127\\.0\\.0\\.1|\\[::1\\])(:[0-9]+)?"));
        }
        client = MongoClients.create(uri);
        database = "hospital_password_test_" + UUID.randomUUID().toString().replace("-", "");
        mongo = new MongoTemplate(client, database);
        new UserIndexes(mongo).run(new DefaultApplicationArguments(new String[0]));
        auth = new AuthService(mongo, passwords, SECRET);
    }
    @AfterEach void tearDown() {
        SecurityContextHolder.clearContext();
        if (mongo != null && database.startsWith("hospital_password_test_")) mongo.getDb().drop();
        if (client != null) client.close();
    }

    @Test void changeStoresHashReturnsFreshSessionAndRevokesAllPreviousSessions() {
        Map<String, Object> registered = register("member1", CURRENT, "01012345678");
        String firstToken = (String) registered.get("token");
        String secondToken = (String) auth.login(Map.of("loginId", "member1", "password", CURRENT)).get("token");
        UserPrincipal current = authenticate(firstToken);
        Map<String, Object> result = auth.changePassword(TestContactProofs.phone(mongo, "PASSWORD_CHANGE", change(CURRENT, NEXT)));
        Document stored = mongo.findById(current.getId(), Document.class, "users");
        assertTrue(passwords.matches(NEXT, stored.getString("passwordHash")));
        assertFalse(passwords.matches(CURRENT, stored.getString("passwordHash")));
        assertNotEquals(NEXT, stored.getString("passwordHash"));
        assertEquals(1, stored.getInteger("tokenVersion"));
        assertThrows(JwtException.class, () -> auth.authenticate(firstToken));
        assertThrows(JwtException.class, () -> auth.authenticate(secondToken));
        UserPrincipal refreshed = auth.authenticate((String) result.get("token"));
        assertEquals(current.getId(), refreshed.getId());
        assertEquals(1, refreshed.getTokenVersion());
        assertEquals(registered.get("user"), result.get("user"));
        assertFalse(result.toString().contains("passwordHash"));
        assertFalse(result.toString().contains(NEXT));
        assertEquals(401, assertThrows(ApiException.class, () -> auth.login(Map.of("loginId", "member1", "password", CURRENT))).getStatus());
        assertEquals(current.getId(), auth.authenticate((String) auth.login(Map.of("loginId", "member1", "password", NEXT)).get("token")).getId());
    }

    @Test void incorrectCurrentPasswordDoesNotChangeHashOrEndExistingSession() {
        String token = (String) register("member1", CURRENT, "01012345678").get("token");
        UserPrincipal current = authenticate(token);
        Document before = mongo.findById(current.getId(), Document.class, "users");
        ApiException error = assertThrows(ApiException.class, () -> auth.changePassword(TestContactProofs.phone(mongo, "PASSWORD_CHANGE", change("WrongPassword!", NEXT))));
        assertEquals(400, error.getStatus());
        assertEquals("현재 비밀번호가 맞지 않습니다.", error.getMessage());
        assertEquals(before, mongo.findById(current.getId(), Document.class, "users"));
        assertEquals(current.getId(), auth.authenticate(token).getId());
    }

    @ParameterizedTest @MethodSource("invalidNewPasswords")
    void invalidNewPasswordCannotChangeExistingPassword(String value) {
        String token = (String) register("member1", CURRENT, "01012345678").get("token");
        UserPrincipal current = authenticate(token);
        assertEquals(400, assertThrows(ApiException.class, () -> auth.changePassword(TestContactProofs.phone(mongo, "PASSWORD_CHANGE", change(CURRENT, value)))).getStatus());
        assertTrue(passwords.matches(CURRENT, mongo.findById(current.getId(), Document.class, "users").getString("passwordHash")));
        assertEquals(0, auth.authenticate(token).getTokenVersion());
    }
    static Stream<String> invalidNewPasswords() { return Stream.of("", "1234567", "a".repeat(73), "가".repeat(25)); }

    @Test void malformedFieldsAndLongCurrentPasswordAreBadRequests() {
        authenticate((String) register("member1", CURRENT, "01012345678").get("token"));
        for (Map<String, Object> body : List.<Map<String, Object>>of(Map.of(), Map.of("currentPassword", CURRENT),
                Map.of("newPassword", NEXT), Map.of("currentPassword", 12345, "newPassword", NEXT),
                Map.of("currentPassword", CURRENT, "newPassword", List.of(NEXT)), change("a".repeat(73), NEXT))) {
            assertEquals(400, assertThrows(ApiException.class, () -> auth.changePassword(TestContactProofs.phone(mongo, "PASSWORD_CHANGE", body))).getStatus());
        }
        assertEquals(400, assertThrows(ApiException.class, () -> auth.changePassword(TestContactProofs.phone(mongo, "PASSWORD_CHANGE", null))).getStatus());
    }

    @Test void samePasswordIsRejectedWithoutRevokingSession() {
        String token = (String) register("member1", CURRENT, "01012345678").get("token");
        authenticate(token);
        assertEquals(400, assertThrows(ApiException.class, () -> auth.changePassword(TestContactProofs.phone(mongo, "PASSWORD_CHANGE", change(CURRENT, CURRENT)))).getStatus());
        assertEquals(0, auth.authenticate(token).getTokenVersion());
    }

    @Test void passwordsKeepLeadingAndTrailingSpacesAndAcceptUtf8ByteBoundary() {
        String spaced = "  CurrentPassword!  ";
        authenticate((String) register("member1", spaced, "01012345678").get("token"));
        assertEquals(400, assertThrows(ApiException.class, () -> auth.changePassword(TestContactProofs.phone(mongo, "PASSWORD_CHANGE", change(spaced.trim(), NEXT)))).getStatus());
        String changed = "  ChangedPassword!  ";
        Map<String, Object> response = auth.changePassword(TestContactProofs.phone(mongo, "PASSWORD_CHANGE", change(spaced, changed)));
        assertEquals(401, assertThrows(ApiException.class, () -> auth.login(Map.of("loginId", "member1", "password", changed.trim()))).getStatus());
        authenticate((String) response.get("token"));
        String boundary = "가".repeat(24);
        auth.changePassword(TestContactProofs.phone(mongo, "PASSWORD_CHANGE", change(changed, boundary)));
        assertNotNull(auth.login(Map.of("loginId", "member1", "password", boundary)).get("token"));
    }

    @Test void legacyAdminWithoutProviderOrVersionCanChangePassword() {
        mongo.insert(new Document("_id", "legacy-admin").append("loginId", "admin").append("name", "시험 관리자")
            .append("role", "ADMIN").append("passwordHash", passwords.encode(CURRENT)), "users");
        String token = (String) auth.login(Map.of("loginId", "admin", "password", CURRENT)).get("token");
        authenticate(token);
        Map<String, Object> result = auth.changePassword(TestContactProofs.phone(mongo, "PASSWORD_CHANGE", change(CURRENT, NEXT)));
        UserPrincipal refreshed = auth.authenticate((String) result.get("token"));
        assertEquals("ADMIN", refreshed.getRole());
        assertEquals("LOCAL", refreshed.getAuthProvider());
        assertEquals(1, refreshed.getTokenVersion());
        assertThrows(JwtException.class, () -> auth.authenticate(token));
    }

    @Test void unauthenticatedRequestIsRejected() {
        assertEquals(401, assertThrows(ApiException.class, () -> auth.changePassword(TestContactProofs.phone(mongo, "PASSWORD_CHANGE", change(CURRENT, NEXT)))).getStatus());
    }

    @Test void hospitalPasswordChangeKeepsTheExistingFlowWithoutPhoneVerification() {
        Document hospital = new Document("_id", "hospital-owner").append("loginId", "hospital_account")
                .append("name", "시험 병원").append("role", "HOSPITAL").append("authProvider", "LOCAL")
                .append("hospitalId", "hospital-1").append("businessNumber", "1234567890")
                .append("passwordHash", passwords.encode(CURRENT)).append("tokenVersion", 0);
        mongo.insert(hospital, "users");
        mongo.insert(new Document("_id", "hospital-1").append("userId", "hospital-owner")
                .append("businessNumber", "1234567890").append("status", "ACTIVE"), HospitalAccountService.REGISTRATIONS);
        String token = (String) auth.issueHospitalSession(hospital).get("token");
        authenticate(token);
        Map<String, Object> response = auth.changePassword(change(CURRENT, NEXT));
        UserPrincipal changed = auth.authenticate((String) response.get("token"));
        assertEquals("HOSPITAL", changed.getRole());
        assertEquals(1, changed.getTokenVersion());
        assertThrows(JwtException.class, () -> auth.authenticate(token));
        assertTrue(passwords.matches(NEXT, mongo.findById("hospital-owner", Document.class, "users").getString("passwordHash")));
        assertEquals(0, mongo.getCollection(ContactVerificationService.PROOFS).countDocuments());
    }

    @ParameterizedTest @ValueSource(strings = {"GOOGLE", "KAKAO"})
    void socialAndPendingMembersCannotAddServicePassword(String provider) {
        Document social = new Document("_id", "social-id").append("loginId", "social_member").append("name", "시험 회원")
            .append("role", "USER").append("authProvider", provider).append("profileComplete", true).append("tokenVersion", 0);
        mongo.insert(social, "users");
        as(new UserPrincipal(social));
        assertEquals(403, assertThrows(ApiException.class, () -> auth.changePassword(TestContactProofs.phone(mongo, "PASSWORD_CHANGE", change(CURRENT, NEXT)))).getStatus());
        as(UserPrincipal.forSignup(social));
        assertEquals(403, assertThrows(ApiException.class, () -> auth.changePassword(TestContactProofs.phone(mongo, "PASSWORD_CHANGE", change(CURRENT, NEXT)))).getStatus());
        assertFalse(mongo.findById("social-id", Document.class, "users").containsKey("passwordHash"));
    }

    @Test void bodyCannotChooseAnotherUserOrEscalateRole() {
        String ownerToken = (String) register("member1", CURRENT, "01012345678").get("token");
        String otherToken = (String) register("member2", CURRENT, "01012345679").get("token");
        UserPrincipal other = auth.authenticate(otherToken);
        UserPrincipal owner = authenticate(ownerToken);
        Map<String, Object> response = auth.changePassword(TestContactProofs.phone(mongo, "PASSWORD_CHANGE", Map.of("currentPassword", CURRENT, "newPassword", NEXT,
            "id", other.getId(), "userId", other.getId(), "role", "ADMIN", "tokenVersion", 99)));
        UserPrincipal changed = auth.authenticate((String) response.get("token"));
        assertEquals(owner.getId(), changed.getId());
        assertEquals("USER", changed.getRole());
        assertEquals(1, changed.getTokenVersion());
        assertTrue(passwords.matches(CURRENT, mongo.findById(other.getId(), Document.class, "users").getString("passwordHash")));
        assertEquals(other.getId(), auth.authenticate(otherToken).getId());
    }

    @Test void stalePrincipalAndDeletedMemberCannotChangePassword() {
        String token = (String) register("member1", CURRENT, "01012345678").get("token");
        UserPrincipal original = authenticate(token);
        auth.logout();
        assertEquals(401, assertThrows(ApiException.class, () -> auth.changePassword(TestContactProofs.phone(mongo, "PASSWORD_CHANGE", change(CURRENT, NEXT)))).getStatus());
        String fresh = (String) auth.login(Map.of("loginId", "member1", "password", CURRENT)).get("token");
        authenticate(fresh);
        mongo.getCollection("users").deleteOne(new Document("_id", original.getId()));
        assertEquals(401, assertThrows(ApiException.class, () -> auth.changePassword(TestContactProofs.phone(mongo, "PASSWORD_CHANGE", change(CURRENT, NEXT)))).getStatus());
    }

    @Test void concurrentChangesWithSameSessionAllowOnlyOneNewPassword() throws Exception {
        String token = (String) register("member1", CURRENT, "01012345678").get("token");
        UserPrincipal principal = auth.authenticate(token);
        CyclicBarrier beforeWrite = new CyclicBarrier(2);
        PasswordEncoder synchronizedEncoder = new PasswordEncoder() {
            @Override public String encode(CharSequence value) {
                try { beforeWrite.await(10, TimeUnit.SECONDS); }
                catch (Exception error) { throw new IllegalStateException(error); }
                return passwords.encode(value);
            }
            @Override public boolean matches(CharSequence value, String hash) { return passwords.matches(value, hash); }
        };
        AuthService parallelAuth = new AuthService(mongo, synchronizedEncoder, SECRET);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> concurrentChange(parallelAuth, principal, NEXT));
            var second = executor.submit(() -> concurrentChange(parallelAuth, principal, "AnotherPassword2026!"));
            Object result1 = first.get(15, TimeUnit.SECONDS), result2 = second.get(15, TimeUnit.SECONDS);
            assertEquals(1, (result1 instanceof Map ? 1 : 0) + (result2 instanceof Map ? 1 : 0));
            ApiException rejected = (ApiException) (result1 instanceof ApiException ? result1 : result2);
            assertEquals(401, rejected.getStatus());
            Map<?, ?> success = (Map<?, ?>) (result1 instanceof Map ? result1 : result2);
            UserPrincipal refreshed = auth.authenticate((String) success.get("token"));
            assertEquals(1, refreshed.getTokenVersion());
            Document user = mongo.findById(principal.getId(), Document.class, "users");
            assertEquals(result1 instanceof Map, passwords.matches(NEXT, user.getString("passwordHash")));
            assertEquals(result2 instanceof Map, passwords.matches("AnotherPassword2026!", user.getString("passwordHash")));
            assertThrows(JwtException.class, () -> auth.authenticate(token));
        } finally { executor.shutdownNow(); }
    }
    private Object concurrentChange(AuthService service, UserPrincipal principal, String next) {
        as(principal);
        try { return service.changePassword(TestContactProofs.phone(mongo, "PASSWORD_CHANGE", change(CURRENT, next))); }
        catch (ApiException error) { return error; }
        finally { SecurityContextHolder.clearContext(); }
    }
    private Map<String, Object> register(String loginId, String password, String phone) {
        return auth.register(TestContactProofs.signup(mongo, Map.of("name", "시험 회원", "loginId", loginId, "password", password,
            "email", loginId + "@example.com", "phone", phone)));
    }
    private Map<String, Object> change(String current, String next) { return Map.of("currentPassword", current, "newPassword", next); }
    private UserPrincipal authenticate(String token) { UserPrincipal principal = auth.authenticate(token); as(principal); return principal; }
    private void as(UserPrincipal principal) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of())); }
}
