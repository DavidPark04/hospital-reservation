package kr.dmu.hospital.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class SocialLoginTicketsTest {
    private MongoClient client;
    private MongoTemplate mongo;
    private String database;
    @BeforeEach void setUp() {
        String uri = System.getenv("MONGODB_TEST_URI");
        assertTrue(uri.startsWith("mongodb://"));
        for (String host : new ConnectionString(uri).getHosts()) assertTrue(host.matches("(localhost|127\\.0\\.0\\.1|\\[::1\\])(:[0-9]+)?"));
        client = MongoClients.create(uri);
        database = "hospital_ticket_test_" + UUID.randomUUID().toString().replace("-", "");
        mongo = new MongoTemplate(client, database);
        new UserIndexes(mongo).run(new DefaultApplicationArguments(new String[0]));
    }
    @AfterEach void tearDown() {
        if (mongo != null && database.startsWith("hospital_ticket_test_")) mongo.getDb().drop();
        if (client != null) client.close();
    }
    @Test void ticketIsRandomSingleUseAndBoundToMemberVersion() {
        SocialLoginTickets tickets = new SocialLoginTickets(mongo);
        Document user = new Document("_id", "member-id").append("tokenVersion", 3);
        String first = tickets.create(user); String second = tickets.create(user);
        assertNotEquals(first, second); assertFalse(first.contains("member-id"));
        Document stored = mongo.findById(SocialLoginTickets.hash(first), Document.class, SocialLoginTickets.COLLECTION);
        assertNotNull(stored); assertFalse(stored.toJson().contains(first));
        assertEquals(java.util.Set.of("_id", "userId", "tokenVersion", "signupPending", "expiresAt"), stored.keySet());
        SocialLoginTickets.Ticket ticket = new SocialLoginTickets(mongo).consume(first);
        assertEquals("member-id", ticket.getUserId()); assertEquals(3, ticket.getTokenVersion());
        assertEquals(401, assertThrows(ApiException.class, () -> tickets.consume(first)).getStatus());
        assertEquals(401, assertThrows(ApiException.class, () -> tickets.consume(null)).getStatus());
        assertEquals(401, assertThrows(ApiException.class, () -> tickets.consume("forged")).getStatus());
    }
    @Test void expiredTicketCannotBeUsed() {
        AtomicReference<Instant> time = new AtomicReference<>(Instant.now().plusSeconds(3600));
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return time.get(); }
        };
        SocialLoginTickets tickets = new SocialLoginTickets(mongo, clock);
        String value = tickets.create(new Document("_id", "member"));
        time.set(time.get().plusSeconds(60));
        assertEquals(401, assertThrows(ApiException.class, () -> tickets.consume(value)).getStatus());
        assertNotNull(mongo.findById(SocialLoginTickets.hash(value), Document.class, SocialLoginTickets.COLLECTION));
    }
    @Test void concurrentInstancesCanConsumePendingTicketOnlyOnce() throws Exception {
        SocialLoginTickets first = new SocialLoginTickets(mongo), second = new SocialLoginTickets(mongo);
        String value = first.create(new Document("_id", "signup-id").append("tokenVersion", 4).append("registrationPending", true));
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> calls = List.of(() -> consume(first, value), () -> consume(second, value));
            int succeeded = 0;
            for (var result : executor.invokeAll(calls)) if (result.get()) succeeded++;
            assertEquals(1, succeeded);
        }
    }
    private boolean consume(SocialLoginTickets service, String value) {
        try {
            SocialLoginTickets.Ticket result = service.consume(value);
            assertTrue(result.isSignupPending()); assertEquals(4, result.getTokenVersion()); return true;
        } catch (ApiException expired) { assertEquals(401, expired.getStatus()); return false; }
    }
}
