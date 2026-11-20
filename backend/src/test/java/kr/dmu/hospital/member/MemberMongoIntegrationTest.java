package kr.dmu.hospital.member;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import kr.dmu.hospital.auth.AuthService;
import kr.dmu.hospital.auth.UserIndexes;
import kr.dmu.hospital.auth.UserPrincipal;
import kr.dmu.hospital.booking.BookingIndexes;
import kr.dmu.hospital.booking.BookingService;
import kr.dmu.hospital.common.ApiErrorHandler;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
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
        tokenA = (String) auth.register(Map.of("loginId", "patient-a", "name", "회원 가", "password", PASSWORD, "phone", "010-1234-5678", "email", "patient-a@example.com")).get("token");
        tokenB = (String) auth.register(Map.of("loginId", "patient-b", "name", "회원 나", "password", PASSWORD, "phone", "010-8765-4321", "email", "patient-b@example.com")).get("token");
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
                .append("tel", "000-0000-0000").append("source", "SAMPLE"), "hospitals");
        mongo.insert(new Document("_id", "doctor").append("hospitalId", "hospital").append("departmentCode", "01")
                .append("departmentName", "내과").append("name", "시험 의료진").append("isVirtual", true), "doctors");
        for (int i = 1; i <= 3; i++) {
            mongo.insert(new Document("_id", "slot-" + i).append("doctorId", "doctor")
                    .append("startAt", Date.from(Instant.now().plusSeconds(86400L + i * 3600L)))
                    .append("capacity", 4).append("reservedCount", 0).append("closed", false), "timeSlots");
        }
        booking = new BookingService(mongo, transactionManager);
        members = new MemberService(mongo, booking, transactionManager);
        mvc = MockMvcBuilders.standaloneSetup(new MemberController(members))
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

    @Test
    void inconsistentSlotCounterRollsBackUserCancellationRequestsAndAudit() {
        String first = booking.create(userA, "first", "slot-1").getString("id");
        String second = booking.create(userA, "second", "slot-2").getString("id");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("slot-2")), new Update().set("reservedCount", 0), "timeSlots");
        ApiException failed = assertThrows(ApiException.class, () -> members.delete(ADMIN, userA));
        assertEquals(409, failed.getStatus());
        assertEquals(userA, auth.authenticate(tokenA).getId());
        assertEquals("RESERVED", mongo.findById(first, Document.class, "reservations").getString("status"));
        assertEquals("RESERVED", mongo.findById(second, Document.class, "reservations").getString("status"));
        assertEquals(1, count("slot-1"));
        assertEquals(2, mongo.getCollection("requests").countDocuments(new Document("userId", userA)));
        assertEquals(2, mongo.getCollection("reservationEvents").countDocuments());
    }

    @Test
    void cancellationHelperRequiresTransactionAndDeletedMembersCannotBookAgain() {
        assertThrows(IllegalStateException.class, () -> booking.cancelMemberReservations(userA, ADMIN));
        members.delete(ADMIN, userA);
        assertEquals(401, assertThrows(ApiException.class, () -> booking.create(userA, "late", "slot-1")).getStatus());
        assertEquals(0, count("slot-1"));
        assertEquals(0, mongo.getCollection("reservations").countDocuments());
    }

    @Test
    void bookingAlreadyInFlightIsCanceledByConcurrentDeletion() throws Exception {
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
        MemberService concurrentMembers = new MemberService(deletionMongo, booking, transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Document> created = executor.submit(() -> concurrentBooking.create(userA, "racing-create", "slot-1"));
            assertTrue(bookingOwnsUser.await(10, TimeUnit.SECONDS));
            Future<Document> deleted = executor.submit(() -> concurrentMembers.delete(ADMIN, userA));
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

    @Test
    void deletionAlreadyInFlightPreventsConcurrentBookingFromCommitting() throws Exception {
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
        MemberService concurrentMembers = new MemberService(deletionMongo, booking, transactionManager);
        BookingService concurrentBooking = new BookingService(bookingMongo, transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Document> deleted = executor.submit(() -> concurrentMembers.delete(ADMIN, userA));
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
