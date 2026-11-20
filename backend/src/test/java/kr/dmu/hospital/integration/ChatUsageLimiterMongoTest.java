package kr.dmu.hospital.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Only explicitly configured local MongoDB is used; no paid API is called. */
@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI",
        matches = "^mongodb://(?:127\\.0\\.0\\.1|localhost)(?::\\d+)?/.*$")
class ChatUsageLimiterMongoTest {
    private static final String SECRET = "test-only-chat-usage-secret-with-32-characters";
    private MongoClient client;
    private MongoTemplate mongo;
    private String database;
    private Clock clock;

    @BeforeEach
    void setUp() {
        client = MongoClients.create(System.getenv("MONGODB_TEST_URI"));
        database = "hospital_chat_usage_test_" + UUID.randomUUID().toString().replace("-", "");
        mongo = new MongoTemplate(client, database);
        clock = Clock.fixed(Instant.parse("2030-11-20T12:00:00Z"), ZoneOffset.UTC);
        limiter(clock).run(new DefaultApplicationArguments(new String[0]));
    }

    @AfterEach
    void tearDown() {
        if (mongo != null && database != null && database.matches("hospital_chat_usage_test_[a-f0-9]{32}")) {
            mongo.getDb().drop();
        }
        if (client != null) client.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void limitSurvivesDifferentInstancesAndRestart(boolean authenticated) {
        int limit = authenticated ? 60 : 20;
        String caller = authenticated ? "user:test-member" : "ip:192.0.2.10";
        ChatUsageLimiter first = limiter(clock);
        for (int index = 0; index < limit / 2; index++) first.checkLimit(caller, authenticated);
        ChatUsageLimiter second = limiter(clock);
        for (int index = limit / 2; index < limit; index++) second.checkLimit(caller, authenticated);

        ApiException error = assertThrows(ApiException.class,
                () -> limiter(clock).checkLimit(caller, authenticated));
        assertEquals(429, error.getStatus());
        assertEquals("오늘의 상담 이용 횟수를 모두 사용했습니다. 병원 직접 검색은 계속 이용할 수 있어요.", error.getMessage());
        assertEquals(limit, onlyCounter().getInteger("count"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentFirstRequestsNeverExceedTheQuota(boolean authenticated) throws Exception {
        int limit = authenticated ? 60 : 20;
        ChatUsageLimiter first = limiter(clock);
        ChatUsageLimiter second = limiter(clock);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < 96; index++) {
                ChatUsageLimiter instance = index % 2 == 0 ? first : second;
                results.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        instance.checkLimit("ip:192.0.2.20", authenticated);
                        return true;
                    } catch (ApiException exception) {
                        assertEquals(429, exception.getStatus());
                        return false;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> result : results) if (result.get(15, TimeUnit.SECONDS)) accepted++;
            assertEquals(limit, accepted);
        }
        assertEquals(1, mongo.getCollection(ChatUsageLimiter.COLLECTION).countDocuments());
        assertEquals(limit, onlyCounter().getInteger("count"));
    }

    @Test
    void koreanMidnightStartsANewQuotaWithoutWaitingForTtlCleanup() {
        ChatUsageLimiter before = limiter(Clock.fixed(Instant.parse("2030-11-20T14:59:59Z"), ZoneOffset.UTC));
        for (int index = 0; index < 20; index++) before.checkLimit("ip:192.0.2.30", false);
        assertThrows(ApiException.class, () -> before.checkLimit("ip:192.0.2.30", false));
        assertEquals(Date.from(Instant.parse("2030-11-20T15:00:00Z")), onlyCounter().getDate("expiresAt"));

        limiter(Clock.fixed(Instant.parse("2030-11-20T15:00:00Z"), ZoneOffset.UTC))
                .checkLimit("ip:192.0.2.30", false);
        List<Document> counters = mongo.getCollection(ChatUsageLimiter.COLLECTION).find().into(new ArrayList<>());
        assertEquals(2, counters.size());
        assertEquals(Set.of(20, 1), Set.of(counters.get(0).getInteger("count"), counters.get(1).getInteger("count")));
        assertEquals(2, counters.stream().map(item -> item.getString("_id")).distinct().count());
    }

    @Test
    void callersRemainSeparateAndOnlyOpaqueIdCountAndExpiryAreStored() {
        ChatUsageLimiter limiter = limiter(clock);
        limiter.checkLimit("ip:192.0.2.40", false);
        limiter.checkLimit("user:test-member", true);
        List<Document> counters = mongo.getCollection(ChatUsageLimiter.COLLECTION).find().into(new ArrayList<>());
        assertEquals(2, counters.size());
        for (Document counter : counters) {
            assertEquals(Set.of("_id", "count", "expiresAt"), counter.keySet());
            assertTrue(counter.getString("_id").matches("[a-f0-9]{64}"));
            assertFalse(counter.toJson().contains("192.0.2.40"));
            assertFalse(counter.toJson().contains("test-member"));
            assertEquals(1, counter.getInteger("count"));
        }
        Document ttl = mongo.getCollection(ChatUsageLimiter.COLLECTION).listIndexes().into(new ArrayList<>())
                .stream().filter(index -> "chat_usage_expiry_v1".equals(index.getString("name"))).findFirst().orElseThrow();
        assertEquals(new Document("expiresAt", 1), ttl.get("key"));
        assertEquals(0, ((Number) ttl.get("expireAfterSeconds")).longValue());
    }

    @Test
    void invalidMessagesAreNotCountedAndEmergencyShortCircuitIsCountedWithoutExternalCalls() {
        OpenAiClient ai = mock(OpenAiClient.class);
        HospitalService hospitals = mock(HospitalService.class);
        ChatService chat = new ChatService(ai, hospitals, new ObjectMapper(), limiter(clock));
        assertThrows(ApiException.class, () -> chat.chat(Map.of("messages", List.of()), "ip:192.0.2.50", false));
        assertEquals(0, mongo.getCollection(ChatUsageLimiter.COLLECTION).countDocuments());
        Map<String, Object> result = chat.chat(Map.of("messages", List.of(Map.of(
                "role", "user", "content", "숨을 못 쉬겠어요"))), "ip:192.0.2.50", false);
        assertEquals(true, result.get("emergency"));
        assertEquals(1, onlyCounter().getInteger("count"));
        verifyNoInteractions(ai, hospitals);
    }

    private ChatUsageLimiter limiter(Clock time) { return new ChatUsageLimiter(mongo, SECRET, time); }
    private Document onlyCounter() {
        assertEquals(1, mongo.getCollection(ChatUsageLimiter.COLLECTION).countDocuments());
        return mongo.getCollection(ChatUsageLimiter.COLLECTION).find().first();
    }
}
