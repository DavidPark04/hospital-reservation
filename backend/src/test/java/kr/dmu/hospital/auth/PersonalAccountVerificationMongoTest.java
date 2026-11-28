package kr.dmu.hospital.auth;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.dmu.hospital.booking.BookingService;
import kr.dmu.hospital.common.ApiErrorHandler;
import kr.dmu.hospital.common.ApiException;
import kr.dmu.hospital.member.MemberService;
import kr.dmu.hospital.member.MyMemberController;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real service/transaction coverage with synthetic receipts; never sends email or SMS. */
@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class PersonalAccountVerificationMongoTest {
    private MongoClient client;
    private MongoTemplate mongo;
    private String database;
    private AuthService auth;
    private MemberService members;
    private MockMvc mvc;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private static final String CURRENT = "OriginalPassword2026!";
    private static final String NEXT = "UpdatedPassword2026!";

    @BeforeEach void setUp() {
        String uri = System.getenv("MONGODB_TEST_URI");
        assertTrue(uri.startsWith("mongodb://"));
        for (String host : new ConnectionString(uri).getHosts()) assertTrue(host.matches("(localhost|127\\.0\\.0\\.1|\\[::1\\])(:[0-9]+)?"));
        client = MongoClients.create(uri);
        database = "hospital_personal_auth_test_" + UUID.randomUUID().toString().replace("-", "");
        mongo = new MongoTemplate(client, database);
        new UserIndexes(mongo).run(new DefaultApplicationArguments(new String[0]));
        auth = new AuthService(mongo, passwords, "personal-verification-test-secret-at-least-32-characters");
        members = new MemberService(mongo, mock(BookingService.class), new MongoTransactionManager(mongo.getMongoDatabaseFactory()), passwords);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth), new MyMemberController(members))
                .setControllerAdvice(new ApiErrorHandler()).build();
    }

    @AfterEach void tearDown() {
        SecurityContextHolder.clearContext();
        if (mongo != null && database.startsWith("hospital_personal_auth_test_")) mongo.getDb().drop();
        if (client != null) client.close();
    }

    @Test void signupRequiresBothMatchingReceiptsAndRollsBackPartialConsumption() throws Exception {
        Map<String, Object> form = registration("member1", "01012345678");
        form.put("emailVerified", true); form.put("phoneVerified", true);
        mvc.perform(post("/api/auth/register").contentType("application/json").content(new Document(form).toJson()))
                .andExpect(status().isBadRequest());
        String emailProof = TestContactProofs.seed(mongo, "SIGNUP", "EMAIL", "member1@example.com", "", 0);
        form.put("emailVerificationToken", emailProof);
        mvc.perform(post("/api/auth/register").contentType("application/json").content(new Document(form).toJson()))
                .andExpect(status().isBadRequest());
        assertNotNull(receipt(emailProof));
        form.put("phoneVerificationToken", TestContactProofs.seed(mongo, "SIGNUP", "PHONE", "01087654321", "", 0));
        assertEquals(400, assertThrows(ApiException.class, () -> auth.register(form)).getStatus());
        assertNotNull(receipt(emailProof));
        String phoneProof = TestContactProofs.seed(mongo, "SIGNUP", "PHONE", "01012345678", "", 0);
        form.put("phoneVerificationToken", phoneProof);
        mvc.perform(post("/api/auth/register").contentType("application/json").content(new Document(form).toJson()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.emailVerified").value(true))
                .andExpect(jsonPath("$.user.phoneVerified").value(true));
        assertNull(receipt(emailProof)); assertNull(receipt(phoneProof));
        assertEquals(1, mongo.getCollection("users").countDocuments());
    }

    @Test void passwordChangeRejectsMissingWrongPurposeWrongAccountExpiredAndStaleProofs() throws Exception {
        String token = register("member1", "01012345678");
        String id = authenticate(token).getId();
        Document before = mongo.findById(id, Document.class, "users");
        Map<String, Object> form = new HashMap<>(Map.of("currentPassword", CURRENT, "newPassword", NEXT));
        mvc.perform(patch("/api/auth/password").contentType("application/json").content(new Document(form).toJson()))
                .andExpect(status().isBadRequest());
        for (String proof : List.of(
                TestContactProofs.seed(mongo, "WITHDRAWAL", "PHONE", "01012345678", id, 0),
                TestContactProofs.seed(mongo, "PASSWORD_CHANGE", "PHONE", "01012345678", "another-member", 0),
                TestContactProofs.seed(mongo, "PASSWORD_CHANGE", "PHONE", "01012345678", id, 1),
                TestContactProofs.seed(mongo, "PASSWORD_CHANGE", "PHONE", "01087654321", id, 0))) {
            form.put("phoneVerificationToken", proof);
            assertEquals(400, assertThrows(ApiException.class, () -> auth.changePassword(form)).getStatus());
            assertNotNull(receipt(proof));
        }
        String expired = TestContactProofs.phoneToken(mongo, "PASSWORD_CHANGE", id);
        mongo.getCollection(ContactVerificationService.PROOFS).updateOne(new Document("_id", SocialLoginTickets.hash(expired)),
                new Document("$set", new Document("expiresAt", Date.from(Instant.now().minusSeconds(1)))));
        form.put("phoneVerificationToken", expired);
        assertEquals(400, assertThrows(ApiException.class, () -> auth.changePassword(form)).getStatus());
        assertEquals(before, mongo.findById(id, Document.class, "users"));
        assertEquals(id, auth.authenticate(token).getId());
        String valid = TestContactProofs.phoneToken(mongo, "PASSWORD_CHANGE", id);
        form.put("phoneVerificationToken", valid);
        mvc.perform(patch("/api/auth/password").contentType("application/json").content(new Document(form).toJson()))
                .andExpect(status().isOk());
        assertNull(receipt(valid));
        assertThrows(JwtException.class, () -> auth.authenticate(token));
    }

    @Test void emailChangeBindsNewAddressAndAccountThenRevokesPreviousSession() throws Exception {
        String token = register("member1", "01012345678");
        String id = authenticate(token).getId();
        Map<String, Object> form = new HashMap<>(Map.of("email", " NewAddress@example.com "));
        mvc.perform(patch("/api/auth/email").contentType("application/json").content(new Document(form).toJson()))
                .andExpect(status().isBadRequest());
        String wrong = TestContactProofs.seed(mongo, "EMAIL_CHANGE", "EMAIL", "different@example.com", id, 0);
        form.put("emailVerificationToken", wrong);
        assertEquals(400, assertThrows(ApiException.class, () -> auth.changeEmail(form)).getStatus());
        String valid = TestContactProofs.seed(mongo, "EMAIL_CHANGE", "EMAIL", "newaddress@example.com", id, 0);
        form.put("emailVerificationToken", valid);
        mvc.perform(patch("/api/auth/email").contentType("application/json").content(new Document(form).toJson()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.email").value("NewAddress@example.com"))
                .andExpect(jsonPath("$.user.emailVerified").value(true));
        Document stored = mongo.findById(id, Document.class, "users");
        assertEquals("newaddress@example.com", stored.getString("emailNormalized"));
        assertEquals(1, stored.getInteger("tokenVersion"));
        assertNull(receipt(valid)); assertNotNull(receipt(wrong));
        assertThrows(JwtException.class, () -> auth.authenticate(token));
    }

    @Test void resetRequiresTheMatchingLocalAccountAndSingleUsePhoneReceipt() throws Exception {
        String oldToken = register("member1", "01012345678");
        String id = auth.authenticate(oldToken).getId();
        Map<String, Object> form = new HashMap<>(Map.of("loginId", "member1", "newPassword", NEXT));
        mvc.perform(post("/api/auth/password/reset").contentType("application/json").content(new Document(form).toJson()))
                .andExpect(status().isBadRequest());
        form.put("phoneVerificationToken", TestContactProofs.phoneToken(mongo, "PASSWORD_CHANGE", id));
        assertEquals(400, assertThrows(ApiException.class, () -> auth.resetPassword(form)).getStatus());
        String proof = TestContactProofs.phoneToken(mongo, "PASSWORD_RESET", id);
        form.put("phoneVerificationToken", proof);
        form.put("loginId", "somebody-else");
        assertEquals(400, assertThrows(ApiException.class, () -> auth.resetPassword(form)).getStatus());
        assertNotNull(receipt(proof));
        form.put("loginId", "MEMBER1");
        mvc.perform(post("/api/auth/password/reset").contentType("application/json").content(new Document(form).toJson()))
                .andExpect(status().isOk());
        assertNull(receipt(proof));
        assertThrows(JwtException.class, () -> auth.authenticate(oldToken));
        assertEquals(401, assertThrows(ApiException.class, () -> auth.login(Map.of("loginId", "member1", "password", CURRENT))).getStatus());
        assertNotNull(auth.login(Map.of("loginId", "member1", "password", NEXT)).get("token"));
        form.put("newPassword", "YetAnotherPassword2026!");
        assertEquals(400, assertThrows(ApiException.class, () -> auth.resetPassword(form)).getStatus());
    }

    @Test void localAndSocialWithdrawalBothRequirePhoneAndCannotUseAnotherPurpose() throws Exception {
        for (String provider : List.of("LOCAL", "GOOGLE")) {
            SecurityContextHolder.clearContext();
            String token = register("member" + provider, provider.equals("LOCAL") ? "01012345678" : "01087654321");
            String id = auth.authenticate(token).getId();
            if (provider.equals("GOOGLE")) mongo.getCollection("users").updateOne(new Document("_id", id), new Document("$set",
                    new Document("authProvider", "GOOGLE").append("profileComplete", true).append("socialIdentity", "GOOGLE:subject")));
            authenticate(token);
            assertEquals(400, assertThrows(ApiException.class, () -> members.withdraw(id, CURRENT)).getStatus());
            String wrong = TestContactProofs.phoneToken(mongo, "PASSWORD_RESET", id);
            mvc.perform(delete("/api/members/me").contentType("application/json").content(new Document("password", CURRENT)
                    .append("phoneVerificationToken", wrong).toJson())).andExpect(status().isBadRequest());
            assertNotNull(mongo.findById(id, Document.class, "users"));
            mvc.perform(delete("/api/members/me").contentType("application/json").content(new Document("password", CURRENT)
                    .append("phoneVerificationToken", TestContactProofs.phoneToken(mongo, "WITHDRAWAL", id)).toJson()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.deletedUserId").value(id));
            assertNull(mongo.findById(id, Document.class, "users"));
            assertThrows(JwtException.class, () -> auth.authenticate(token));
        }
    }

    @Test void emailChangesRequireLoginAndResetCannotChangeAdminOrSocialAccounts() throws Exception {
        mvc.perform(patch("/api/auth/email").contentType("application/json").content("{\"email\":\"new@example.com\"}"))
                .andExpect(status().isUnauthorized());
        for (String kind : List.of("ADMIN", "GOOGLE")) {
            String id = "restricted-" + kind;
            mongo.insert(new Document("_id", id).append("loginId", id.toLowerCase()).append("role", kind.equals("ADMIN") ? "ADMIN" : "USER")
                    .append("authProvider", kind.equals("ADMIN") ? "LOCAL" : "GOOGLE").append("phone", "01012345678")
                    .append("passwordHash", passwords.encode(CURRENT)).append("profileComplete", true), "users");
            String proof = TestContactProofs.phoneToken(mongo, "PASSWORD_RESET", id);
            assertEquals(400, assertThrows(ApiException.class, () -> auth.resetPassword(Map.of("loginId", id,
                    "newPassword", NEXT, "phoneVerificationToken", proof))).getStatus());
            assertTrue(passwords.matches(CURRENT, mongo.findById(id, Document.class, "users").getString("passwordHash")));
            assertNotNull(receipt(proof));
        }
    }

    @Test void temporaryMembersCanLoginButCannotUseContactProofsToMutateTheirAccounts() {
        String id = "test0001";
        mongo.insert(new Document("_id", id).append("loginId", id).append("name", id).append("role", "USER")
                .append("authProvider", "LOCAL").append("passwordHash", passwords.encode(CURRENT)).append("testUser", true)
                .append("phone", "01000010001").append("email", "test0001@example.com")
                .append("emailNormalized", "test0001@example.com").append("memberIdentityClaimed", true)
                .append("phoneVerified", false).append("emailVerified", false).append("tokenVersion", 0), "users");
        String token = (String) auth.login(Map.of("loginId", id, "password", CURRENT)).get("token");
        assertEquals(id, authenticate(token).getId());
        Document before = mongo.findById(id, Document.class, "users");
        String change = TestContactProofs.phoneToken(mongo, "PASSWORD_CHANGE", id);
        assertEquals(403, assertThrows(ApiException.class, () -> auth.changePassword(Map.of(
                "currentPassword", CURRENT, "newPassword", NEXT, "phoneVerificationToken", change))).getStatus());
        String reset = TestContactProofs.phoneToken(mongo, "PASSWORD_RESET", id);
        assertEquals(403, assertThrows(ApiException.class, () -> auth.resetPassword(Map.of(
                "loginId", id, "newPassword", NEXT, "phoneVerificationToken", reset))).getStatus());
        String email = TestContactProofs.seed(mongo, "EMAIL_CHANGE", "EMAIL", "new@example.com", id, 0);
        assertEquals(403, assertThrows(ApiException.class, () -> auth.changeEmail(Map.of(
                "email", "new@example.com", "emailVerificationToken", email))).getStatus());
        String withdraw = TestContactProofs.phoneToken(mongo, "WITHDRAWAL", id);
        assertEquals(403, assertThrows(ApiException.class, () -> members.withdrawVerified(id, CURRENT, withdraw)).getStatus());
        for (String proof : List.of(change, reset, email, withdraw)) assertNotNull(receipt(proof));
        assertEquals(before, mongo.findById(id, Document.class, "users"));
        assertEquals(id, auth.authenticate(token).getId());
    }

    private Map<String, Object> registration(String loginId, String phone) {
        return new HashMap<>(Map.of("loginId", loginId, "name", "인증 시험", "password", CURRENT,
                "email", loginId.toLowerCase() + "@example.com", "phone", phone));
    }
    private String register(String loginId, String phone) {
        return (String) auth.register(TestContactProofs.signup(mongo, registration(loginId, phone))).get("token");
    }
    private UserPrincipal authenticate(String token) {
        UserPrincipal principal = auth.authenticate(token);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        return principal;
    }
    private Document receipt(String token) { return mongo.findById(SocialLoginTickets.hash(token), Document.class, ContactVerificationService.PROOFS); }
}
