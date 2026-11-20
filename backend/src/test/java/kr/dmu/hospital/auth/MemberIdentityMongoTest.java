package kr.dmu.hospital.auth;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import kr.dmu.hospital.common.ApiException;
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
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class MemberIdentityMongoTest {
    private MongoClient client;
    private MongoTemplate mongo;
    private AuthService auth;
    private SocialMemberService social;
    private String database;
    @BeforeEach void setUp() {
        String uri = System.getenv("MONGODB_TEST_URI");
        assertTrue(uri.startsWith("mongodb://"));
        for (String host : new ConnectionString(uri).getHosts()) assertTrue(host.matches("(localhost|127\\.0\\.0\\.1|\\[::1\\])(:[0-9]+)?"));
        client = MongoClients.create(uri);
        database = "hospital_identity_test_" + UUID.randomUUID().toString().replace("-", "");
        mongo = new MongoTemplate(client, database);
        indexes();
        auth = new AuthService(mongo, new BCryptPasswordEncoder(), "identity-test-signing-secret-at-least-32-characters");
        social = new SocialMemberService(mongo);
    }
    @AfterEach void tearDown() {
        SecurityContextHolder.clearContext();
        if (mongo != null && database.startsWith("hospital_identity_test_")) mongo.getDb().drop();
        if (client != null) client.close();
    }
    @ParameterizedTest @ValueSource(strings = {"LOCAL", "GOOGLE", "KAKAO"})
    void completedMemberBlocksNewLocalAndSocialProfileWithProviderSpecificMessage(String existingProvider) {
        if (existingProvider.equals("LOCAL")) auth.register(registration("original1", "Kim Do Hyeong", "01012345678"));
        else { Document original = pending(existingProvider, "original"); setCurrent(original); auth.completeSocialProfile(profile("Kim Do Hyeong", "01012345678")); }
        MemberIdentityConflict local = assertThrows(MemberIdentityConflict.class,
            () -> auth.register(registration("newuser1", "  KIM   DO HYEONG  ", "010-1234-5678")));
        assertEquals(409, local.getStatus()); assertEquals(existingProvider, local.toPublic().get("existingAuthProvider"));
        assertEquals("MEMBER_ALREADY_EXISTS", local.toPublic().get("code")); assertFalse(local.getMessage().contains("같은 이름"));
        Document newcomer = pending(existingProvider.equals("KAKAO") ? "GOOGLE" : "KAKAO", "newcomer"); setCurrent(newcomer);
        MemberIdentityConflict other = assertThrows(MemberIdentityConflict.class,
            () -> auth.completeSocialProfile(profile("kim do hyeong", "010 1234 5678")));
        assertEquals(existingProvider, other.toPublic().get("existingAuthProvider"));
        Document unchanged = mongo.findById(newcomer.getString("_id"), Document.class, "social_signups");
        assertNotNull(unchanged);
        for (String field : List.of("name", "email", "phone", "memberIdentityClaimed", "nameNormalized", "profileComplete")) assertFalse(unchanged.containsKey(field));
        assertEquals(newcomer.getInteger("tokenVersion"), unchanged.getInteger("tokenVersion"));
        assertNull(mongo.findById(newcomer.getString("_id"), Document.class, "users"));
        assertEquals(1, mongo.getCollection("users").countDocuments());
    }
    @Test void differentNameOrDifferentPhoneIsAllowedAndLoginIdConflictRemainsDistinct() {
        auth.register(registration("original1", "같은 이름", "01012345678"));
        auth.register(registration("othername", "다른 이름", "01012345678"));
        auth.register(registration("otherphone", "같은 이름", "01087654321"));
        ApiException id = assertThrows(ApiException.class, () -> auth.register(registration("original1", "또 다른 이름", "021234567")));
        assertFalse(id instanceof MemberIdentityConflict); assertEquals("이미 사용 중인 아이디입니다.", id.getMessage());
        assertEquals(3, mongo.getCollection("users").countDocuments());
    }
    @Test void nfcNormalizationAndWhitespacePolicyAreConsistent() {
        auth.register(registration("original1", "김도형", "01012345678"));
        assertThrows(MemberIdentityConflict.class, () -> auth.register(registration("newuser1", Normalizer.normalize("김도형", Normalizer.Form.NFD), "010-1234-5678")));
        assertEquals("kim do hyeong", MemberIdentity.normalizedName("  KIM\t DO  HYEONG  "));
        assertNotEquals(MemberIdentity.normalizedName("Kim Do"), MemberIdentity.normalizedName("KimDo"));
    }
    @Test void completedSocialReloginKeepsItsOwnIdentityWithoutConflict() {
        Document first = pending("KAKAO", "same-account"); setCurrent(first);
        Map<String, Object> completedSession = auth.completeSocialProfile(profile("직접 이름", "01012345678"));
        String memberId = auth.authenticate((String) completedSession.get("token")).getId();
        Document again = pending("KAKAO", "same-account");
        assertNotEquals(first.getString("_id"), memberId); assertEquals(memberId, again.getString("_id"));
        assertEquals("직접 이름", again.getString("name"));
        assertEquals(true, again.getBoolean("profileComplete")); assertEquals(1, mongo.getCollection("users").countDocuments());
        assertEquals(0, mongo.getCollection("social_signups").countDocuments());
        setCurrent(again);
        ApiException completed = assertThrows(ApiException.class, () -> auth.completeSocialProfile(profile("직접 이름", "01012345678")));
        assertFalse(completed instanceof MemberIdentityConflict); assertEquals(409, completed.getStatus());
    }
    @Test void concurrentLocalSignupHasOneWinnerAndDatabaseIndexEnforcesIdentity() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> one = () -> { start.await(); try { auth.register(registration("raceuser1", "동시 이름", "01012345678")); return 200; } catch (MemberIdentityConflict conflict) { return 409; } };
            Callable<Integer> two = () -> { start.await(); try { auth.register(registration("raceuser2", "동시 이름", "010-1234-5678")); return 200; } catch (MemberIdentityConflict conflict) { return 409; } };
            var first = executor.submit(one); var second = executor.submit(two); start.countDown();
            assertEquals(List.of(200, 409), List.of(first.get(), second.get()).stream().sorted().toList());
        }
        Document stored = mongo.findOne(new org.springframework.data.mongodb.core.query.Query(), Document.class, "users");
        Document bypass = new Document(stored); bypass.put("_id", "bypass-id"); bypass.put("loginId", "bypass-id");
        assertThrows(DuplicateKeyException.class, () -> mongo.insert(bypass, "users"));
        assertEquals(1, mongo.getCollection("users").countDocuments());
    }
    @Test void concurrentLocalAndSocialCompletionCannotBothClaimSameIdentity() throws Exception {
        Document newcomer = pending("GOOGLE", "concurrent"); CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var local = executor.submit(() -> { start.await(); try { auth.register(registration("localrace", "동시 이름", "01012345678")); return 200; } catch (MemberIdentityConflict conflict) { return 409; } });
            var provider = executor.submit(() -> { start.await(); setCurrent(newcomer); try { auth.completeSocialProfile(profile("동시 이름", "010-1234-5678")); return 200; }
                catch (MemberIdentityConflict conflict) { return 409; } finally { SecurityContextHolder.clearContext(); } });
            start.countDown(); assertEquals(List.of(200, 409), List.of(local.get(), provider.get()).stream().sorted().toList());
        }
        assertEquals(1, mongo.getCollection("users").countDocuments(new Document("memberIdentityClaimed", true)));
        assertEquals(1, mongo.getCollection("users").countDocuments());
    }
    @Test void concurrentGoogleAndKakaoCompletionLeavesTheLoserPending() throws Exception {
        Document google = pending("GOOGLE", "race-google"); Document kakao = pending("KAKAO", "race-kakao");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> first = () -> { start.await(); setCurrent(google); try { auth.completeSocialProfile(profile("동시 이름", "01012345678")); return 200; }
                catch (MemberIdentityConflict conflict) { assertEquals("KAKAO", conflict.toPublic().get("existingAuthProvider")); return 409; }
                finally { SecurityContextHolder.clearContext(); } };
            Callable<Integer> second = () -> { start.await(); setCurrent(kakao); try { auth.completeSocialProfile(profile("동시 이름", "010-1234-5678")); return 200; }
                catch (MemberIdentityConflict conflict) { assertEquals("GOOGLE", conflict.toPublic().get("existingAuthProvider")); return 409; }
                finally { SecurityContextHolder.clearContext(); } };
            var one = executor.submit(first); var two = executor.submit(second); start.countDown();
            assertEquals(List.of(200, 409), List.of(one.get(), two.get()).stream().sorted().toList());
        }
        assertEquals(1, mongo.getCollection("users").countDocuments(new Document("memberIdentityClaimed", true)));
        assertEquals(1, mongo.getCollection("users").countDocuments());
        assertEquals(1, mongo.getCollection("social_signups").countDocuments());
        Document loser = mongo.getCollection("social_signups").find().first();
        assertNotNull(loser);
        for (String field : List.of("name", "email", "phone", "memberIdentityClaimed", "nameNormalized", "profileComplete")) assertFalse(loser.containsKey(field));
        assertNull(mongo.findById(loser.getString("_id"), Document.class, "users"));
    }
    @Test void indexBackfillsCompletedMembersButExcludesPendingAndPhoneLessAdmin() {
        mongo.insert(new Document("_id", "admin").append("name", "관리자").append("loginId", "admin").append("role", "ADMIN"), "users");
        mongo.insert(new Document("_id", "legacy-local").append("name", "  KIM   DO  ").append("loginId", "oldlocal").append("phone", "010-1234-5678").append("role", "USER"), "users");
        Document unfinished = pending("KAKAO", "pending-only");
        indexes(); indexes();
        Document local = mongo.findById("legacy-local", Document.class, "users");
        assertEquals("kim do", local.getString("nameNormalized")); assertEquals("01012345678", local.getString("phone"));
        assertEquals(true, local.getBoolean("memberIdentityClaimed"));
        assertFalse(mongo.findById("admin", Document.class, "users").containsKey("memberIdentityClaimed"));
        assertNull(mongo.findById(unfinished.getString("_id"), Document.class, "users"));
        assertFalse(mongo.findById(unfinished.getString("_id"), Document.class, "social_signups").containsKey("memberIdentityClaimed"));
        assertThrows(MemberIdentityConflict.class, () -> auth.register(registration("newlocal", "Kim Do", "01012345678")));
    }
    @Test void conflictingLegacyBackfillStopsBeforeChangingOrMergingMembers() {
        mongo.insert(new Document("_id", "legacy-one").append("name", "Kim Do").append("loginId", "legacy1").append("phone", "010-1234-5678").append("role", "USER"), "users");
        mongo.insert(new Document("_id", "legacy-two").append("name", "KIM  DO").append("loginId", "legacy2").append("phone", "01012345678").append("authProvider", "GOOGLE").append("profileComplete", true).append("role", "USER"), "users");
        IllegalStateException failure = assertThrows(IllegalStateException.class, this::indexes);
        assertFalse(failure.getMessage().contains("010")); assertFalse(failure.getMessage().contains("Kim"));
        assertEquals(2, mongo.getCollection("users").countDocuments());
        assertFalse(mongo.findById("legacy-one", Document.class, "users").containsKey("memberIdentityClaimed"));
        assertFalse(mongo.findById("legacy-two", Document.class, "users").containsKey("memberIdentityClaimed"));
        assertEquals("010-1234-5678", mongo.findById("legacy-one", Document.class, "users").getString("phone"));
    }
    private void indexes() { new UserIndexes(mongo).run(new DefaultApplicationArguments(new String[0])); }
    private void setCurrent(Document user) {
        UserPrincipal principal = Boolean.TRUE.equals(user.getBoolean("registrationPending")) ? UserPrincipal.forSignup(user) : new UserPrincipal(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
    private Document pending(String provider, String subject) { return provider.equals("KAKAO") ? social.signIn("kakao", Map.of("id", Integer.toUnsignedLong(subject.hashCode()) + 1)) : social.signIn("google", Map.of("sub", subject)); }
    private Map<String, Object> profile(String name, String phone) { return Map.of("name", name, "email", "manual@example.com", "phone", phone); }
    private Map<String, Object> registration(String loginId, String name, String phone) { return Map.of("name", name, "loginId", loginId, "password", "TestPassword2026!", "phone", phone); }
}
