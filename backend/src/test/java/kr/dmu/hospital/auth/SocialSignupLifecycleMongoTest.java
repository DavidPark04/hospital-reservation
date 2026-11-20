package kr.dmu.hospital.auth;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.result.DeleteResult;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.junit.jupiter.api.Assertions.*;

/** Temporary signup authorizations are tested only in a disposable loopback database. */
@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class SocialSignupLifecycleMongoTest {
    private static final String SECRET = "signup-lifecycle-test-signing-secret-at-least-32-characters";
    private MongoClient client;
    private SimpleMongoClientDatabaseFactory factory;
    private MongoTemplate mongo;
    private AuthService auth;
    private SocialMemberService social;
    private String database;

    @BeforeEach void setUp() {
        String uri = System.getenv("MONGODB_TEST_URI");
        assertTrue(uri.startsWith("mongodb://"));
        for (String host : new ConnectionString(uri).getHosts()) {
            assertTrue(host.matches("(localhost|127\\.0\\.0\\.1|\\[::1\\])(:[0-9]+)?"));
        }
        client = MongoClients.create(uri);
        database = "hospital_signup_lifecycle_test_" + UUID.randomUUID().toString().replace("-", "");
        factory = new SimpleMongoClientDatabaseFactory(client, database);
        mongo = new MongoTemplate(factory);
        new UserIndexes(mongo).run(new DefaultApplicationArguments(new String[0]));
        auth = new AuthService(mongo, new BCryptPasswordEncoder(), SECRET);
        social = new SocialMemberService(mongo);
    }

    @AfterEach void tearDown() {
        SecurityContextHolder.clearContext();
        if (mongo != null && database != null && database.startsWith("hospital_signup_lifecycle_test_")) mongo.getDb().drop();
        if (client != null) client.close();
    }

    @Test void oauthCreatesOnlyTemporaryAuthorizationAndReauthenticationRevokesItsPriorToken() {
        Document first = signup();
        String firstToken = token(first);
        assertTrue(auth.authenticate(firstToken).isSignupPending());
        assertEquals(0, count("users"));
        assertEquals(1, count("social_signups"));
        Document stored = mongo.findById(first.getString("_id"), Document.class, "social_signups");
        assertNotNull(stored);
        for (String field : List.of("registrationPending", "name", "email", "phone", "loginId", "passwordHash")) {
            assertFalse(stored.containsKey(field), field + " must not be persisted for temporary signup");
        }
        Document again = signup();
        assertEquals(first.getString("_id"), again.getString("_id"));
        assertTrue(again.getInteger("tokenVersion") > first.getInteger("tokenVersion"));
        assertThrows(JwtException.class, () -> auth.authenticate(firstToken));
        assertTrue(auth.authenticate(token(again)).isSignupPending());
        assertEquals(0, count("users"));
        assertEquals(1, count("social_signups"));
    }

    @Test void expirationRejectsAuthenticationAndExchangeWithoutWaitingForTtlDeletion() {
        Document pending = signup();
        String pendingToken = token(pending);
        expire(pending);
        assertThrows(JwtException.class, () -> auth.authenticate(pendingToken));
        assertEquals(401, assertThrows(ApiException.class,
                () -> auth.issueSocialSession(pending.getString("_id"), pending.getInteger("tokenVersion"), true)).getStatus());
        assertEquals(0, count("users"));
    }

    @Test void renewingExpiredAuthorizationDoesNotReviveItsOldJwt() {
        Document pending = signup();
        String oldToken = token(pending);
        expire(pending);
        Document renewed = signup();
        assertThrows(JwtException.class, () -> auth.authenticate(oldToken));
        UserPrincipal current = auth.authenticate(token(renewed));
        assertTrue(current.isSignupPending());
        assertFalse(current.isProfileComplete());
        assertEquals(0, count("users"));
    }

    @Test void logoutCancelsTemporarySignupAndLaterOAuthCannotReviveTheCanceledToken() {
        Document pending = signup();
        String oldToken = token(pending);
        signIn(oldToken);
        auth.logout();
        assertEquals(0, count("social_signups"));
        assertEquals(0, count("users"));
        assertThrows(JwtException.class, () -> auth.authenticate(oldToken));
        Document again = signup();
        assertNotEquals(pending.getString("_id"), again.getString("_id"));
        assertTrue(auth.authenticate(token(again)).isSignupPending());
        assertThrows(JwtException.class, () -> auth.authenticate(oldToken));
    }

    @Test void completionCreatesANewMemberAndCannotPromoteOrReplayTheOldSignupToken() {
        Document pending = signup();
        String pendingToken = token(pending);
        signIn(pendingToken);
        Map<String, Object> completed = auth.completeSocialProfile(profile());
        UserPrincipal member = auth.authenticate((String) completed.get("token"));
        assertFalse(member.isSignupPending());
        assertTrue(member.isProfileComplete());
        assertNotEquals(pending.getString("_id"), member.getId());
        assertEquals("USER", member.getRole());
        assertEquals(1, count("users"));
        assertEquals(0, count("social_signups"));
        Document stored = mongo.findById(member.getId(), Document.class, "users");
        assertEquals("직접 입력 이름", stored.getString("name"));
        assertEquals("manual@example.test", stored.getString("email"));
        assertEquals("01012345678", stored.getString("phone"));
        assertEquals(true, stored.getBoolean("profileComplete"));
        assertThrows(JwtException.class, () -> auth.authenticate(pendingToken));
        assertEquals(401, assertThrows(ApiException.class,
                () -> auth.issueSocialSession(pending.getString("_id"), pending.getInteger("tokenVersion"), true)).getStatus());
        assertThrows(ApiException.class, () -> auth.completeSocialProfile(profile()));
        assertEquals(1, count("users"));
        Document relogin = signup();
        assertEquals(member.getId(), relogin.getString("_id"));
        assertEquals("직접 입력 이름", relogin.getString("name"));
        assertEquals(0, count("social_signups"));
    }

    @Test void failureAfterSignupConsumptionRollsBackBothMemberCreationAndTemporaryDeletion() {
        Document pending = signup();
        String pendingToken = token(pending);
        signIn(pendingToken);
        MongoTemplate failingMongo = new MongoTemplate(factory) {
            @Override public DeleteResult remove(Query query, String collectionName) {
                DeleteResult result = super.remove(query, collectionName);
                if ("social_signups".equals(collectionName)) throw new IllegalStateException("Forced failure after signup consumption");
                return result;
            }
        };
        AuthService failingAuth = new AuthService(failingMongo, new BCryptPasswordEncoder(), SECRET);
        assertThrows(IllegalStateException.class, () -> failingAuth.completeSocialProfile(profile()));
        assertEquals(0, count("users"));
        assertEquals(1, count("social_signups"));
        assertTrue(auth.authenticate(pendingToken).isSignupPending());
        auth.completeSocialProfile(profile());
        assertEquals(1, count("users"));
        assertEquals(0, count("social_signups"));
    }

    @Test void completionBetweenSignInLookupAndTemporaryInsertDoesNotLeaveAnOrphanSignup() {
        Document pending = signup();
        signIn(token(pending));
        AtomicBoolean completeAfterFirstLookup = new AtomicBoolean(true);
        MongoTemplate racingMongo = new MongoTemplate(factory) {
            @Override public <T> T findOne(Query query, Class<T> entityClass, String collectionName) {
                T result = super.findOne(query, entityClass, collectionName);
                if ("users".equals(collectionName) && result == null && completeAfterFirstLookup.getAndSet(false)) {
                    auth.completeSocialProfile(profile());
                }
                return result;
            }
        };
        Document result = new SocialMemberService(racingMongo).signIn("google", Map.of("sub", "stable-provider-subject"));
        assertTrue(result.getBoolean("profileComplete"));
        assertNotEquals(pending.getString("_id"), result.getString("_id"));
        assertEquals("직접 입력 이름", result.getString("name"));
        assertEquals(1, count("users"));
        assertEquals(0, count("social_signups"));
    }

    private Document signup() { return social.signIn("google", Map.of("sub", "stable-provider-subject")); }
    private String token(Document pending) {
        return (String) auth.issueSocialSession(pending.getString("_id"), pending.getInteger("tokenVersion"), true).get("token");
    }
    private void signIn(String token) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(auth.authenticate(token), null, List.of()));
    }
    private void expire(Document pending) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(pending.getString("_id"))),
                new Update().set("expiresAt", Date.from(Instant.now().minusSeconds(5))), "social_signups");
    }
    private long count(String collection) { return mongo.getCollection(collection).countDocuments(); }
    private Map<String, Object> profile() {
        return Map.of("name", "직접 입력 이름", "email", "manual@example.test", "phone", "010-1234-5678");
    }
}
