package kr.dmu.hospital.auth;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import kr.dmu.hospital.common.ApiException;
import kr.dmu.hospital.config.DemoData;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Uses a fresh test database on loopback MongoDB only; never connects to Atlas. */
@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class AuthMongoIntegrationTest {
    private MongoClient client;
    private MongoTemplate mongo;
    private AuthService auth;
    private String database;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private static final String PASSWORD = "TestPassword2026!";

    @BeforeEach
    void setUp() {
        String uri = System.getenv("MONGODB_TEST_URI");
        assertTrue(uri.startsWith("mongodb://"), "Auth tests require local MongoDB.");
        for (String host : new ConnectionString(uri).getHosts()) {
            assertTrue(host.matches("(localhost|127\\.0\\.0\\.1|\\[::1\\])(:[0-9]+)?"),
                    "Auth tests must use a loopback MongoDB host.");
        }
        client = MongoClients.create(uri);
        database = "hospital_auth_test_" + UUID.randomUUID().toString().replace("-", "");
        mongo = new MongoTemplate(client, database);
        initializeIndexes();
        auth = new AuthService(mongo, passwords, "local-auth-test-signing-key-at-least-32-characters");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (mongo != null && database != null && database.startsWith("hospital_auth_test_")) {
            mongo.getDb().drop();
        }
        if (client != null) client.close();
    }

    @Test
    void signupAndLoginUseNormalizedIdWhileKeepingInternalIdAndPasswordPrivate() {
        Map<String, Object> registered = auth.register(registration("  Patient_01-A  "));
        Map<?, ?> user = (Map<?, ?>) registered.get("user");
        String id = (String) user.get("id");
        assertEquals("patient_01-a", user.get("loginId"));
        assertEquals("시험 회원", user.get("name"));
        assertEquals("USER", user.get("role"));
        assertNotEquals("patient_01-a", id);
        assertEquals(8, user.size());
        assertEquals("LOCAL", user.get("authProvider"));
        assertEquals(true, user.get("profileComplete"));
        assertEquals(false, user.get("emailVerified"));
        assertEquals("01012345678", user.get("phone"));
        assertFalse(user.containsKey("email"));
        assertFalse(user.containsKey("passwordHash"));
        Document stored = mongo.findById(id, Document.class, "users");
        assertNotNull(stored);
        assertEquals("patient_01-a", stored.getString("loginId"));
        assertFalse(stored.containsKey("emailNormalized"));
        assertNotEquals(PASSWORD, stored.getString("passwordHash"));
        assertTrue(passwords.matches(PASSWORD, stored.getString("passwordHash")));

        Map<String, Object> signedIn = auth.login(Map.of("loginId", " PATIENT_01-A ", "password", PASSWORD));
        assertEquals(user, signedIn.get("user"));
        assertEquals(id, auth.authenticate((String) registered.get("token")).getId());
        assertEquals("patient_01-a", auth.authenticate((String) signedIn.get("token")).getLoginId());
    }

    @Test
    void unknownIdAndIncorrectPasswordAreRejected() {
        auth.register(registration("patient1"));
        ApiException missing = assertThrows(ApiException.class,
                () -> auth.login(Map.of("loginId", "absent1", "password", PASSWORD)));
        ApiException wrong = assertThrows(ApiException.class,
                () -> auth.login(Map.of("loginId", "patient1", "password", "WrongPassword!")));
        assertEquals(401, missing.getStatus());
        assertEquals(401, wrong.getStatus());
        assertEquals("아이디 또는 비밀번호가 맞지 않습니다.", wrong.getMessage());
    }

    @Test
    void duplicateIdsAreNormalizedAndProtectedByTheDatabaseUniqueIndex() {
        auth.register(registration(" Patient1 "));
        ApiException duplicate = assertThrows(ApiException.class,
                () -> auth.register(registration("PATIENT1")));
        assertEquals(409, duplicate.getStatus());
        assertEquals("이미 사용 중인 아이디입니다.", duplicate.getMessage());
        assertThrows(DuplicateKeyException.class,
                () -> mongo.insert(new Document("_id", "another-user").append("loginId", "patient1"), "users"));
        assertEquals(1, mongo.getCollection("users").countDocuments());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "abcdefghijklmnopqrstuvwxyz12345", "patient@example.com",
            "patient name", "patient.name", "회원아이디", "abcd/ef", "abcd+ef", "Kate"})
    void rejectsInvalidIdsDuringSignupAndLogin(String loginId) {
        ApiException signup = assertThrows(ApiException.class, () -> auth.register(registration(loginId)));
        ApiException login = assertThrows(ApiException.class,
                () -> auth.login(Map.of("loginId", loginId, "password", PASSWORD)));
        assertEquals(400, signup.getStatus());
        assertEquals(400, login.getStatus());
        assertEquals(0, mongo.getCollection("users").countDocuments());
    }

    @Test
    void emailOnlyRequestsAreRejected() {
        Map<String, Object> oldBody = Map.of("name", "시험 회원", "email", "patient@example.com", "password", PASSWORD);
        assertEquals(400, assertThrows(ApiException.class, () -> auth.register(oldBody)).getStatus());
        assertEquals(400, assertThrows(ApiException.class, () -> auth.login(oldBody)).getStatus());
        assertEquals(0, mongo.getCollection("users").countDocuments());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcd", "1234", "a_b-c", "abcdefghijklmnopqrstuvwxyz1234"})
    void acceptsIdsAtTheLengthBoundariesAndAllSupportedCharacters(String loginId) {
        Map<String, Object> registered = auth.register(registration(loginId));
        assertEquals(loginId, auth.authenticate((String) registered.get("token")).getLoginId());
    }

    @Test
    void signupCannotChooseAdminRoleOrInternalIdentity() {
        Map<String, Object> response = auth.register(Map.of("name", "시험 회원", "loginId", "patient1",
                "password", PASSWORD, "phone", "01012345678", "role", "ADMIN", "id", "admin-user", "tokenVersion", 99));
        UserPrincipal user = auth.authenticate((String) response.get("token"));
        assertEquals("USER", user.getRole());
        assertNotEquals("admin-user", user.getId());
        Document stored = mongo.findById(user.getId(), Document.class, "users");
        assertNotNull(stored);
        assertEquals(0, stored.getInteger("tokenVersion"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        assertEquals(403, assertThrows(ApiException.class, CurrentUser::requireAdmin).getStatus());
    }

    @Test
    void existingAdminUsesIdAndKeepsStoredRole() {
        mongo.insert(new Document("_id", "test-admin-internal-id").append("name", "시험 관리자")
                .append("loginId", "admin").append("passwordHash", passwords.encode(PASSWORD))
                .append("role", "ADMIN").append("tokenVersion", 0), "users");
        Map<String, Object> response = auth.login(Map.of("loginId", " ADMIN ", "password", PASSWORD));
        UserPrincipal admin = auth.authenticate((String) response.get("token"));
        assertEquals("test-admin-internal-id", admin.getId());
        assertEquals("admin", admin.getLoginId());
        assertEquals("ADMIN", admin.getRole());
    }

    @Test
    void deletedUserTokenCannotAuthenticateEvenAfterTheLoginIdIsReused() {
        Map<String, Object> original = auth.register(registration("patient1"));
        String token = (String) original.get("token");
        String originalId = auth.authenticate(token).getId();
        mongo.getCollection("users").deleteOne(new Document("_id", originalId));
        assertThrows(JwtException.class, () -> auth.authenticate(token));
        Map<String, Object> replacement = auth.register(registration("patient1"));
        assertNotEquals(originalId, auth.authenticate((String) replacement.get("token")).getId());
        assertThrows(JwtException.class, () -> auth.authenticate(token));
    }

    @Test
    void logoutInvalidatesPreviousTokensAndNewLoginWorks() {
        Map<String, Object> registered = auth.register(registration("patient1"));
        String token = (String) registered.get("token");
        UserPrincipal user = auth.authenticate(token);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        auth.logout();
        assertThrows(JwtException.class, () -> auth.authenticate(token));
        Map<String, Object> newSession = auth.login(Map.of("loginId", "patient1", "password", PASSWORD));
        assertEquals(user.getId(), auth.authenticate((String) newSession.get("token")).getId());
    }

    @Test
    void loginIdIndexIsNamedUniqueAndIdempotentForAnExistingEmptyCollection() {
        initializeIndexes();
        Document index = null;
        for (Document current : mongo.getCollection("users").listIndexes()) {
            if ("user_login_id_v1".equals(current.getString("name"))) index = current;
        }
        assertNotNull(index);
        assertEquals(new Document("loginId", 1), index.get("key"));
        assertEquals(true, index.getBoolean("unique"));
        assertEquals(0, mongo.getCollection("users").countDocuments());
    }

    @Test
    void demoModeSeedsOnlyHospitalsAndNeverRecreatesUsers() {
        DefaultApplicationArguments arguments = new DefaultApplicationArguments(new String[0]);
        new DemoData(mongo, false).run(arguments);
        assertEquals(0, mongo.getCollection("hospitals").countDocuments());
        new DemoData(mongo, true).run(arguments);
        new DemoData(mongo, true).run(arguments);
        assertEquals(6, mongo.getCollection("hospitals").countDocuments());
        assertEquals(6, mongo.getCollection("hospitalEvidence").countDocuments());
        assertEquals(0, mongo.getCollection("users").countDocuments());
    }

    private Map<String, Object> registration(String loginId) {
        return Map.of("name", "시험 회원", "loginId", loginId, "password", PASSWORD, "phone", "01012345678");
    }

    @Test void registrationRequiresPhoneAndStoresNormalizedDomesticNumber() {
        assertEquals(400, assertThrows(ApiException.class, () -> auth.register(Map.of("name", "회원", "loginId", "newuser", "password", PASSWORD))).getStatus());
        Map<String, Object> response = auth.register(Map.of("name", "회원", "loginId", "newuser", "password", PASSWORD, "phone", " 010-1234-5678 "));
        assertEquals("01012345678", ((Map<?, ?>) response.get("user")).get("phone"));
        String id = auth.authenticate((String) response.get("token")).getId();
        assertEquals("01012345678", mongo.findById(id, Document.class, "users").getString("phone"));
    }

    @ParameterizedTest @ValueSource(strings = {"", "1234567890", "0101234", "010123456789", "010-ABCD-5678", "+821012345678"})
    void registrationRejectsInvalidPhone(String phone) {
        assertEquals(400, assertThrows(ApiException.class, () -> auth.register(Map.of("name", "회원", "loginId", "newuser", "password", PASSWORD, "phone", phone))).getStatus());
        assertEquals(0, mongo.getCollection("users").countDocuments());
    }

    private void initializeIndexes() {
        new UserIndexes(mongo).run(new DefaultApplicationArguments(new String[0]));
    }
}
