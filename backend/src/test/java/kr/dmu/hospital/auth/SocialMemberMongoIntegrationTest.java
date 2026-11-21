package kr.dmu.hospital.auth;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class SocialMemberMongoIntegrationTest {
    private MongoClient client;
    private MongoTemplate mongo;
    private SocialMemberService members;
    private AuthService auth;
    private String database;
    @BeforeEach void setUp() {
        String uri = System.getenv("MONGODB_TEST_URI");
        assertTrue(uri.startsWith("mongodb://"));
        for (String host : new ConnectionString(uri).getHosts()) assertTrue(host.matches("(localhost|127\\.0\\.0\\.1|\\[::1\\])(:[0-9]+)?"));
        client = MongoClients.create(uri);
        database = "hospital_social_test_" + UUID.randomUUID().toString().replace("-", "");
        mongo = new MongoTemplate(client, database);
        new UserIndexes(mongo).run(new DefaultApplicationArguments(new String[0]));
        members = new SocialMemberService(mongo);
        auth = new AuthService(mongo, new BCryptPasswordEncoder(), "social-test-jwt-secret-at-least-32-characters");
    }
    @AfterEach void tearDown() {
        SecurityContextHolder.clearContext();
        if (mongo != null && database.startsWith("hospital_social_test_")) mongo.getDb().drop();
        if (client != null) client.close();
    }
    @Test void googleLoginUsesOnlyStableIdAndCreatesOneTemporarySignup() {
        Document first = members.signIn("google", google("subject"));
        Document second = members.signIn("google", google("subject"));
        assertEquals(first.getString("_id"), second.getString("_id"));
        assertEquals("GOOGLE", second.getString("authProvider"));
        for (String field : List.of("name", "email", "phone", "profileImageUrl", "access_token", "passwordHash")) assertFalse(second.containsKey(field));
        assertEquals(0, mongo.getCollection("users").countDocuments());
        assertEquals(1, mongo.getCollection("social_signups").countDocuments());
        Map<String, Object> user = UserPrincipal.forSignup(second).toPublic();
        assertEquals("", user.get("name")); assertEquals("", user.get("loginId"));
        assertEquals(false, user.get("profileComplete")); assertEquals(false, user.get("emailVerified"));
        assertFalse(user.containsKey("socialIdentity")); assertFalse(user.containsKey("socialSubject"));
        assertFalse(user.containsKey("email"));
    }
    @Test void uncompletedSocialSignupDoesNotAppearAsMemberEvenWhenProfileIsDuplicate() {
        auth.register(Map.of("name", "직접 이름", "loginId", "alreadyjoined", "password", "TestPassword2026!", "phone", "01012345678", "email", "alreadyjoined@example.com"));
        Document pending = members.signIn("google", google("not-yet-joined")); setCurrent(pending);
        assertThrows(MemberIdentityConflict.class, () -> auth.completeSocialProfile(profile()));
        assertEquals(1, mongo.getCollection("users").countDocuments(), "OAuth authentication and rejected profile must not create a formal member.");
        assertEquals(1, mongo.getCollection("social_signups").countDocuments());
    }
    @Test void kakaoLoginIgnoresNicknameAndVerifiedProviderEmail() {
        Document user = members.signIn("kakao", Map.of("id", 123456789L, "kakao_account", Map.of("profile", Map.of("nickname", "제공자 닉네임"),
            "is_email_valid", true, "is_email_verified", true, "email", "provider@example.com")));
        assertFalse(user.containsKey("name")); assertEquals("KAKAO:123456789", user.getString("socialIdentity"));
        assertEquals(true, user.getBoolean("registrationPending")); assertFalse(user.containsKey("email"));
    }
    @Test void manualProfileIsSavedWithPhoneAndPreservedOnLaterLogin() {
        Document pending = members.signIn("google", google("subject"));
        String pendingToken = (String) auth.issueSocialSession(pending.getString("_id"), pending.getInteger("tokenVersion"), true).get("token");
        setCurrent(pending);
        Map<String, Object> response = auth.completeSocialProfile(Map.of("name", " 직접 이름 ", "email", " personal@example.com ", "phone", "010-1234-5678"));
        Map<?, ?> user = (Map<?, ?>) response.get("user");
        assertEquals("직접 이름", user.get("name")); assertEquals("personal@example.com", user.get("email"));
        assertEquals("01012345678", user.get("phone")); assertEquals(true, user.get("profileComplete")); assertEquals(false, user.get("emailVerified"));
        assertNotEquals(pending.getString("_id"), user.get("id"));
        assertEquals(0, mongo.getCollection("social_signups").countDocuments());
        assertThrows(JwtException.class, () -> auth.authenticate(pendingToken));
        assertTrue(auth.authenticate((String) response.get("token")).isProfileComplete());
        Document later = members.signIn("google", google("subject"));
        assertEquals("직접 이름", later.getString("name")); assertEquals("personal@example.com", later.getString("email"));
        assertEquals("01012345678", later.getString("phone")); assertEquals("USER", later.getString("emailSource"));
        assertEquals(false, later.getBoolean("emailVerified")); assertEquals(1, mongo.getCollection("users").countDocuments());
        assertNotNull(later.getDate("profileCompletedAt"));
    }
    @Test void emailNeverLinksAccountsAndRequestCannotChooseIdentityOrAdmin() {
        mongo.insert(new Document("_id", "existing-admin").append("loginId", "admin").append("name", "관리자").append("role", "ADMIN").append("email", "personal@example.com"), "users");
        new UserIndexes(mongo).run(new DefaultApplicationArguments(new String[0]));
        Document originalAdmin = mongo.findById("existing-admin", Document.class, "users");
        Document google = members.signIn("google", google("subject")); setCurrent(google);
        ApiException duplicate = assertThrows(ApiException.class, () -> auth.completeSocialProfile(Map.of("name", "회원", "email", " PERSONAL@example.com ", "phone", "021234567", "role", "ADMIN", "id", "existing-admin", "emailVerified", true)));
        assertEquals(ApiException.class, duplicate.getClass()); assertEquals(409, duplicate.getStatus());
        assertEquals("이미 가입된 정보가 있어요. 기존에 가입한 방법으로 로그인해 주세요.", duplicate.getMessage());
        assertEquals(originalAdmin, mongo.findById("existing-admin", Document.class, "users"));
        assertEquals(1, mongo.getCollection("users").countDocuments());
        assertNotNull(mongo.findById(google.getString("_id"), Document.class, "social_signups"));
        auth.completeSocialProfile(Map.of("name", "회원", "email", "different@example.com", "phone", "021234567", "role", "ADMIN", "id", "existing-admin", "emailVerified", true));
        Document kakao = members.signIn("kakao", Map.of("id", 999L));
        assertNotEquals(google.getString("_id"), kakao.getString("_id"));
        Document saved = mongo.getCollection("users").find(new Document("socialIdentity", "GOOGLE:subject")).first();
        assertEquals("USER", saved.getString("role")); assertEquals(false, saved.getBoolean("emailVerified"));
        assertEquals("GOOGLE:subject", saved.getString("socialIdentity")); assertEquals(2, mongo.getCollection("users").countDocuments());
        assertEquals(1, mongo.getCollection("social_signups").countDocuments());
    }
    @Test void missingInvalidProfileDoesNotCompleteOrModifyAccount() {
        Document pending = members.signIn("google", google("subject")); setCurrent(pending);
        List<Map<String, Object>> invalid = List.of(Map.of("name", "회원", "email", "valid@example.com"),
            Map.of("name", "", "email", "valid@example.com", "phone", "01012345678"),
            Map.of("name", "회원", "email", "not-an-email", "phone", "01012345678"),
            Map.of("name", "회원", "email", ".bad@example.com", "phone", "01012345678"),
            Map.of("name", "회원", "email", "valid@example.com", "phone", "ABCD"),
            Map.of("name", "회원", "email", "valid@example.com", "phone", 1012345678));
        for (Map<String, Object> body : invalid) assertEquals(400, assertThrows(ApiException.class, () -> auth.completeSocialProfile(body)).getStatus());
        Document user = mongo.findById(pending.getString("_id"), Document.class, "social_signups");
        assertFalse(user.containsKey("name")); assertFalse(user.containsKey("email")); assertEquals(1, user.getInteger("tokenVersion"));
        assertEquals(0, mongo.getCollection("users").countDocuments());
    }
    @Test void profileCompletionIsSingleUseAndUnavailableForLocalUsers() {
        Document pending = members.signIn("kakao", Map.of("id", 44)); setCurrent(pending);
        auth.completeSocialProfile(profile());
        assertEquals(401, assertThrows(ApiException.class, () -> auth.completeSocialProfile(profile())).getStatus());
        setCurrent(new Document("_id", "local-admin").append("loginId", "admin").append("name", "관리자").append("role", "ADMIN"));
        assertEquals(403, assertThrows(ApiException.class, () -> auth.completeSocialProfile(profile())).getStatus());
        assertTrue(CurrentUser.require().isProfileComplete());
    }
    @Test void concurrentFirstLoginCreatesOneTemporarySignupAndNoMember() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Document> signIn = () -> members.signIn("google", google("concurrent-subject"));
            var results = executor.invokeAll(List.of(signIn, signIn));
            assertEquals(results.get(0).get().getString("_id"), results.get(1).get().getString("_id"));
            assertEquals(0, mongo.getCollection("users").countDocuments());
            assertEquals(1, mongo.getCollection("social_signups").countDocuments());
        }
    }
    @Test void simultaneousProfileCompletionHasExactlyOneWinner() throws Exception {
        Document pending = members.signIn("google", google("subject"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> complete = () -> { setCurrent(pending); try { auth.completeSocialProfile(profile()); return 200; }
                catch (ApiException error) { return error.getStatus(); } finally { SecurityContextHolder.clearContext(); } };
            var results = executor.invokeAll(List.of(complete, complete));
            var statuses = results.stream().map(result -> { try { return result.get(); } catch (Exception error) { throw new RuntimeException(error); } }).sorted().toList();
            assertEquals(200, statuses.get(0)); assertTrue(List.of(401, 409).contains(statuses.get(1)));
        }
        assertEquals(1, mongo.getCollection("users").countDocuments()); assertEquals(0, mongo.getCollection("social_signups").countDocuments());
    }
    @Test void concurrentReloginCannotEraseNewlyCompletedProfile() throws Exception {
        Document pending = members.signIn("google", google("subject"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Object> login = () -> members.signIn("google", google("subject"));
            Callable<Object> complete = () -> { setCurrent(pending); try { return auth.completeSocialProfile(profile()); }
                catch (ApiException replacedAuthentication) { assertEquals(401, replacedAuthentication.getStatus()); return 401; }
                finally { SecurityContextHolder.clearContext(); } };
            for (var result : executor.invokeAll(List.of(login, complete))) result.get();
        }
        if (mongo.getCollection("users").countDocuments() == 0) {
            Document latest = mongo.getCollection("social_signups").find().first();
            setCurrent(latest.append("registrationPending", true)); auth.completeSocialProfile(profile());
        }
        Document saved = mongo.getCollection("users").find(new Document("socialIdentity", "GOOGLE:subject")).first();
        assertEquals(true, saved.getBoolean("profileComplete")); assertEquals("직접 이름", saved.getString("name"));
        assertEquals("manual@example.com", saved.getString("email")); assertEquals("01012345678", saved.getString("phone"));
    }
    @Test void legacyPendingMemberMovesToMinimalTemporarySignupWithoutChangingCompletedMembers() {
        Map<String, Object> completed = auth.register(Map.of("name", "기존 회원", "loginId", "existing1", "password", "TestPassword2026!", "phone", "01087654321", "email", "existing@example.com"));
        String completedId = ((Map<?, ?>) completed.get("user")).get("id").toString();
        Document before = mongo.findById(completedId, Document.class, "users");
        mongo.insert(new Document("_id", "legacy").append("loginId", "legacy_google").append("role", "USER")
            .append("authProvider", "GOOGLE").append("socialIdentity", "GOOGLE:legacy-subject").append("socialSubject", "legacy-subject")
            .append("name", "Old provider name").append("email", "provider@example.com").append("profileImageUrl", "https://example.com/photo"), "users");
        new UserIndexes(mongo).run(new DefaultApplicationArguments(new String[0]));
        new UserIndexes(mongo).run(new DefaultApplicationArguments(new String[0]));
        assertNull(mongo.findById("legacy", Document.class, "users"));
        assertEquals(before, mongo.findById(completedId, Document.class, "users"));
        Document user = mongo.getCollection("social_signups").find(new Document("socialIdentity", "GOOGLE:legacy-subject")).first();
        assertNotNull(user); assertNotEquals("legacy", user.getString("_id"));
        for (String field : List.of("name", "email", "phone", "passwordHash", "profileImageUrl", "legacyUser")) assertFalse(user.containsKey(field));
        assertTrue(user.getDate("expiresAt").after(new java.util.Date()));
        assertEquals(1, mongo.getCollection("users").countDocuments());
    }
    @ParameterizedTest @ValueSource(strings = {"reservations", "requests", "reservationEvents"})
    void legacyPendingWithReferencesIsPreservedAndMigrationStops(String collection) {
        Document original = new Document("_id", "legacy").append("loginId", "legacy_google").append("role", "USER")
            .append("authProvider", "GOOGLE").append("socialIdentity", "GOOGLE:legacy-subject").append("socialSubject", "legacy-subject").append("profileComplete", false);
        mongo.insert(original, "users");
        mongo.insert(new Document("_id", "reference").append(collection.equals("reservationEvents") ? "actorId" : "userId", "legacy"), collection);
        assertThrows(IllegalStateException.class, () -> new UserIndexes(mongo).run(new DefaultApplicationArguments(new String[0])));
        assertEquals(original, mongo.findById("legacy", Document.class, "users"));
        assertEquals(0, mongo.getCollection("social_signups").countDocuments());
    }
    @Test void socialAccountCannotUsePasswordLoginAndDeletedMemberCannotExchange() {
        Document signup = members.signIn("google", google("subject")); setCurrent(signup); auth.completeSocialProfile(profile());
        Document user = members.signIn("google", google("subject"));
        assertEquals(401, assertThrows(ApiException.class, () -> auth.login(Map.of("loginId", user.getString("loginId"), "password", "any-password"))).getStatus());
        mongo.getCollection("users").deleteOne(new Document("_id", user.getString("_id")));
        assertEquals(401, assertThrows(ApiException.class, () -> auth.issueSocialSession(user.getString("_id"), 0)).getStatus());
    }
    @Test void invalidProviderOrMissingStableIdentityCannotCreateMember() {
        assertThrows(ApiException.class, () -> members.signIn("other", Map.of("sub", "subject")));
        assertThrows(ApiException.class, () -> members.signIn("google", Map.of("email", "member@example.com")));
        assertThrows(ApiException.class, () -> members.signIn("kakao", Map.of("id", "non-numeric")));
        assertEquals(0, mongo.getCollection("users").countDocuments());
    }
    private void setCurrent(Document user) {
        UserPrincipal principal = Boolean.TRUE.equals(user.getBoolean("registrationPending")) ? UserPrincipal.forSignup(user) : new UserPrincipal(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
    private Map<String, Object> profile() { return Map.of("name", "직접 이름", "email", "manual@example.com", "phone", "01012345678"); }
    private Map<String, Object> google(String subject) {
        return Map.of("sub", subject, "name", "Provider Name", "email", "provider@example.com", "email_verified", true,
            "picture", "https://example.com/avatar.png", "role", "ADMIN", "access_token", "must-not-be-saved");
    }
}
