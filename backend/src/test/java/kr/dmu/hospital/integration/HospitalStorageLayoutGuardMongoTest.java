package kr.dmu.hospital.integration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Guard checks are read-only and run only against an explicitly selected local test database. */
@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI",
        matches = "^mongodb://(?:127\\.0\\.0\\.1|localhost):\\d+/.*$")
class HospitalStorageLayoutGuardMongoTest {
    private MongoClient client;
    private MongoTemplate mongo;

    @BeforeEach
    void setUp() {
        client = MongoClients.create(System.getenv("MONGODB_TEST_URI"));
        mongo = new MongoTemplate(client, "hospital_layout_test_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @AfterEach
    void tearDown() {
        if (mongo != null) mongo.getDb().drop();
        if (client != null) client.close();
    }

    @Test
    void freshDatabaseIsAllowedWithoutCreatingCollections() {
        validate();
        assertTrue(mongo.getCollectionNames().isEmpty());
    }

    @Test
    void emptyBasicCollectionIsAllowedWithoutCreatingOtherCollections() {
        mongo.createCollection("hospitals");
        validate();
        assertEquals(Set.of("hospitals"), mongo.getCollectionNames());
        assertEquals(0, mongo.getCollection("hospitals").countDocuments());
    }

    @Test
    void anyLegacyCatalogBlocksStartupAndPreservesTheLegacyLayout() {
        Document details = new Document("_id", "existing").append("source", "LIVE")
                .append("evidence", List.of()).append("detailFetchedAt", new Date(1));
        mongo.insert(details, "hospitals");
        mongo.createCollection("hospitalBasicCatalog");
        Set<String> before = mongo.getCollectionNames();
        assertThrows(IllegalStateException.class, this::validate);
        assertEquals(before, mongo.getCollectionNames());
        assertEquals(details, mongo.findById("existing", Document.class, "hospitals"));
        assertEquals(0, mongo.getCollection("hospitalBasicCatalog").countDocuments());
    }

    @Test
    void legacyDetailsWithoutTheOldCatalogStillBlockStartupWithoutMutation() {
        Document details = new Document("_id", "existing").append("provider", "HIRA")
                .append("externalId", "existing").append("name", "보존할 병원")
                .append("detailFetchedAt", new Date(1));
        mongo.insert(details, "hospitals");
        assertThrows(IllegalStateException.class, this::validate);
        assertEquals(Set.of("hospitals"), mongo.getCollectionNames());
        assertEquals(details, mongo.findById("existing", Document.class, "hospitals"));
    }

    @Test
    void mixedBasicAndDetailFieldsBlockStartupWithoutMutation() {
        Document mixed = basic().append("detailComplete", true).append("evidence", List.of());
        mongo.insert(mixed, "hospitals");
        assertThrows(IllegalStateException.class, this::validate);
        assertEquals(mixed, mongo.findById("same-id", Document.class, "hospitals"));
    }

    @Test
    void renamedLayoutKeepsBasicDetailsAndTheirSharedIdUnchanged() {
        Document basic = basic().append("active", false);
        Document details = new Document("_id", "same-id").append("detailComplete", true)
                .append("detailFetchedAt", new Date(1)).append("evidence", List.of());
        mongo.insert(basic, "hospitals");
        mongo.insert(details, "hospitalDetails");
        validate();
        assertEquals(Set.of("hospitals", "hospitalDetails"), mongo.getCollectionNames());
        assertEquals(basic, mongo.findById("same-id", Document.class, "hospitals"));
        assertEquals(details, mongo.findById("same-id", Document.class, "hospitalDetails"));
    }

    private Document basic() {
        return new Document("_id", "same-id").append("provider", "HIRA").append("active", true)
                .append("apiFields", new Document("ykiho", "source-id").append("yadmNm", "기본 병원"));
    }

    private void validate() {
        new HospitalStorageLayoutGuard(mongo).afterSingletonsInstantiated();
    }
}
