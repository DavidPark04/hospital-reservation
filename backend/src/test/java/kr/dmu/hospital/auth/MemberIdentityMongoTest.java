package kr.dmu.hospital.auth;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.IndexOptions;
import java.util.ArrayList;
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

/** Contact uniqueness is tested only in a disposable loopback database. */
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
    void matchingNormalizedNameAndPhoneRevealsProviderForEverySignupMethod(String existingProvider) {
        complete(existingProvider, "original1", "Kim Do Hyeong", "01012345678", "original@example.com");
        for (String provider : List.of("LOCAL", "GOOGLE", "KAKAO")) {
            String subject = "matched-new-" + provider;
            Document pending = provider.equals("LOCAL") ? null : pending(provider, subject);
            if (pending != null) setCurrent(pending);
            MemberIdentityConflict conflict = assertThrows(MemberIdentityConflict.class,
                () -> finish(provider, subject, "  KIM   DO  HYEONG  ", "010-1234-5678", provider.toLowerCase(java.util.Locale.ROOT) + "@different.example"));
            assertConflict(conflict, existingProvider);
            if (pending != null) assertStillPending(pending);
        }
        assertEquals(1, count("users"));
    }

    @ParameterizedTest @ValueSource(strings = {"LOCAL", "GOOGLE", "KAKAO"})
    void phoneAloneBlocksEverySignupProviderWithoutDisclosingProvider(String existingProvider) {
        complete(existingProvider, "original1", "기존 이름", "01012345678", "original@example.com");
        for (String provider : List.of("LOCAL", "GOOGLE", "KAKAO")) {
            String subject = "phone-new-" + provider;
            Document pending = provider.equals("LOCAL") ? null : pending(provider, subject);
            if (pending != null) setCurrent(pending);
            ApiException conflict = assertThrows(ApiException.class,
                () -> finish(provider, subject, "다른 이름", "010-1234-5678", provider.toLowerCase(java.util.Locale.ROOT) + "@different.example"));
            assertGenericConflict(conflict);
            if (pending != null) assertStillPending(pending);
        }
        assertEquals(1, count("users"));
    }

    @ParameterizedTest @ValueSource(strings = {"LOCAL", "GOOGLE", "KAKAO"})
    void emailAloneBlocksEverySignupProviderWithoutDisclosingProviderEvenIfNameMatches(String existingProvider) {
        complete(existingProvider, "original1", "기존 이름", "01012345678", "Shared@Example.com");
        for (String provider : List.of("LOCAL", "GOOGLE", "KAKAO")) {
            String subject = "email-new-" + provider;
            Document pending = provider.equals("LOCAL") ? null : pending(provider, subject);
            if (pending != null) setCurrent(pending);
            ApiException conflict = assertThrows(ApiException.class,
                () -> finish(provider, subject, "기존 이름", "01087654321", " shared@EXAMPLE.COM "));
            assertGenericConflict(conflict);
            if (pending != null) assertStillPending(pending);
        }
        assertEquals(1, count("users"));
    }

    @Test void sameNameWithDifferentContactsIsAllowedAndLoginIdConflictRemainsDistinct() {
        auth.register(registration("original1", "같은 이름", "01012345678", "first@example.com"));
        auth.register(registration("otheruser", "같은 이름", "01087654321", "second@example.com"));
        ApiException id = assertThrows(ApiException.class,
            () -> auth.register(registration("original1", "다른 이름", "021234567", "third@example.com")));
        assertFalse(id instanceof MemberIdentityConflict);
        assertEquals("이미 사용 중인 아이디입니다.", id.getMessage());
        assertEquals(2, count("users"));
    }

    @Test void matchingNameAndPhoneHavePriorityWhenEmailBelongsToAnotherMember() {
        complete("KAKAO", "phone-owner", "전화 회원", "01012345678", "phone@example.com");
        complete("GOOGLE", "email-owner", "메일 회원", "01087654321", "email@example.com");
        MemberIdentityConflict conflict = assertThrows(MemberIdentityConflict.class,
            () -> auth.register(registration("newmember", " 전화 회원 ", "01012345678", "email@example.com")));
        assertConflict(conflict, "KAKAO");
        assertEquals(2, count("users"));
    }

    @Test void completedSocialReloginKeepsItsOwnIdentityWithoutConflict() {
        Document first = pending("KAKAO", "same-account"); setCurrent(first);
        Map<String, Object> session = auth.completeSocialProfile(profile("직접 이름", "01012345678", "manual@example.com"));
        String memberId = auth.authenticate((String) session.get("token")).getId();
        Document again = pending("KAKAO", "same-account");
        assertNotEquals(first.getString("_id"), memberId);
        assertEquals(memberId, again.getString("_id"));
        assertEquals("직접 이름", again.getString("name"));
        assertEquals("manual@example.com", again.getString("emailNormalized"));
        assertEquals(true, again.getBoolean("profileComplete"));
        assertEquals(1, count("users")); assertEquals(0, count("social_signups"));
        setCurrent(again);
        ApiException completed = assertThrows(ApiException.class,
            () -> auth.completeSocialProfile(profile("직접 이름", "01012345678", "manual@example.com")));
        assertFalse(completed instanceof MemberIdentityConflict); assertEquals(409, completed.getStatus());
    }

    @ParameterizedTest @ValueSource(strings = {"phone", "email"})
    void concurrentLocalSignupsHaveOneWinnerAndIndependentDatabaseConstraints(String duplicateContact) throws Exception {
        race("LOCAL", "LOCAL", duplicateContact);
        Document stored = mongo.getCollection("users").find().first();
        assertNotNull(stored);
        Document bypass = new Document(stored).append("_id", "bypass").append("loginId", "bypass")
            .append("name", "완전히 다른 이름").append("nameNormalized", "완전히 다른 이름");
        if (duplicateContact.equals("phone")) bypass.append("email", "bypass@example.com").append("emailNormalized", "bypass@example.com");
        else bypass.append("phone", "021234567");
        assertThrows(DuplicateKeyException.class, () -> mongo.insert(bypass, "users"));
        assertEquals(1, count("users"));
    }

    @ParameterizedTest @ValueSource(strings = {"phone", "email"})
    void concurrentLocalAndSocialCompletionCannotBothClaimEitherContact(String duplicateContact) throws Exception {
        race("LOCAL", "GOOGLE", duplicateContact);
    }

    @ParameterizedTest @ValueSource(strings = {"phone", "email"})
    void concurrentGoogleAndKakaoCompletionLeavesTheLoserPending(String duplicateContact) throws Exception {
        race("GOOGLE", "KAKAO", duplicateContact);
        assertEquals(1, count("social_signups"));
        Document loser = mongo.getCollection("social_signups").find().first();
        assertNotNull(loser); assertStillPending(loser);
    }

    @Test void legacyContactsBackfillIndependentlyWithoutInventingMissingValues() {
        mongo.insert(new Document("_id", "admin").append("name", "관리자").append("loginId", "admin").append("role", "ADMIN"), "users");
        mongo.insert(new Document("_id", "email-admin").append("name", "이메일 관리자").append("loginId", "emailadmin")
            .append("email", " Admin@Example.com ").append("role", "ADMIN"), "users");
        mongo.insert(new Document("_id", "legacy-local").append("name", "  KIM   DO  ").append("loginId", "oldlocal")
            .append("phone", "010-1234-5678").append("role", "USER"), "users");
        Document unfinished = pending("KAKAO", "pending-only");
        indexes();
        Document local = mongo.findById("legacy-local", Document.class, "users");
        Document emailAdmin = mongo.findById("email-admin", Document.class, "users");
        assertEquals("01012345678", local.getString("phone")); assertEquals(true, local.getBoolean("memberIdentityClaimed"));
        assertFalse(local.containsKey("email")); assertFalse(local.containsKey("emailNormalized"));
        assertEquals("admin@example.com", emailAdmin.getString("emailNormalized"));
        assertEquals(true, emailAdmin.getBoolean("memberIdentityClaimed")); assertFalse(emailAdmin.containsKey("phone"));
        assertFalse(mongo.findById("admin", Document.class, "users").containsKey("memberIdentityClaimed"));
        indexes();
        assertEquals(local, mongo.findById("legacy-local", Document.class, "users"));
        assertEquals(emailAdmin, mongo.findById("email-admin", Document.class, "users"));
        assertStillPending(unfinished);
        assertGenericConflict(assertThrows(ApiException.class,
            () -> auth.register(registration("newlocal", "다른 이름", "01012345678", "new@example.com"))));
        assertGenericConflict(assertThrows(ApiException.class,
            () -> auth.register(registration("newadmin", "다른 이름", "01087654321", "ADMIN@example.com"))));
    }

    @ParameterizedTest @ValueSource(strings = {"phone", "email"})
    void conflictingLegacyContactsStopBeforeChangingOrMigratingAnyMember(String duplicateContact) {
        String phoneTwo = duplicateContact.equals("phone") ? "01012345678" : "01087654321";
        String emailTwo = duplicateContact.equals("email") ? " First@EXAMPLE.com " : "second@example.com";
        mongo.insert(new Document("_id", "legacy-one").append("name", "첫 이름").append("loginId", "legacy1")
            .append("phone", "010-1234-5678").append("email", "first@example.com").append("role", "USER"), "users");
        mongo.insert(new Document("_id", "legacy-two").append("name", "다른 이름").append("loginId", "legacy2")
            .append("phone", phoneTwo).append("email", emailTwo).append("authProvider", "GOOGLE").append("profileComplete", true).append("role", "USER"), "users");
        mongo.insert(new Document("_id", "legacy-pending").append("loginId", "legacypending").append("role", "USER")
            .append("authProvider", "KAKAO").append("socialIdentity", "KAKAO:4001").append("socialSubject", "4001"), "users");
        List<Document> before = mongo.getCollection("users").find().sort(new Document("_id", 1)).into(new ArrayList<>());
        IllegalStateException failure = assertThrows(IllegalStateException.class, this::indexes);
        assertFalse(failure.getMessage().contains("010")); assertFalse(failure.getMessage().contains("first@"));
        assertFalse(failure.getMessage().contains("첫 이름"));
        assertEquals(before, mongo.getCollection("users").find().sort(new Document("_id", 1)).into(new ArrayList<>()));
        assertEquals(0, count("social_signups"));
    }

    @Test void blankLegacyPhonesDoNotClaimTheSamePhoneWhenEmailsAreDifferent() {
        for (String suffix : List.of("one", "two")) {
            mongo.insert(new Document("_id", "legacy-" + suffix).append("loginId", "legacy" + suffix)
                .append("name", "기존 회원").append("authProvider", "LOCAL").append("role", "USER")
                .append("phone", " ").append("email", suffix + "@example.com"), "users");
        }
        indexes(); indexes();
        for (String suffix : List.of("one", "two")) {
            Document member = mongo.findById("legacy-" + suffix, Document.class, "users");
            assertNotNull(member); assertFalse(member.containsKey("phone"));
            assertEquals(suffix + "@example.com", member.getString("email"));
            assertEquals(suffix + "@example.com", member.getString("emailNormalized"));
            assertEquals(true, member.getBoolean("memberIdentityClaimed"));
            assertGenericConflict(assertThrows(ApiException.class,
                () -> auth.register(registration("new" + suffix, "다른 이름", "01012345678", suffix + "@example.com"))));
        }
        assertEquals(2, count("users"));
        auth.register(registration("withphone", "전화 회원", "01012345678", "new@example.com"));
        assertEquals(3, count("users"));
    }

    @Test void oldCombinedIndexIsReplacedByIndependentUniqueIndexesIdempotently() {
        mongo.getCollection("users").createIndex(new Document("nameNormalized", 1).append("phone", 1),
            new IndexOptions().unique(true).name("user_name_phone_v1").partialFilterExpression(new Document("memberIdentityClaimed", true)));
        auth.register(registration("memberone", "같은 이름", "01012345678", "one@example.com"));
        indexes(); indexes();
        List<Document> all = mongo.getCollection("users").listIndexes().into(new ArrayList<>());
        assertFalse(all.stream().anyMatch(index -> "user_name_phone_v1".equals(index.getString("name"))));
        for (String field : List.of("phone", "emailNormalized")) {
            Document index = all.stream().filter(item -> new Document(field, 1).equals(item.get("key"))).findFirst().orElseThrow();
            assertEquals(true, index.getBoolean("unique"));
            assertNotNull(index.get("partialFilterExpression"));
        }
        assertEquals(1, count("users"));
    }

    @Test void concurrentStartupPreservesContactsAndMigratesOneTemporarySignup() throws Exception {
        mongo.getCollection("users").createIndex(new Document("nameNormalized", 1).append("phone", 1),
            new IndexOptions().unique(true).name("user_name_phone_v1").partialFilterExpression(new Document("memberIdentityClaimed", true)));
        mongo.insert(new Document("_id", "legacy-member").append("loginId", "legacymember").append("name", "기존 회원")
            .append("phone", "010-1234-5678").append("email", " Legacy@Example.com ").append("role", "USER"), "users");
        mongo.insert(new Document("_id", "legacy-signup").append("loginId", "legacysignup").append("role", "USER")
            .append("authProvider", "GOOGLE").append("socialIdentity", "GOOGLE:startup-subject").append("socialSubject", "startup-subject"), "users");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Void> initialize = () -> { start.await(); indexes(); return null; };
            var one = executor.submit(initialize); var two = executor.submit(initialize); start.countDown();
            one.get(); two.get();
        }
        Document member = mongo.findById("legacy-member", Document.class, "users");
        assertNotNull(member); assertEquals("01012345678", member.getString("phone"));
        assertEquals("legacy@example.com", member.getString("emailNormalized"));
        assertEquals(1, count("users")); assertEquals(1, count("social_signups"));
        assertNull(mongo.findById("legacy-signup", Document.class, "users"));
        List<Document> all = mongo.getCollection("users").listIndexes().into(new ArrayList<>());
        assertFalse(all.stream().anyMatch(index -> "user_name_phone_v1".equals(index.getString("name"))));
        assertTrue(all.stream().anyMatch(index -> "user_phone_v1".equals(index.getString("name"))));
        assertTrue(all.stream().anyMatch(index -> "user_email_v1".equals(index.getString("name"))));
    }

    private void race(String firstProvider, String secondProvider, String duplicateContact) throws Exception {
        Document firstPending = firstProvider.equals("LOCAL") ? null : pending(firstProvider, "race-one");
        Document secondPending = secondProvider.equals("LOCAL") ? null : pending(secondProvider, "race-two");
        String secondPhone = duplicateContact.equals("phone") ? "010-1234-5678" : "01087654321";
        String secondEmail = duplicateContact.equals("email") ? " FIRST@example.com " : "second@example.com";
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> first = () -> attempt(start, firstPending, firstProvider, "raceone", "첫 이름", "01012345678", "first@example.com");
            Callable<Integer> second = () -> attempt(start, secondPending, secondProvider, "racetwo", "다른 이름", secondPhone, secondEmail);
            var one = executor.submit(first); var two = executor.submit(second); start.countDown();
            assertEquals(List.of(200, 409), List.of(one.get(), two.get()).stream().sorted().toList());
        }
        assertEquals(1, count("users"));
    }
    private int attempt(CountDownLatch start, Document pending, String provider, String subject, String name, String phone, String email) throws Exception {
        start.await();
        if (pending != null) setCurrent(pending);
        try { finish(provider, subject, name, phone, email); return 200; }
        catch (ApiException conflict) { assertEquals(409, conflict.getStatus()); return conflict.getStatus(); }
        finally { SecurityContextHolder.clearContext(); }
    }
    private void complete(String provider, String subject, String name, String phone, String email) {
        if (!provider.equals("LOCAL")) setCurrent(pending(provider, subject));
        finish(provider, subject, name, phone, email);
    }
    private void finish(String provider, String subject, String name, String phone, String email) {
        if (provider.equals("LOCAL")) auth.register(registration(subject, name, phone, email));
        else auth.completeSocialProfile(profile(name, phone, email));
    }
    private void assertConflict(MemberIdentityConflict conflict, String provider) {
        assertEquals(409, conflict.getStatus()); assertEquals(provider, conflict.toPublic().get("existingAuthProvider"));
        assertEquals("MEMBER_ALREADY_EXISTS", conflict.toPublic().get("code"));
        String expected = provider.equals("KAKAO") ? "이미 카카오로 가입된 계정이 있어요. 카카오로 로그인해 주세요."
            : provider.equals("GOOGLE") ? "이미 구글로 가입된 계정이 있어요. 구글로 로그인해 주세요."
            : "이미 일반 가입으로 등록된 계정이 있어요. 아이디로 로그인해 주세요.";
        assertEquals(expected, conflict.getMessage());
    }
    private void assertGenericConflict(ApiException conflict) {
        assertEquals(ApiException.class, conflict.getClass());
        assertEquals(409, conflict.getStatus());
        assertEquals("이미 가입된 정보가 있어요. 입력 정보를 확인해 주세요.", conflict.getMessage());
    }
    private void assertStillPending(Document pending) {
        Document unchanged = mongo.findById(pending.getString("_id"), Document.class, "social_signups");
        assertNotNull(unchanged);
        for (String field : List.of("name", "email", "emailNormalized", "phone", "memberIdentityClaimed", "nameNormalized", "profileComplete")) assertFalse(unchanged.containsKey(field));
        assertEquals(pending.getInteger("tokenVersion"), unchanged.getInteger("tokenVersion"));
        assertNull(mongo.findById(pending.getString("_id"), Document.class, "users"));
    }
    private void indexes() { new UserIndexes(mongo).run(new DefaultApplicationArguments(new String[0])); }
    private long count(String collection) { return mongo.getCollection(collection).countDocuments(); }
    private void setCurrent(Document user) {
        UserPrincipal principal = Boolean.TRUE.equals(user.getBoolean("registrationPending")) ? UserPrincipal.forSignup(user) : new UserPrincipal(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
    private Document pending(String provider, String subject) { return provider.equals("KAKAO") ? social.signIn("kakao", Map.of("id", Integer.toUnsignedLong(subject.hashCode()) + 1)) : social.signIn("google", Map.of("sub", subject)); }
    private Map<String, Object> profile(String name, String phone, String email) { return Map.of("name", name, "email", email, "phone", phone); }
    private Map<String, Object> registration(String loginId, String name, String phone, String email) { return Map.of("name", name, "loginId", loginId, "password", "TestPassword2026!", "phone", phone, "email", email); }
}
