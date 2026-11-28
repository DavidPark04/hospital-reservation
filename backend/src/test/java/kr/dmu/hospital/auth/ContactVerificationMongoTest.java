package kr.dmu.hospital.auth;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class ContactVerificationMongoTest {
    private MongoClient client;
    private MongoTemplate mongo;
    private String database;
    private ContactVerificationService verification;
    private VerificationMailSender mail;
    private FirebasePhoneVerifier phones;
    private final AtomicReference<String> emailCode = new AtomicReference<>();
    private final AtomicReference<Instant> time = new AtomicReference<>();

    @BeforeEach void setup() {
        String uri = System.getenv("MONGODB_TEST_URI");
        assertTrue(uri.startsWith("mongodb://"));
        for (String host : new ConnectionString(uri).getHosts()) assertTrue(host.matches("(localhost|127\\.0\\.0\\.1|\\[::1\\])(:[0-9]+)?"));
        client = MongoClients.create(uri);
        database = "contact_verification_test_" + UUID.randomUUID().toString().replace("-", "");
        mongo = new MongoTemplate(client, database);
        new UserIndexes(mongo).run(new DefaultApplicationArguments(new String[0]));
        time.set(Instant.now());
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(ignored -> time.get());
        mail = mock(VerificationMailSender.class);
        when(mail.enabled()).thenReturn(true);
        doAnswer(call -> { emailCode.set(call.getArgument(1)); return null; }).when(mail).sendCode(anyString(), anyString());
        phones = mock(FirebasePhoneVerifier.class);
        when(phones.enabled()).thenReturn(true);
        when(phones.verify(anyString())).thenAnswer(ignored -> new FirebasePhoneVerifier.PhoneIdentity("firebase-user", "01012345678", time.get()));
        verification = new ContactVerificationService(mongo, mail, phones, "public-api-key", "test.firebaseapp.com", "test", clock);
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        if (mongo != null && database != null && database.startsWith("contact_verification_test_")) mongo.getDb().drop();
        if (client != null) client.close();
    }

    @Test void emailProofIsContactAndActionBoundExpiresAndIsSingleUse() {
        var sent = verification.sendEmail(Map.of("purpose", "SIGNUP", "email", "Person@Example.com"), "127.0.0.1");
        String challengeId = (String) sent.get("challengeId");
        Document stored = mongo.findById(SocialLoginTickets.hash(challengeId), Document.class, ContactVerificationService.CHALLENGES);
        assertNotNull(stored);
        assertNotEquals(emailCode.get(), stored.getString("codeHash"));
        assertFalse(stored.containsKey("code"));
        var confirmed = verification.confirmEmail(Map.of("challengeId", challengeId, "code", emailCode.get()));
        assertEquals("person@example.com", confirmed.get("contact"));
        String proof = (String) confirmed.get("verificationToken");
        assertEquals(400, assertThrows(ApiException.class, () -> verification.consume(proof, "EMAIL_CHANGE", "EMAIL", "person@example.com", "", 0)).getStatus());
        assertEquals(400, assertThrows(ApiException.class, () -> verification.consume(proof, "SIGNUP", "EMAIL", "other@example.com", "", 0)).getStatus());
        verification.consume(proof, "SIGNUP", "EMAIL", "person@example.com", "", 0);
        assertThrows(ApiException.class, () -> verification.consume(proof, "SIGNUP", "EMAIL", "person@example.com", "", 0));
        assertThrows(ApiException.class, () -> verification.confirmEmail(Map.of("challengeId", challengeId, "code", emailCode.get())));
    }

    @Test void incorrectCodesAreLimitedAndSuccessfulRequestsCannotRaceToTwoProofs() throws Exception {
        var sent = verification.sendEmail(Map.of("purpose", "SIGNUP", "email", "person@example.com"), "127.0.0.1");
        String challengeId = (String) sent.get("challengeId");
        String wrong = emailCode.get().equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 5; i++) assertThrows(ApiException.class, () -> verification.confirmEmail(Map.of("challengeId", challengeId, "code", wrong)));
        assertThrows(ApiException.class, () -> verification.confirmEmail(Map.of("challengeId", challengeId, "code", emailCode.get())));
        time.set(time.get().plusSeconds(61));
        String fresh = (String) verification.sendEmail(Map.of("purpose", "SIGNUP", "email", "person@example.com"), "127.0.0.1").get("challengeId");
        var ready = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var calls = List.of(pool.submit(() -> confirmAtOnce(ready, fresh)), pool.submit(() -> confirmAtOnce(ready, fresh)));
            var results = List.of(calls.get(0).get(10, TimeUnit.SECONDS), calls.get(1).get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        }
        assertEquals(1, mongo.getCollection(ContactVerificationService.PROOFS).countDocuments());
    }

    private boolean confirmAtOnce(CyclicBarrier ready, String challenge) throws Exception {
        ready.await(5, TimeUnit.SECONDS);
        try { verification.confirmEmail(Map.of("challengeId", challenge, "code", emailCode.get())); return true; }
        catch (ApiException expected) { assertEquals(400, expected.getStatus()); return false; }
    }

    @Test void emailResendCooldownAndProviderFailureCannotLeaveUsableChallenges() {
        var first = verification.sendEmail(Map.of("purpose", "SIGNUP", "email", "person@example.com"), "127.0.0.1");
        String code = emailCode.get();
        assertEquals(429, assertThrows(ApiException.class, () -> verification.sendEmail(Map.of("purpose", "SIGNUP", "email", "person@example.com"), "127.0.0.1")).getStatus());
        time.set(time.get().plusSeconds(61));
        doThrow(new IllegalStateException("simulated mail failure")).when(mail).sendCode(anyString(), anyString());
        assertEquals(503, assertThrows(ApiException.class, () -> verification.sendEmail(Map.of("purpose", "SIGNUP", "email", "person@example.com"), "127.0.0.1")).getStatus());
        assertEquals(0, mongo.getCollection(ContactVerificationService.CHALLENGES).countDocuments());
        assertThrows(ApiException.class, () -> verification.confirmEmail(Map.of("challengeId", first.get("challengeId"), "code", code)));
    }

    @Test void emailChangeChallengeRequiresTheSameLiveAccountAndVersion() {
        Document user = user("person", "01012345678");
        login(user);
        var sent = verification.sendEmail(Map.of("purpose", "EMAIL_CHANGE", "email", "new@example.com"), "127.0.0.1");
        login(user("other", "01098765432"));
        assertThrows(ApiException.class, () -> verification.confirmEmail(Map.of("challengeId", sent.get("challengeId"), "code", emailCode.get())));
        login(user);
        var confirmed = verification.confirmEmail(Map.of("challengeId", sent.get("challengeId"), "code", emailCode.get()));
        String proof = (String) confirmed.get("verificationToken");
        assertThrows(ApiException.class, () -> verification.consume(proof, "EMAIL_CHANGE", "EMAIL", "new@example.com", "person", 1));
        verification.consume(proof, "EMAIL_CHANGE", "EMAIL", "new@example.com", "person", 0);
    }

    @Test void smsUsesSignedContactAndSameAuthenticationCannotIssueAnotherProof() {
        var result = verification.confirmPhone(Map.of("purpose", "SIGNUP", "idToken", "provider-token", "phone", "01098765432"));
        assertEquals("01012345678", result.get("contact"));
        assertEquals(400, assertThrows(ApiException.class, () -> verification.confirmPhone(Map.of("purpose", "SIGNUP", "idToken", "refreshed-provider-token"))).getStatus());
        String proof = (String) result.get("verificationToken");
        verification.consume(proof, "SIGNUP", "PHONE", "01012345678", "", 0);
        assertThrows(ApiException.class, () -> verification.confirmPhone(Map.of("purpose", "SIGNUP", "idToken", "provider-token")));
    }

    @Test void registeredPhoneAndResetLoginIdMustMatchTheVerifiedPhone() {
        Document account = user("person", "01098765432");
        login(account);
        assertThrows(ApiException.class, () -> verification.confirmPhone(Map.of("purpose", "WITHDRAWAL", "idToken", "provider-token")));
        SecurityContextHolder.clearContext();
        assertThrows(ApiException.class, () -> verification.confirmPhone(Map.of("purpose", "PASSWORD_RESET", "idToken", "provider-token", "loginId", "person")));
        var match = user("matching", "01012345678");
        var reset = verification.confirmPhone(Map.of("purpose", "PASSWORD_RESET", "idToken", "provider-token", "loginId", "MATCHING"));
        String token = (String) reset.get("verificationToken");
        assertThrows(ApiException.class, () -> verification.consume(token, "PASSWORD_RESET", "PHONE", "01012345678", "person", 0));
        verification.consume(token, "PASSWORD_RESET", "PHONE", "01012345678", match.getString("_id"), 0);
    }

    @Test void expiredProofAndChallengeAreRejectedBeforeTtlCleanup() {
        var sent = verification.sendEmail(Map.of("purpose", "SIGNUP", "email", "person@example.com"), "127.0.0.1");
        var sms = verification.confirmPhone(Map.of("purpose", "SIGNUP", "idToken", "provider-token"));
        time.set(time.get().plusSeconds(601));
        assertThrows(ApiException.class, () -> verification.confirmEmail(Map.of("challengeId", sent.get("challengeId"), "code", emailCode.get())));
        assertThrows(ApiException.class, () -> verification.consume((String) sms.get("verificationToken"), "SIGNUP", "PHONE", "01012345678", "", 0));
    }

    @Test void consumingInsideFailedAccountTransactionKeepsProofForRetry() {
        var sms = verification.confirmPhone(Map.of("purpose", "SIGNUP", "idToken", "provider-token"));
        String proof = (String) sms.get("verificationToken");
        var transaction = new TransactionTemplate(new MongoTransactionManager(mongo.getMongoDatabaseFactory()));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            verification.consume(proof, "SIGNUP", "PHONE", "01012345678", "", 0);
            throw new IllegalStateException("registration failed");
        }));
        verification.consume(proof, "SIGNUP", "PHONE", "01012345678", "", 0);
    }

    @Test void personalProofRequestsCannotBeIssuedForHospitalOrSocialPasswordReset() {
        var hospital = user("hospital", "01012345678").append("role", "HOSPITAL");
        mongo.save(hospital, "users");
        login(hospital);
        assertThrows(ApiException.class, () -> verification.sendEmail(Map.of("purpose", "EMAIL_CHANGE", "email", "new@example.com"), "127.0.0.1"));
        assertThrows(ApiException.class, () -> verification.confirmPhone(Map.of("purpose", "PASSWORD_CHANGE", "idToken", "provider-token")));
        SecurityContextHolder.clearContext();
        assertThrows(ApiException.class, () -> verification.confirmPhone(Map.of("purpose", "PASSWORD_RESET", "idToken", "provider-token", "loginId", "hospital")));
    }

    private Document user(String id, String phone) {
        return mongo.insert(new Document("_id", id).append("loginId", id).append("role", "USER").append("authProvider", "LOCAL")
                .append("phone", phone).append("email", id + "@example.com").append("tokenVersion", 0), "users");
    }

    private void login(Document user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new UserPrincipal(user), null, List.of()));
    }
}
