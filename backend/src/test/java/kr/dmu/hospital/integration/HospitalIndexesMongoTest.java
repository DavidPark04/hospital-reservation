package kr.dmu.hospital.integration;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real local MongoDB only; never opens or drops the application's database. */
@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI",
        matches = "^mongodb://(?:127\\.0\\.0\\.1|localhost):\\d+/.*$")
class HospitalIndexesMongoTest {
    private MongoClient client;
    private MongoTemplate mongo;
    private String database;

    @BeforeEach
    void setUp() {
        String uri = System.getenv("MONGODB_TEST_URI");
        if (uri == null || !uri.matches("^mongodb://(?:127\\.0\\.0\\.1|localhost):\\d+/.*$")) {
            throw new IllegalStateException("Schema tests require an explicit local MongoDB URI.");
        }
        client = MongoClients.create(uri);
        database = "hospital_schema_test_" + UUID.randomUUID().toString().replace("-", "");
        mongo = new MongoTemplate(client, database);
    }

    @AfterEach
    void tearDown() {
        if (mongo != null && database != null && database.matches("hospital_schema_test_[a-f0-9]{32}")) {
            mongo.getDb().drop();
        }
        if (client != null) client.close();
    }

    @Test
    void enforcesProviderExternalIdentityWithNamedUniqueIndex() {
        migrate();
        mongo.getCollection("hospitals").insertOne(hospital("one", "shared"));
        assertThrows(MongoWriteException.class,
                () -> mongo.getCollection("hospitals").insertOne(hospital("two", "shared")));
        mongo.getCollection("hospitals").insertOne(hospital("three", "shared").append("provider", "SAMPLE"));
        Document index = mongo.getCollection("hospitals").listIndexes()
                .into(new java.util.ArrayList<>()).stream()
                .filter(row -> "hospital_provider_external_v1".equals(row.getString("name"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, index.getBoolean("unique"));
        assertEquals(new Document("provider", 1).append("externalId", 1), index.get("key"));
    }

    @Test
    void backfillsFiniteLocationAndPreservesExistingLocationAndOtherFields() {
        insert(hospital("valid", "a").append("latitude", 37.25).append("longitude", 127.05)
                .append("custom", "keep"));
        insert(hospital("invalid", "b").append("latitude", Double.NaN).append("longitude", 127));
        insert(hospital("range", "c").append("latitude", 95).append("longitude", 127));
        Document location = new Document("custom", "original");
        insert(hospital("existing", "d").append("location", location).append("latitude", 37).append("longitude", 127));
        migrate();
        assertEquals(new Document("latitude", 37.25).append("longitude", 127.05), get("valid").get("location"));
        assertEquals("keep", get("valid").getString("custom"));
        assertEquals(Double.valueOf(37.25), get("valid").getDouble("latitude"));
        assertTrue(get("invalid").containsKey("location"));
        assertNull(get("invalid").get("location"));
        assertNull(get("range").get("location"));
        assertEquals(location, get("existing").get("location"));
    }

    @Test
    void marksUnknownPeriodsWithoutInventingOrOverwritingSourcePeriods() {
        Document unknown = fact("unknown", "h", "DEPARTMENT", new Date(1000));
        Document known = fact("known", "h", "DESIGNATION", new Date(2000)).append("basePeriod", "2026-2028");
        insert(hospital("h", "a").append("evidence", List.of(unknown, known)));
        mongo.getCollection("hospitalEvidence").insertMany(List.of(new Document(unknown), new Document(known)));
        migrate();
        List<Document> embedded = get("h").getList("evidence", Document.class);
        assertTrue(embedded.get(0).containsKey("basePeriod"));
        assertNull(embedded.get(0).get("basePeriod"));
        assertEquals("2026-2028", embedded.get(1).getString("basePeriod"));
        Document storedUnknown = mongo.getCollection("hospitalEvidence").find(new Document("_id", "unknown")).first();
        assertNotNull(storedUnknown);
        assertTrue(storedUnknown.containsKey("basePeriod"));
        assertNull(storedUnknown.get("basePeriod"));
        assertEquals("2026-2028", mongo.getCollection("hospitalEvidence")
                .find(new Document("_id", "known")).first().getString("basePeriod"));
    }

    @Test
    void derivesBasicTimeFromBasicEvidenceAndSynchronizesOnlyNewerMatchingFacts() {
        Date old = new Date(1000), newer = new Date(2000), detail = new Date(9000);
        Document embedded = fact("basic", "h", "BASIC", old).append("basePeriod", "known-period").append("extra", "keep");
        Document designation = fact("designation", "h", "DESIGNATION", detail).append("basePeriod", null);
        insert(hospital("h", "a").append("fetchedAt", detail).append("evidence", List.of(embedded, designation)));
        mongo.getCollection("hospitalEvidence").insertOne(fact("basic", "h", "BASIC", newer));
        insert(hospital("fallback", "b").append("fetchedAt", detail)
                .append("evidence", List.of(fact("fallback-basic", "fallback", "BASIC", old))));
        insert(hospital("unknown", "c").append("fetchedAt", detail));
        insert(hospital("preserved", "d").append("basicFetchedAt", old).append("fetchedAt", detail));
        Document onlyDetail = fact("only-detail", "restore", "DEPARTMENT", detail).append("basePeriod", null);
        insert(hospital("restore", "e").append("evidence", List.of(onlyDetail)));
        mongo.getCollection("hospitalEvidence").insertOne(fact("restore-basic", "restore", "BASIC", newer));
        insert(hospital("upgrade-period", "f").append("evidence",
                List.of(fact("upgrade-basic", "upgrade-period", "BASIC", old).append("basePeriod", null))));
        mongo.getCollection("hospitalEvidence").insertOne(fact("upgrade-basic", "upgrade-period", "BASIC", newer)
                .append("basePeriod", "source-period"));
        migrate();
        assertEquals(newer, get("h").getDate("basicFetchedAt"));
        List<Document> updated = get("h").getList("evidence", Document.class);
        assertEquals(newer, updated.get(0).getDate("fetchedAt"));
        assertEquals("known-period", updated.get(0).getString("basePeriod"));
        assertEquals("keep", updated.get(0).getString("extra"));
        assertEquals(designation, updated.get(1));
        assertEquals(old, get("fallback").getDate("basicFetchedAt"));
        assertTrue(get("unknown").containsKey("basicFetchedAt"));
        assertNull(get("unknown").get("basicFetchedAt"));
        assertEquals(old, get("preserved").getDate("basicFetchedAt"));
        List<Document> restored = get("restore").getList("evidence", Document.class);
        assertEquals(2, restored.size());
        assertEquals(onlyDetail, restored.getFirst());
        assertEquals("BASIC", restored.getLast().getString("kind"));
        assertEquals(newer, restored.getLast().getDate("fetchedAt"));
        assertEquals(newer, get("restore").getDate("basicFetchedAt"));
        assertEquals("source-period", get("upgrade-period").getList("evidence", Document.class)
                .getFirst().getString("basePeriod"));
    }

    @Test
    void migrationIsIdempotentAndDoesNotAddCollectionsBeyondHospitalSchema() {
        insert(hospital("h", "a").append("evidence", List.of(fact("basic", "h", "BASIC", new Date(1000)))));
        migrate();
        Document first = get("h");
        migrate();
        assertEquals(first, get("h"));
        assertEquals(2, mongo.getCollectionNames().size());
        assertEquals(1, mongo.getCollection("hospitals").countDocuments());
    }

    private Document hospital(String id, String externalId) {
        return new Document("_id", id).append("provider", "HIRA").append("externalId", externalId).append("name", "Test hospital");
    }

    private Document fact(String id, String hospitalId, String kind, Date fetchedAt) {
        return new Document("_id", id).append("hospitalId", hospitalId).append("kind", kind)
                .append("value", id).append("sourceUrl", "https://example.test/source").append("fetchedAt", fetchedAt);
    }

    private void insert(Document document) { mongo.getCollection("hospitals").insertOne(document); }
    private Document get(String id) { return mongo.getCollection("hospitals").find(new Document("_id", id)).first(); }
    private void migrate() { new HospitalIndexes(mongo).run(new DefaultApplicationArguments(new String[0])); }
}
