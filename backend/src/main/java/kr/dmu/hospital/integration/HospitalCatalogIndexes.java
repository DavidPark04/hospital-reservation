package kr.dmu.hospital.integration;

import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Index creation is idempotent; the full catalog is never scanned or backfilled at startup. */
@Component
@Order(5)
public class HospitalCatalogIndexes implements ApplicationRunner {
    private final MongoTemplate mongo;

    public HospitalCatalogIndexes(MongoTemplate mongo) { this.mongo = mongo; }

    @Override public void run(ApplicationArguments args) {
        var collection = mongo.getCollection(HospitalCatalog.COLLECTION);
        collection.createIndex(new Document("provider", 1).append("externalId", 1),
                new IndexOptions().unique(true).name("catalog_provider_external_v1"));
        collection.createIndex(new Document("active", 1).append("name", 1).append("_id", 1),
                new IndexOptions().name("catalog_active_name_v1"));
        collection.createIndex(new Document("departmentCodes", 1).append("active", 1).append("name", 1).append("_id", 1),
                new IndexOptions().name("catalog_department_name_v1"));
    }
}
