package kr.dmu.hospital.member;

import kr.dmu.hospital.auth.TestContactProofs;
import com.mongodb.ConnectionString;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import kr.dmu.hospital.auth.AuthService;
import kr.dmu.hospital.auth.ComwelClient;
import kr.dmu.hospital.auth.HospitalAccountService;
import kr.dmu.hospital.auth.UserIndexes;
import kr.dmu.hospital.auth.UserPrincipal;
import kr.dmu.hospital.booking.BookingIndexes;
import kr.dmu.hospital.booking.BookingService;
import kr.dmu.hospital.common.ApiErrorHandler;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Each test uses its own database on a loopback replica set; Atlas is never accepted. */
@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class MemberMongoIntegrationTest {
    private MongoClient client;
    private MongoTemplate mongo;
    private SimpleMongoClientDatabaseFactory factory;
    private MongoTransactionManager transactionManager;
    private BookingService booking;
    private MemberService members;
    private AuthService auth;
    private MockMvc mvc;
    private String database;
    private String userA;
    private String userB;
    private String tokenA;
    private String tokenB;
    private static final String ADMIN = "test-admin";
    private static final String PASSWORD = "MemberTest2026!";

    @BeforeEach
    void setUp() {
        String uri = System.getenv("MONGODB_TEST_URI");
        assertTrue(uri.startsWith("mongodb://"), "Member tests require local MongoDB.");
        for (String host : new ConnectionString(uri).getHosts()) {
            assertTrue(host.matches("(localhost|127\\.0\\.0\\.1|\\[::1\\])(:[0-9]+)?"),
                    "Member tests must use a loopback MongoDB host.");
        }
        client = MongoClients.create(uri);
        database = "hospital_member_test_" + UUID.randomUUID().toString().replace("-", "");
        factory = new SimpleMongoClientDatabaseFactory(client, database);
        mongo = new MongoTemplate(factory);
        transactionManager = new MongoTransactionManager(factory);
        DefaultApplicationArguments args = new DefaultApplicationArguments(new String[0]);
        new UserIndexes(mongo).run(args);
        new BookingIndexes(mongo).run(args);
        auth = new AuthService(mongo, new BCryptPasswordEncoder(), "local-member-test-signing-key-at-least-32-characters");
        tokenA = (String) auth.register(TestContactProofs.signup(mongo, Map.of("loginId", "patient-a", "name", "회원 가", "password", PASSWORD, "phone", "010-1234-5678", "email", "patient-a@example.com"))).get("token");
        tokenB = (String) auth.register(TestContactProofs.signup(mongo, Map.of("loginId", "patient-b", "name", "회원 나", "password", PASSWORD, "phone", "010-8765-4321", "email", "patient-b@example.com"))).get("token");
        userA = auth.authenticate(tokenA).getId();
        userB = auth.authenticate(tokenB).getId();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(userA)),
                new Update().set("createdAt", Date.from(Instant.parse("2026-01-02T00:00:00Z"))), "users");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(userB)),
                new Update().set("createdAt", Date.from(Instant.parse("2026-01-03T00:00:00Z"))), "users");
        mongo.insert(new Document("_id", ADMIN).append("loginId", "admin").append("name", "시험 관리자")
                .append("role", "ADMIN").append("passwordHash", "must-never-be-returned")
                .append("tokenVersion", 7).append("bookingVersion", 9)
                .append("createdAt", Date.from(Instant.parse("2026-01-01T00:00:00Z"))), "users");
        mongo.insert(new Document("_id", "hospital").append("name", "시험 병원").append("address", "시험 주소")
                .append("tel", "000-0000-0000").append("active", true).append("provider", "HIRA"), "hospitals");
        mongo.insert(new Document("_id", "hospital").append("status", "ACTIVE").append("userId", "hospital-owner"), "hospitalRegistrations");
        mongo.insert(new Document("_id", "doctor").append("hospitalId", "hospital").append("departmentCode", "01")
                .append("departmentName", "내과").append("name", "시험 의료진").append("isVirtual", false).append("active", true), "doctors");
        for (int i = 1; i <= 3; i++) {
            mongo.insert(new Document("_id", "slot-" + i).append("doctorId", "doctor")
                    .append("startAt", Date.from(Instant.now().plusSeconds(86400L + i * 3600L)))
                    .append("capacity", 4).append("reservedCount", 0).append("closed", false).append("isVirtual", false), "timeSlots");
        }
        booking = new BookingService(mongo, transactionManager);
        members = new MemberService(mongo, booking, transactionManager, new BCryptPasswordEncoder());
        mvc = MockMvcBuilders.standaloneSetup(new MemberController(members), new MyMemberController(members))
                .setControllerAdvice(new ApiErrorHandler()).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (mongo != null && database != null && database.startsWith("hospital_member_test_")) {
            mongo.getDb().drop();
        }
        if (client != null) client.close();
    }

    @Test
    void bothEndpointsDenyAnonymousAndNormalMembers() throws Exception {
        mvc.perform(get("/api/admin/members")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/admin/members/" + userA)).andExpect(status().isUnauthorized());
        signIn(userB);
        mvc.perform(get("/api/admin/members")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/members/" + userA)).andExpect(status().isForbidden());
        assertNotNull(mongo.findById(userA, Document.class, "users"));
    }

    @Test
    void withdrawalRequiresLoginAndCurrentPasswordWithoutChangingTheAccount() throws Exception {
        mvc.perform(delete("/api/members/me")).andExpect(status().isUnauthorized());
        String reservation = booking.create(userA, "wrong-password-reservation", "slot-1").getString("id");
        signIn(userA);
        mvc.perform(delete("/api/members/me")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/members/me").contentType(MediaType.APPLICATION_JSON).content("{\"password\":123}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/members/me").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"wrong-password\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("현재 비밀번호가 맞지 않습니다."));
        assertEquals(403, assertThrows(ApiException.class, () -> members.withdraw(userA, "x".repeat(73))).getStatus());
        assertEquals(userA, auth.authenticate(tokenA).getId());
        assertEquals(3, mongo.getCollection("users").countDocuments());
        assertEquals("RESERVED", mongo.findById(reservation, Document.class, "reservations").getString("status"));
        assertEquals(1, count("slot-1"));
    }

    @Test
    void withdrawalUsesOnlyTheCurrentUserAndRestoresSlotsBeforeRevokingAllSessions() throws Exception {
        String secondToken = (String) auth.login(Map.of("loginId", "patient-a", "password", PASSWORD)).get("token");
        String reservation = booking.create(userA, "withdraw-reservation", "slot-1").getString("id");
        String otherReservation = booking.create(userB, "other-reservation", "slot-1").getString("id");
        Document otherBefore = mongo.findById(otherReservation, Document.class, "reservations");
        mongo.insert(new Document("_id", "own-login-ticket").append("userId", userA), "social_login_tickets");
        mongo.insert(new Document("_id", "other-login-ticket").append("userId", userB), "social_login_tickets");
        mongo.insert(new Document("_id", "own-withdrawal-proof").append("userId", userA), "social_withdrawal_proofs");
        mongo.insert(new Document("_id", "other-withdrawal-proof").append("userId", userB), "social_withdrawal_proofs");
        signIn(userA);
        mvc.perform(delete("/api/members/me").param("userId", userB).contentType(MediaType.APPLICATION_JSON)
                        .content(new Document("password", PASSWORD).append("userId", userB)
                                .append("phoneVerificationToken", TestContactProofs.phoneToken(mongo, "WITHDRAWAL", userA)).toJson()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("회원 탈퇴가 완료되었습니다."))
                .andExpect(jsonPath("$.deletedUserId").value(userA)).andExpect(jsonPath("$.canceledReservations").value(1));
        assertNull(mongo.findById(userA, Document.class, "users"));
        assertEquals("CANCELED", mongo.findById(reservation, Document.class, "reservations").getString("status"));
        assertEquals(1, count("slot-1"));
        assertEquals(otherBefore, mongo.findById(otherReservation, Document.class, "reservations"));
        assertEquals(0, mongo.getCollection("requests").countDocuments(new Document("userId", userA)));
        assertEquals(1, mongo.getCollection("requests").countDocuments(new Document("userId", userB)));
        assertNull(mongo.findById("own-login-ticket", Document.class, "social_login_tickets"));
        assertNotNull(mongo.findById("other-login-ticket", Document.class, "social_login_tickets"));
        assertNull(mongo.findById("own-withdrawal-proof", Document.class, "social_withdrawal_proofs"));
        assertNotNull(mongo.findById("other-withdrawal-proof", Document.class, "social_withdrawal_proofs"));
        assertEquals(1, mongo.getCollection("reservationEvents").countDocuments(new Document("actorId", userA).append("action", "CANCEL")));
        assertThrows(JwtException.class, () -> auth.authenticate(tokenA));
        assertThrows(JwtException.class, () -> auth.authenticate(secondToken));
        assertEquals(userB, auth.authenticate(tokenB).getId());
        assertEquals(401, assertThrows(ApiException.class, () -> auth.login(Map.of("loginId", "patient-a", "password", PASSWORD))).getStatus());
        mvc.perform(delete("/api/members/me").contentType(MediaType.APPLICATION_JSON)
                        .content(new Document("password", PASSWORD).toJson())).andExpect(status().isUnauthorized());
        assertEquals(1, count("slot-1"));
        assertEquals(401, assertThrows(ApiException.class, () -> booking.create(userA, "late-booking", "slot-2")).getStatus());
        assertEquals(List.of(userB, ADMIN), members.list(ADMIN).stream().map(user -> user.getString("id")).toList());
        Map<String, Object> registered = auth.register(TestContactProofs.signup(mongo, Map.of("loginId", "patient-a", "name", "회원 가", "password", PASSWORD,
                "phone", "010-1234-5678", "email", "PATIENT-A@example.com")));
        String newId = auth.authenticate((String) registered.get("token")).getId();
        assertNotEquals(userA, newId);
        assertTrue(booking.reservations(newId).isEmpty());
        assertThrows(JwtException.class, () -> auth.authenticate(tokenA));
    }

    @Test
    void withdrawalProtectsAdminPendingSignupAndStoredRoleChanges() throws Exception {
        signIn(ADMIN);
        mvc.perform(delete("/api/members/me").contentType(MediaType.APPLICATION_JSON)
                        .content(new Document("password", PASSWORD).toJson()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("관리자 계정은 탈퇴할 수 없습니다."));
        signIn(userA);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(userA)), new Update().set("role", "ADMIN"), "users");
        mvc.perform(delete("/api/members/me").contentType(MediaType.APPLICATION_JSON)
                        .content(new Document("password", PASSWORD).toJson())).andExpect(status().isForbidden());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UserPrincipal.forSignup(new Document("_id", "pending-only").append("authProvider", "GOOGLE")), null, List.of()));
        mvc.perform(delete("/api/members/me").contentType(MediaType.APPLICATION_JSON)
                        .content(new Document("password", PASSWORD).toJson())).andExpect(status().isForbidden());
        assertEquals(3, mongo.getCollection("users").countDocuments());
    }

    @Test
    void legacyLocalMemberWithoutProviderCanWithdrawWithTheExactPassword() {
        String password = " password with spaces ";
        mongo.updateFirst(Query.query(Criteria.where("_id").is(userA)), new Update().unset("authProvider")
                .set("passwordHash", new BCryptPasswordEncoder().encode(password)), "users");
        assertEquals(403, assertThrows(ApiException.class, () -> members.withdraw(userA, password.trim())).getStatus());
        assertEquals(userA, members.withdrawVerified(userA, password,
                TestContactProofs.phoneToken(mongo, "WITHDRAWAL", userA)).getString("deletedUserId"));
        assertNull(mongo.findById(userA, Document.class, "users"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GOOGLE", "KAKAO"})
    void socialWithdrawalRequiresTheVerifiedIdentityAndReleasesTheAccountContacts(String provider) throws Exception {
        String subject = "1234567890";
        String identity = provider + ":" + subject;
        mongo.updateFirst(Query.query(Criteria.where("_id").is(userA)), new Update().set("authProvider", provider)
                .set("socialSubject", subject).set("socialIdentity", identity).set("profileComplete", true).unset("passwordHash"), "users");
        signIn(userA);
        mvc.perform(delete("/api/members/me").contentType(MediaType.APPLICATION_JSON)
                        .content(new Document("password", PASSWORD).toJson())).andExpect(status().isBadRequest());
        assertEquals(403, assertThrows(ApiException.class, () -> members.withdrawSocial(userA, provider + ":9999999999", 0)).getStatus());
        assertEquals(403, assertThrows(ApiException.class, () -> members.withdrawSocial(userA, null, 0)).getStatus());
        assertEquals(403, assertThrows(ApiException.class, () -> members.withdrawSocial(userB, identity, 0)).getStatus());
        assertEquals(403, assertThrows(ApiException.class, () -> members.withdrawSocial(ADMIN, identity, 0)).getStatus());
        assertEquals(403, assertThrows(ApiException.class, () -> members.withdrawSocial(userA, identity, 1)).getStatus());
        assertEquals(userA, auth.authenticate(tokenA).getId());
        assertEquals(400, assertThrows(ApiException.class, () -> members.withdrawSocial(userA, identity, 0)).getStatus());
        Document result = members.withdrawSocial(userA, identity, 0, TestContactProofs.phoneToken(mongo, "WITHDRAWAL", userA));
        assertEquals(userA, result.getString("deletedUserId"));
        assertEquals(0, result.getInteger("canceledReservations"));
        assertNull(mongo.findById(userA, Document.class, "users"));
        assertThrows(JwtException.class, () -> auth.authenticate(tokenA));
        Map<String, Object> registered = auth.register(TestContactProofs.signup(mongo, Map.of("loginId", "returned-member", "name", "다시 가입한 회원", "password", PASSWORD,
                "phone", "010-1234-5678", "email", "patient-a@example.com")));
        assertNotEquals(userA, auth.authenticate((String) registered.get("token")).getId());
    }

    @Test
    void endpointsRecheckStoredRoleEvenWithStaleAdminPrincipal() throws Exception {
        signIn(ADMIN);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(ADMIN)), new Update().set("role", "USER"), "users");
        mvc.perform(get("/api/admin/members")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/members/" + userA)).andExpect(status().isForbidden());
        assertNotNull(mongo.findById(userA, Document.class, "users"));
    }

    @Test
    void listReturnsOnlyPublicFieldsInNewestFirstOrder() throws Exception {
        List<Document> result = members.list(ADMIN);
        assertEquals(List.of(userB, userA, ADMIN), result.stream().map(user -> user.getString("id")).toList());
        for (Document user : result) {
            Set<String> expected = ADMIN.equals(user.getString("id"))
                    ? Set.of("id", "name", "loginId", "role", "authProvider", "profileComplete", "createdAt")
                    : Set.of("id", "name", "loginId", "role", "authProvider", "profileComplete", "phone", "email", "createdAt");
            assertEquals(expected, user.keySet());
            assertEquals("LOCAL", user.getString("authProvider"));
            assertTrue(user.getBoolean("profileComplete"));
        }
        assertEquals("2026-01-03T00:00:00Z", result.getFirst().getString("createdAt"));
        signIn(ADMIN);
        mvc.perform(get("/api/admin/members"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].loginId").value("patient-b"))
                .andExpect(jsonPath("$.items[0].phone").value("01087654321"))
                .andExpect(jsonPath("$.items[0].email").value("patient-b@example.com"))
                .andExpect(jsonPath("$.items[2].phone").doesNotExist())
                .andExpect(jsonPath("$.items[*].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.items[*].tokenVersion").doesNotExist())
                .andExpect(jsonPath("$.items[*].bookingVersion").doesNotExist());
    }

    @Test
    void personalSearchMatchesPartialNamesAndFormattedPhonesOnlyWithinPersonalMembers() throws Exception {
        String hospitalUser = hospitalAccount();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(hospitalUser)),
                new Update().set("name", "회원 가 병원").set("phone", "01012345678"), "users");
        signIn(ADMIN);
        mvc.perform(get("/api/admin/members").param("type", "personal").param("q", "회원 가"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(userA))
                .andExpect(jsonPath("$.personalCount").value(3)).andExpect(jsonPath("$.hospitalCount").value(1));
        for (String phone : List.of("010-1234-5678", "01012345678", "1234", "(010) 1234-5678")) {
            mvc.perform(get("/api/admin/members").param("type", "personal").param("q", phone))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(userA));
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(userB)), new Update().set("phone", "010-8765-4321"), "users");
        mvc.perform(get("/api/admin/members").param("type", "personal").param("q", "87654321"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(userB));
        mvc.perform(get("/api/admin/members").param("type", "personal").param("q", "관리자"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(ADMIN));
        // Login IDs and email addresses are not the requested personal search fields.
        for (String otherField : List.of("patient-a", "patient-a@example.com")) {
            mvc.perform(get("/api/admin/members").param("type", "personal").param("q", otherField))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        }
    }

    @Test
    void hospitalSearchUsesBusinessNumbersAndNeverFallsBackToAnUnfilteredList() throws Exception {
        String hospitalUser = hospitalAccount();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(userA)),
                new Update().set("businessNumber", "6911702710").set("name", "6911702710"), "users");
        signIn(ADMIN);
        for (String number : List.of("691-17-02710", "6911702710", "17-027")) {
            mvc.perform(get("/api/admin/members").param("type", "hospital").param("q", number))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(hospitalUser));
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(hospitalUser)),
                new Update().set("businessNumber", "691-17-02710"), "users");
        mvc.perform(get("/api/admin/members").param("type", "hospital").param("q", "6911702710"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        for (String invalid : List.of("시험 병원", "---", ".*", "691abc", "9999999999")) {
            mvc.perform(get("/api/admin/members").param("type", "hospital").param("q", invalid))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0))
                    .andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.hospitalCount").value(1));
        }
        mvc.perform(get("/api/admin/members").param("q", "6911702710"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void personalSearchTreatsRegexCharactersAsLiteralTextAndIgnoresCase() throws Exception {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(userA)), new Update().set("name", "Test.*[회원]"), "users");
        signIn(ADMIN);
        for (String text : List.of(".*", "[회원]", "  test.*  ")) {
            mvc.perform(get("/api/admin/members").param("type", "personal").param("q", text))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(userA));
        }
        mvc.perform(get("/api/admin/members").param("type", "personal").param("q", "^"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void pagedSearchIsBoundedStableAndClampsPagesAfterDeletion() throws Exception {
        Date created = Date.from(Instant.parse("2026-02-01T00:00:00Z"));
        for (int i = 1; i <= 120; i++) {
            String id = "bulk-" + String.format("%04d", i);
            mongo.insert(new Document("_id", id).append("loginId", id).append("name", "대량 회원 " + i)
                    .append("role", "USER").append("authProvider", "LOCAL").append("createdAt", created), "users");
        }
        Document first = members.search(ADMIN, "personal", "대량 회원", 1, 50);
        Document second = members.search(ADMIN, "personal", "대량 회원", 2, 50);
        Document last = members.search(ADMIN, "personal", "대량 회원", Integer.MAX_VALUE, 50);
        assertEquals(120L, first.getLong("total"));
        assertEquals(123L, first.getLong("personalCount"));
        assertEquals(50, first.getList("items", Document.class).size());
        assertEquals(50, second.getList("items", Document.class).size());
        assertEquals(20, last.getList("items", Document.class).size());
        assertEquals(3, last.getInteger("page"));
        assertEquals("bulk-0120", first.getList("items", Document.class).getFirst().getString("id"));
        Set<String> ids = new java.util.HashSet<>();
        for (Document page : List.of(first, second, last)) for (Document member : page.getList("items", Document.class)) {
            assertTrue(ids.add(member.getString("id")), "Page results must not repeat members.");
        }
        assertEquals(120, ids.size());
        mongo.remove(Query.query(Criteria.where("_id").regex("^bulk-00[012][0-9]$")), "users");
        Document afterDeletion = members.search(ADMIN, "personal", "대량 회원", 3, 50);
        assertEquals(2, afterDeletion.getInteger("page"));
        assertEquals(91L, afterDeletion.getLong("total"));
        signIn(ADMIN);
        mvc.perform(get("/api/admin/members"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(50))
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.pageSize").value(50));
        mvc.perform(get("/api/admin/members").param("q", "없음").param("page", "30"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void searchCountsCompletedMembersAndExposesOnlyTheTemporaryUserLabel() throws Exception {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(userA)), new Update().set("testUser", true)
                .set("seedBatchId", "private-seed-batch").set("phoneVerified", false), "users");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(userB)),
                new Update().set("authProvider", "GOOGLE").set("profileComplete", false), "users");
        hospitalAccount();
        signIn(ADMIN);
        mvc.perform(get("/api/admin/members").param("type", "personal").param("q", "회원"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.personalCount").value(2)).andExpect(jsonPath("$.hospitalCount").value(1))
                .andExpect(jsonPath("$.items[0].testUser").value(true))
                .andExpect(jsonPath("$.items[*].seedBatchId").doesNotExist())
                .andExpect(jsonPath("$.items[*].phoneVerified").doesNotExist())
                .andExpect(jsonPath("$.items[*].passwordHash").doesNotExist());
    }

    @Test
    void searchRejectsInvalidArgumentsAndKeepsAdminAuthorization() throws Exception {
        mvc.perform(get("/api/admin/members").param("type", "personal").param("q", "회원"))
                .andExpect(status().isUnauthorized());
        signIn(userA);
        mvc.perform(get("/api/admin/members").param("type", "hospital").param("q", "691"))
                .andExpect(status().isForbidden());
        assertEquals(403, assertThrows(ApiException.class, () -> members.search(userA, "all", "", 1, 50)).getStatus());
        signIn(ADMIN);
        for (String[] invalid : List.of(new String[]{"type", "unknown"}, new String[]{"page", "0"},
                new String[]{"page", "-1"}, new String[]{"page", "not-number"}, new String[]{"page", "2147483648"},
                new String[]{"pageSize", "0"}, new String[]{"pageSize", "101"}, new String[]{"q", "a".repeat(101)})) {
            mvc.perform(get("/api/admin/members").param(invalid[0], invalid[1])).andExpect(status().isBadRequest());
        }
    }

    @Test
    void listExposesCompletedSocialProfilesAndExcludesLegacyPendingUsers() throws Exception {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(userA)), new Update()
                .set("authProvider", "GOOGLE").set("email", "patient@example.test").set("emailSource", "USER")
                .set("emailVerified", false).set("profileComplete", true)
                .set("socialSubject", "private-provider-subject").set("socialIdentity", "GOOGLE:private-provider-subject")
                .set("accessToken", "private-access-token").set("refreshToken", "private-refresh-token")
                .set("profileImageUrl", "https://example.test/profile.png"), "users");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(userB)), new Update()
                .set("authProvider", "KAKAO").set("name", "예전 제공자 이름").set("email", "old-provider@example.test"), "users");
        mongo.insert(new Document("_id", "pending-google").append("loginId", "pending-google")
                .append("authProvider", "GOOGLE").append("profileComplete", false).append("role", "USER"), "users");
        mongo.insert(new Document("_id", "invalid-completion").append("loginId", "invalid-completion")
                .append("authProvider", "KAKAO").append("profileComplete", "true").append("role", "USER"), "users");
        // Stored contact email is public profile data for local members too.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(ADMIN)), new Update()
                .set("email", "old-local@example.test"), "users");
        List<Document> result = members.list(ADMIN);
        assertEquals(List.of(userA, ADMIN), result.stream().map(user -> user.getString("id")).toList());
        Document google = result.stream().filter(user -> userA.equals(user.getString("id"))).findFirst().orElseThrow();
        assertEquals("GOOGLE", google.getString("authProvider"));
        assertTrue(google.getBoolean("profileComplete"));
        assertEquals("patient@example.test", google.getString("email"));
        assertEquals("01012345678", google.getString("phone"));
        assertEquals(Set.of("id", "name", "loginId", "role", "authProvider", "profileComplete", "email", "phone", "createdAt"), google.keySet());
        assertEquals("old-local@example.test", result.stream().filter(user -> ADMIN.equals(user.getString("id"))).findFirst().orElseThrow().getString("email"));
        signIn(ADMIN);
        mvc.perform(get("/api/admin/members")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].authProvider").value("GOOGLE"))
                .andExpect(jsonPath("$.items[0].profileComplete").value(true))
                .andExpect(jsonPath("$.items[0].email").value("patient@example.test"))
                .andExpect(jsonPath("$.items[0].phone").value("01012345678"))
                .andExpect(jsonPath("$.items[1].email").value("old-local@example.test"))
                .andExpect(jsonPath("$.items[*].socialSubject").doesNotExist())
                .andExpect(jsonPath("$.items[*].socialIdentity").doesNotExist())
                .andExpect(jsonPath("$.items[*].accessToken").doesNotExist())
                .andExpect(jsonPath("$.items[*].refreshToken").doesNotExist())
                .andExpect(jsonPath("$.items[*].profileImageUrl").doesNotExist())
                .andExpect(jsonPath("$.items[*].emailSource").doesNotExist())
                .andExpect(jsonPath("$.items[*].emailVerified").doesNotExist())
                .andExpect(jsonPath("$.items[*].passwordHash").doesNotExist());
        mongo.updateFirst(Query.query(Criteria.where("_id").is(userB)),
                new Update().set("profileComplete", true), "users");
        assertEquals(List.of(userB, userA, ADMIN), members.list(ADMIN).stream().map(user -> user.getString("id")).toList());
    }

    @Test
    void ownAdminOtherAdminsAndUnknownMemberAreProtected() throws Exception {
        mongo.insert(new Document("_id", "second-admin").append("role", "ADMIN").append("loginId", "admin2"), "users");
        signIn(ADMIN);
        mvc.perform(delete("/api/admin/members/" + ADMIN)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/members/second-admin")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/members/absent-member")).andExpect(status().isNotFound());
        assertEquals(4, mongo.getCollection("users").countDocuments());
    }

    @Test void hospitalAccountsExposeIdentifiersOnlyToTheAdminListAndCannotUseAdminDeletion() throws Exception {
        String hospitalUser = hospitalAccount();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(userA)),
                new Update().set("hospitalId", "not-public").set("businessNumber", "not-public"), "users");
        Document hospital = members.list(ADMIN).stream().filter(member -> hospitalUser.equals(member.getString("id"))).findFirst().orElseThrow();
        assertEquals("hospital", hospital.getString("hospitalId"));
        assertEquals("6911702710", hospital.getString("businessNumber"));
        assertFalse(hospital.containsKey("passwordHash"));
        for (Document member : members.list(ADMIN)) if (!"HOSPITAL".equals(member.getString("role"))) {
            assertFalse(member.containsKey("hospitalId")); assertFalse(member.containsKey("businessNumber"));
        }
        signIn(userA);
        mvc.perform(delete("/api/admin/members/" + hospitalUser)).andExpect(status().isForbidden());
        signIn(hospitalUser);
        mvc.perform(delete("/api/admin/members/" + hospitalUser)).andExpect(status().isForbidden());
        assertNotNull(mongo.findById(hospitalUser, Document.class, "users"));
        assertNotNull(mongo.findById("hospital", Document.class, HospitalAccountService.REGISTRATIONS));
    }

    @Test void hospitalWithdrawalRequiresItsCurrentPasswordWithoutChangingAnyData() throws Exception {
        String hospitalUser = hospitalAccount();
        String hospitalToken = (String) hospitalAccounts().login(Map.of("businessNumber", "6911702710", "password", PASSWORD)).get("token");
        String reservation = booking.create(userA, "hospital-password-check", "slot-1").getString("id");
        Document memberBefore = mongo.findById(hospitalUser, Document.class, "users");
        Document registrationBefore = mongo.findById("hospital", Document.class, HospitalAccountService.REGISTRATIONS);
        signIn(hospitalUser);
        mvc.perform(delete("/api/members/me")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/members/me").contentType(MediaType.APPLICATION_JSON).content("{\"password\":123}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/members/me").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"wrong-password\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("현재 비밀번호가 맞지 않습니다."));
        assertEquals(403, assertThrows(ApiException.class, () -> members.withdraw(hospitalUser, "x".repeat(73))).getStatus());
        assertEquals(memberBefore, mongo.findById(hospitalUser, Document.class, "users"));
        assertEquals(registrationBefore, mongo.findById("hospital", Document.class, HospitalAccountService.REGISTRATIONS));
        assertEquals("RESERVED", mongo.findById(reservation, Document.class, "reservations").getString("status"));
        assertEquals(1, count("slot-1"));
        assertTrue(mongo.findById("doctor", Document.class, "doctors").getBoolean("active"));
        assertFalse(mongo.findById("slot-1", Document.class, "timeSlots").getBoolean("closed"));
        assertEquals(hospitalUser, auth.authenticate(hospitalToken).getId());
    }

    @ParameterizedTest @ValueSource(strings = {"GOOGLE", "KAKAO", ""})
    void hospitalWithdrawalRequiresLocalCredentialsAndCannotUseSocialIdentity(String provider) {
        String hospitalUser = hospitalAccount();
        assertEquals(403, assertThrows(ApiException.class,
                () -> members.withdrawSocial(hospitalUser, "GOOGLE:fake", 0)).getStatus());
        Update update = provider.isEmpty() ? new Update().unset("authProvider") : new Update().set("authProvider", provider);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(hospitalUser)), update, "users");
        assertEquals(403, assertThrows(ApiException.class, () -> members.withdraw(hospitalUser, PASSWORD)).getStatus());
        assertNotNull(mongo.findById(hospitalUser, Document.class, "users"));
        assertNotNull(mongo.findById("hospital", Document.class, HospitalAccountService.REGISTRATIONS));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void hospitalDeletionAndSelfWithdrawalCancelOnlyItsBookingsAndPreserveCatalogAndHistory(boolean withdrawal) throws Exception {
        String hospitalUser = hospitalAccount();
        String hospitalToken = (String) hospitalAccounts().login(Map.of("businessNumber", "6911702710", "password", PASSWORD)).get("token");
        Document catalog = mongo.findById("hospital", Document.class, "hospitals");
        String first = booking.create(userA, "hospital-first", "slot-1").getString("id");
        String second = booking.create(userB, "hospital-second", "slot-2").getString("id");
        String canceled = booking.create(userA, "hospital-canceled", "slot-3").getString("id");
        booking.cancel(userA, canceled, "already-canceled");
        Document canceledBefore = mongo.findById(canceled, Document.class, "reservations");
        mongo.insert(new Document("_id", "other-hospital").append("name", "다른 병원").append("active", true).append("provider", "HIRA"), "hospitals");
        mongo.insert(new Document("_id", "other-hospital").append("status", "ACTIVE").append("userId", "other-owner"), HospitalAccountService.REGISTRATIONS);
        mongo.insert(new Document("_id", "other-doctor").append("hospitalId", "other-hospital").append("departmentCode", "01")
                .append("departmentName", "내과").append("name", "다른 의료진").append("isVirtual", false).append("active", true), "doctors");
        mongo.insert(new Document("_id", "other-slot").append("doctorId", "other-doctor")
                .append("startAt", Date.from(Instant.now().plusSeconds(172800))).append("capacity", 2)
                .append("reservedCount", 0).append("closed", false).append("isVirtual", false), "timeSlots");
        String other = booking.create(userB, "other-hospital", "other-slot").getString("id");
        Document otherBefore = mongo.findById(other, Document.class, "reservations");
        mongo.insert(new Document("_id", "old-business-proof").append("businessNumber", "6911702710"), HospitalAccountService.VERIFICATIONS);
        String actor = withdrawal ? hospitalUser : ADMIN;
        signIn(actor);
        mvc.perform(delete(withdrawal ? "/api/members/me" : "/api/admin/members/" + hospitalUser)
                .param("userId", userB).contentType(MediaType.APPLICATION_JSON)
                .content(new Document("password", PASSWORD).append("userId", userB).toJson())).andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedUserId").value(hospitalUser)).andExpect(jsonPath("$.canceledReservations").value(2));
        assertNull(mongo.findById(hospitalUser, Document.class, "users"));
        assertNull(mongo.findById("hospital", Document.class, HospitalAccountService.REGISTRATIONS));
        assertEquals(catalog, mongo.findById("hospital", Document.class, "hospitals"));
        assertFalse(mongo.findById("doctor", Document.class, "doctors").getBoolean("active"));
        for (int i = 1; i <= 3; i++) {
            assertEquals(0, count("slot-" + i));
            assertTrue(mongo.findById("slot-" + i, Document.class, "timeSlots").getBoolean("closed"));
        }
        for (String id : List.of(first, second)) {
            Document reservation = mongo.findById(id, Document.class, "reservations");
            assertEquals("CANCELED", reservation.getString("status"));
            assertNotNull(reservation.get("snapshot"));
        }
        assertEquals(2, mongo.getCollection("reservationEvents").countDocuments(new Document("actorId", actor).append("action", "CANCEL")));
        assertEquals(canceledBefore, mongo.findById(canceled, Document.class, "reservations"));
        assertEquals(otherBefore, mongo.findById(other, Document.class, "reservations"));
        assertTrue(mongo.findById("other-doctor", Document.class, "doctors").getBoolean("active"));
        assertFalse(mongo.findById("other-slot", Document.class, "timeSlots").getBoolean("closed"));
        assertEquals(1, count("other-slot"));
        assertNull(mongo.findById("old-business-proof", Document.class, HospitalAccountService.VERIFICATIONS));
        assertEquals(userA, auth.authenticate(tokenA).getId());
        assertEquals(userB, auth.authenticate(tokenB).getId());
        assertThrows(JwtException.class, () -> auth.authenticate(hospitalToken));
        assertEquals(401, assertThrows(ApiException.class,
                () -> hospitalAccounts().login(Map.of("businessNumber", "6911702710", "password", PASSWORD))).getStatus());
        assertEquals(409, assertThrows(ApiException.class, () -> booking.create(userA, "after-closure", "slot-1")).getStatus());
        assertEquals(404, assertThrows(ApiException.class, () -> booking.editSlot("slot-1", 4, false)).getStatus());
        assertThrows(IllegalStateException.class, () -> booking.closeHospitalBookings("hospital", ADMIN));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void hospitalDeletionRollsBackEveryChangeWhenSlotAccountingIsInconsistent(boolean withdrawal) {
        String hospitalUser = hospitalAccount();
        String first = booking.create(userA, "hospital-first", "slot-1").getString("id");
        String second = booking.create(userB, "hospital-second", "slot-2").getString("id");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("slot-2")), new Update().set("reservedCount", 0), "timeSlots");
        assertEquals(409, assertThrows(ApiException.class,
                () -> deleteHospitalOrWithdraw(hospitalUser, withdrawal)).getStatus());
        assertNotNull(mongo.findById(hospitalUser, Document.class, "users"));
        assertNotNull(mongo.findById("hospital", Document.class, HospitalAccountService.REGISTRATIONS));
        assertTrue(mongo.findById("doctor", Document.class, "doctors").getBoolean("active"));
        assertFalse(mongo.findById("slot-1", Document.class, "timeSlots").getBoolean("closed"));
        assertEquals(1, count("slot-1"));
        assertEquals("RESERVED", mongo.findById(first, Document.class, "reservations").getString("status"));
        assertEquals("RESERVED", mongo.findById(second, Document.class, "reservations").getString("status"));
        assertEquals(0, mongo.getCollection("reservationEvents").countDocuments(new Document("actorId", withdrawal ? hospitalUser : ADMIN)));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void hospitalRegistrationOwnershipMismatchPreventsDeletingAnyAccountOrSchedule(boolean withdrawal) {
        String hospitalUser = hospitalAccount();
        mongo.updateFirst(Query.query(Criteria.where("_id").is("hospital")), new Update().set("userId", "other-owner"), HospitalAccountService.REGISTRATIONS);
        assertEquals(409, assertThrows(ApiException.class,
                () -> deleteHospitalOrWithdraw(hospitalUser, withdrawal)).getStatus());
        assertNotNull(mongo.findById(hospitalUser, Document.class, "users"));
        assertEquals("other-owner", mongo.findById("hospital", Document.class, HospitalAccountService.REGISTRATIONS).getString("userId"));
        assertTrue(mongo.findById("doctor", Document.class, "doctors").getBoolean("active"));
    }

    @Test
    void deleteCancelsOnlyActiveMemberBookingsRestoresCapacityAndRevokesTokens() throws Exception {
        String first = booking.create(userA, "create-first", "slot-1").getString("id");
        String second = booking.create(userA, "create-second", "slot-2").getString("id");
        String old = booking.create(userA, "create-old", "slot-3").getString("id");
        booking.cancel(userA, old, "cancel-old");
        String other = booking.create(userB, "create-other", "slot-1").getString("id");
        Document otherBefore = mongo.findById(other, Document.class, "reservations");
        Document otherUserBefore = mongo.findById(userB, Document.class, "users");
        signIn(ADMIN);
        mvc.perform(delete("/api/admin/members/" + userA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedUserId").value(userA))
                .andExpect(jsonPath("$.canceledReservations").value(2));
        assertNull(mongo.findById(userA, Document.class, "users"));
        assertThrows(JwtException.class, () -> auth.authenticate(tokenA));
        assertEquals(userB, auth.authenticate(tokenB).getId());
        assertEquals(1, count("slot-1"));
        assertEquals(0, count("slot-2"));
        assertEquals(0, count("slot-3"));
        for (String id : List.of(first, second, old)) {
            assertEquals("CANCELED", mongo.findById(id, Document.class, "reservations").getString("status"));
        }
        assertEquals(otherBefore, mongo.findById(other, Document.class, "reservations"));
        assertEquals(otherUserBefore, mongo.findById(userB, Document.class, "users"));
        assertEquals(0, mongo.getCollection("requests").countDocuments(new Document("userId", userA)));
        assertEquals(1, mongo.getCollection("requests").countDocuments(new Document("userId", userB)));
        assertEquals(2, mongo.getCollection("reservationEvents").countDocuments(
                new Document("actorId", ADMIN).append("action", "CANCEL")));
        assertEquals(3, mongo.getCollection("reservationEvents").countDocuments(new Document("action", "CANCEL")));
        mvc.perform(delete("/api/admin/members/" + userA)).andExpect(status().isNotFound());
        assertEquals(1, count("slot-1"));
        assertEquals(3, mongo.getCollection("reservationEvents").countDocuments(new Document("action", "CANCEL")));
    }

    @Test
    void memberWithoutBookingsDeletesWithZeroCancellations() {
        Document result = members.delete(ADMIN, userA);
        assertEquals(0, result.getInteger("canceledReservations"));
        assertEquals(userA, result.getString("deletedUserId"));
        assertEquals(2, mongo.getCollection("users").countDocuments());
        assertThrows(JwtException.class, () -> auth.authenticate(tokenA));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inconsistentSlotCounterRollsBackUserCancellationRequestsAndAudit(boolean withdrawal) {
        String first = booking.create(userA, "first", "slot-1").getString("id");
        String second = booking.create(userA, "second", "slot-2").getString("id");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("slot-2")), new Update().set("reservedCount", 0), "timeSlots");
        mongo.insert(new Document("_id", "rollback-login-ticket").append("userId", userA), "social_login_tickets");
        mongo.insert(new Document("_id", "rollback-withdrawal-proof").append("userId", userA), "social_withdrawal_proofs");
        ApiException failed = assertThrows(ApiException.class, () -> deleteOrWithdraw(members, withdrawal));
        assertEquals(409, failed.getStatus());
        assertEquals(userA, auth.authenticate(tokenA).getId());
        assertEquals("RESERVED", mongo.findById(first, Document.class, "reservations").getString("status"));
        assertEquals("RESERVED", mongo.findById(second, Document.class, "reservations").getString("status"));
        assertEquals(1, count("slot-1"));
        assertEquals(2, mongo.getCollection("requests").countDocuments(new Document("userId", userA)));
        assertEquals(2, mongo.getCollection("reservationEvents").countDocuments());
        assertNotNull(mongo.findById("rollback-login-ticket", Document.class, "social_login_tickets"));
        assertNotNull(mongo.findById("rollback-withdrawal-proof", Document.class, "social_withdrawal_proofs"));
    }

    @Test
    void cancellationHelperRequiresTransactionAndDeletedMembersCannotBookAgain() {
        assertThrows(IllegalStateException.class, () -> booking.cancelMemberReservations(userA, ADMIN));
        members.delete(ADMIN, userA);
        assertEquals(401, assertThrows(ApiException.class, () -> booking.create(userA, "late", "slot-1")).getStatus());
        assertEquals(0, count("slot-1"));
        assertEquals(0, mongo.getCollection("reservations").countDocuments());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bookingAlreadyInFlightIsCanceledByConcurrentDeletion(boolean withdrawal) throws Exception {
        CountDownLatch bookingOwnsUser = new CountDownLatch(1);
        CountDownLatch releaseBooking = new CountDownLatch(1);
        CountDownLatch deletionReachedUser = new CountDownLatch(1);
        AtomicBoolean firstWrite = new AtomicBoolean(true);
        MongoTemplate bookingMongo = new MongoTemplate(factory) {
            @Override
            public UpdateResult updateFirst(Query query, UpdateDefinition update, String collection) {
                UpdateResult result = super.updateFirst(query, update, collection);
                if ("users".equals(collection) && firstWrite.getAndSet(false)) {
                    bookingOwnsUser.countDown();
                    await(releaseBooking);
                }
                return result;
            }
        };
        MongoTemplate deletionMongo = new MongoTemplate(factory) {
            @Override
            public DeleteResult remove(Query query, String collection) {
                if ("users".equals(collection)) deletionReachedUser.countDown();
                return super.remove(query, collection);
            }
        };
        BookingService concurrentBooking = new BookingService(bookingMongo, transactionManager);
        MemberService concurrentMembers = new MemberService(deletionMongo, booking, transactionManager, new BCryptPasswordEncoder());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Document> created = executor.submit(() -> concurrentBooking.create(userA, "racing-create", "slot-1"));
            assertTrue(bookingOwnsUser.await(10, TimeUnit.SECONDS));
            Future<Document> deleted = executor.submit(() -> deleteOrWithdraw(concurrentMembers, withdrawal));
            assertTrue(deletionReachedUser.await(10, TimeUnit.SECONDS));
            releaseBooking.countDown();
            String reservationId = created.get(20, TimeUnit.SECONDS).getString("id");
            assertEquals(1, deleted.get(20, TimeUnit.SECONDS).getInteger("canceledReservations"));
            assertNull(mongo.findById(userA, Document.class, "users"));
            assertEquals("CANCELED", mongo.findById(reservationId, Document.class, "reservations").getString("status"));
            assertEquals(0, count("slot-1"));
            assertEquals(0, mongo.getCollection("requests").countDocuments(new Document("userId", userA)));
        } finally {
            releaseBooking.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionAlreadyInFlightPreventsConcurrentBookingFromCommitting(boolean withdrawal) throws Exception {
        CountDownLatch deletionOwnsUser = new CountDownLatch(1);
        CountDownLatch releaseDeletion = new CountDownLatch(1);
        CountDownLatch bookingReachedUser = new CountDownLatch(1);
        AtomicBoolean firstRemove = new AtomicBoolean(true);
        MongoTemplate deletionMongo = new MongoTemplate(factory) {
            @Override
            public DeleteResult remove(Query query, String collection) {
                DeleteResult result = super.remove(query, collection);
                if ("users".equals(collection) && firstRemove.getAndSet(false)) {
                    deletionOwnsUser.countDown();
                    await(releaseDeletion);
                }
                return result;
            }
        };
        MongoTemplate bookingMongo = new MongoTemplate(factory) {
            @Override
            public UpdateResult updateFirst(Query query, UpdateDefinition update, String collection) {
                if ("users".equals(collection)) bookingReachedUser.countDown();
                return super.updateFirst(query, update, collection);
            }
        };
        MemberService concurrentMembers = new MemberService(deletionMongo, booking, transactionManager, new BCryptPasswordEncoder());
        BookingService concurrentBooking = new BookingService(bookingMongo, transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Document> deleted = executor.submit(() -> deleteOrWithdraw(concurrentMembers, withdrawal));
            assertTrue(deletionOwnsUser.await(10, TimeUnit.SECONDS));
            Future<Integer> attempted = executor.submit(() -> {
                try {
                    concurrentBooking.create(userA, "racing-create", "slot-1");
                    return 200;
                } catch (ApiException error) {
                    return error.getStatus();
                }
            });
            assertTrue(bookingReachedUser.await(10, TimeUnit.SECONDS));
            releaseDeletion.countDown();
            assertEquals(0, deleted.get(20, TimeUnit.SECONDS).getInteger("canceledReservations"));
            assertEquals(401, attempted.get(20, TimeUnit.SECONDS));
            assertNull(mongo.findById(userA, Document.class, "users"));
            assertEquals(0, count("slot-1"));
            assertEquals(0, mongo.getCollection("reservations").countDocuments());
            assertEquals(0, mongo.getCollection("requests").countDocuments());
        } finally {
            releaseDeletion.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void hospitalDeletionCancelsBookingsThatWereAlreadyInFlight() throws Exception {
        String hospitalUser = hospitalAccount();
        CountDownLatch bookingOwnsDoctor = new CountDownLatch(1);
        CountDownLatch releaseBooking = new CountDownLatch(1);
        CountDownLatch deletionReachedDoctors = new CountDownLatch(1);
        AtomicBoolean firstLock = new AtomicBoolean(true);
        MongoTemplate bookingMongo = new MongoTemplate(factory) {
            @Override public <T> T findAndModify(Query query, UpdateDefinition update, FindAndModifyOptions options, Class<T> type, String collection) {
                T result = super.findAndModify(query, update, options, type, collection);
                if ("doctors".equals(collection) && firstLock.getAndSet(false)) { bookingOwnsDoctor.countDown(); await(releaseBooking); }
                return result;
            }
        };
        MongoTemplate deletionMongo = new MongoTemplate(factory) {
            @Override public UpdateResult updateMulti(Query query, UpdateDefinition update, String collection) {
                if ("doctors".equals(collection)) deletionReachedDoctors.countDown();
                return super.updateMulti(query, update, collection);
            }
        };
        BookingService concurrentBooking = new BookingService(bookingMongo, transactionManager);
        MemberService concurrentMembers = new MemberService(deletionMongo, new BookingService(deletionMongo, transactionManager), transactionManager, new BCryptPasswordEncoder());
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Document> created = executor.submit(() -> concurrentBooking.create(userA, "hospital-race", "slot-1"));
            try {
                assertTrue(bookingOwnsDoctor.await(10, TimeUnit.SECONDS));
                Future<Document> deleted = executor.submit(() -> concurrentMembers.delete(ADMIN, hospitalUser));
                assertTrue(deletionReachedDoctors.await(10, TimeUnit.SECONDS));
                releaseBooking.countDown();
                String reservationId = created.get(20, TimeUnit.SECONDS).getString("id");
                assertEquals(1, deleted.get(20, TimeUnit.SECONDS).getInteger("canceledReservations"));
                assertEquals("CANCELED", mongo.findById(reservationId, Document.class, "reservations").getString("status"));
                assertEquals(0, count("slot-1"));
            } finally { releaseBooking.countDown(); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"BOOK", "CREATE_SLOTS", "EDIT_SLOT"})
    void hospitalClosureAlreadyInFlightBlocksBookingsAndAdministratorScheduleWrites(String operation) throws Exception {
        String hospitalUser = hospitalAccount();
        CountDownLatch closureOwnsDoctors = new CountDownLatch(1);
        CountDownLatch releaseClosure = new CountDownLatch(1);
        CountDownLatch operationConflicted = new CountDownLatch(1);
        CountDownLatch closureCommitted = new CountDownLatch(1);
        AtomicReference<RuntimeException> conflict = new AtomicReference<>();
        java.util.function.Consumer<RuntimeException> waitForClosure = error -> {
            if (conflict.compareAndSet(null, error)) {
                operationConflicted.countDown();
                await(closureCommitted);
            }
        };
        AtomicBoolean firstClosure = new AtomicBoolean(true);
        MongoTemplate deletionMongo = new MongoTemplate(factory) {
            @Override public UpdateResult updateMulti(Query query, UpdateDefinition update, String collection) {
                UpdateResult result = super.updateMulti(query, update, collection);
                if ("doctors".equals(collection) && firstClosure.getAndSet(false)) { closureOwnsDoctors.countDown(); await(releaseClosure); }
                return result;
            }
        };
        MongoTemplate operationMongo = new MongoTemplate(factory) {
            @Override public UpdateResult updateFirst(Query query, UpdateDefinition update, String collection) {
                try {
                    return super.updateFirst(query, update, collection);
                } catch (RuntimeException error) {
                    if (HospitalAccountService.REGISTRATIONS.equals(collection)) waitForClosure.accept(error);
                    throw error;
                }
            }
            @Override public <T> T findAndModify(Query query, UpdateDefinition update, FindAndModifyOptions options, Class<T> type, String collection) {
                try {
                    return super.findAndModify(query, update, options, type, collection);
                } catch (RuntimeException error) {
                    if ("doctors".equals(collection)) waitForClosure.accept(error);
                    throw error;
                }
            }
        };
        MemberService concurrentMembers = new MemberService(deletionMongo, new BookingService(deletionMongo, transactionManager), transactionManager, new BCryptPasswordEncoder());
        BookingService concurrentBooking = new BookingService(operationMongo, transactionManager);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Document> deleted = executor.submit(() -> concurrentMembers.delete(ADMIN, hospitalUser));
            try {
                assertTrue(closureOwnsDoctors.await(10, TimeUnit.SECONDS), "Closure must acquire its doctor write lock first.");
                Future<Integer> attempted = executor.submit(() -> {
                    try {
                        switch (operation) {
                            case "BOOK" -> concurrentBooking.create(userA, "closure-race", "slot-1");
                            case "CREATE_SLOTS" -> concurrentBooking.createSlots("doctor", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(3).toString(), "09:00", "10:00", 30, 4);
                            case "EDIT_SLOT" -> concurrentBooking.editSlot("slot-1", 4, false);
                            default -> throw new AssertionError("Unexpected operation");
                        }
                        return 200;
                    } catch (ApiException error) { return error.getStatus(); }
                });
                // BOOK already owns the slot before attempting the doctor write. Releasing
                // closure before this write conflicts lets closure hit that slot lock and
                // become the retry victim instead. Observe the actual aborted operation
                // transaction, then let closure commit before allowing the operation retry.
                assertTrue(operationConflicted.await(10, TimeUnit.SECONDS), "Concurrent operation must reach a MongoDB write conflict.");
                assertTrue(transactionConflict(conflict.get()), "The blocked operation must fail with a transaction write conflict.");
                releaseClosure.countDown();
                assertEquals(0, deleted.get(20, TimeUnit.SECONDS).getInteger("canceledReservations"));
                closureCommitted.countDown();
                assertTrue(List.of(404, 409).contains(attempted.get(20, TimeUnit.SECONDS)));
                assertEquals(3, mongo.getCollection("timeSlots").countDocuments());
                assertEquals(0, mongo.getCollection("reservations").countDocuments());
                assertEquals(0, count("slot-1"));
                assertTrue(mongo.findById("slot-1", Document.class, "timeSlots").getBoolean("closed"));
            } finally { releaseClosure.countDown(); closureCommitted.countDown(); }
        }
    }

    private boolean transactionConflict(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoException mongoError && (mongoError.getCode() == 112
                    || mongoError.hasErrorLabel("TransientTransactionError"))) return true;
        }
        return false;
    }

    private String hospitalAccount() {
        mongo.insert(new Document("_id", "hospital-owner").append("loginId", "hospital:6911702710")
                .append("name", "시험 병원").append("role", "HOSPITAL").append("authProvider", "LOCAL")
                .append("hospitalId", "hospital").append("businessNumber", "6911702710").append("profileComplete", true)
                .append("passwordHash", new BCryptPasswordEncoder().encode(PASSWORD)).append("createdAt", new Date()), "users");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("hospital")), new Update().set("businessNumber", "6911702710"), HospitalAccountService.REGISTRATIONS);
        return "hospital-owner";
    }

    private HospitalAccountService hospitalAccounts() {
        return new HospitalAccountService(mongo, new BCryptPasswordEncoder(), auth, new ComwelClient(""), transactionManager);
    }

    private Document deleteHospitalOrWithdraw(String userId, boolean withdrawal) {
        return withdrawal ? members.withdraw(userId, PASSWORD) : members.delete(ADMIN, userId);
    }

    private Document deleteOrWithdraw(MemberService service, boolean withdrawal) {
        return withdrawal ? service.withdrawVerified(userA, PASSWORD, TestContactProofs.phoneToken(mongo, "WITHDRAWAL", userA)) : service.delete(ADMIN, userA);
    }

    private void signIn(String userId) {
        Document user = mongo.findById(userId, Document.class, "users");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new UserPrincipal(user), null, List.of()));
    }

    private int count(String slot) {
        return mongo.findById(slot, Document.class, "timeSlots").getInteger("reservedCount");
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent operation did not reach its checkpoint.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
