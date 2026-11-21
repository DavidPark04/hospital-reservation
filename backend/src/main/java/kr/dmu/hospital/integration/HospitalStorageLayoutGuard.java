package kr.dmu.hospital.integration;

import org.bson.Document;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Refuses an unfinished storage rename before the web server and mutation runners start. */
@Component
public class HospitalStorageLayoutGuard implements SmartInitializingSingleton {
    private final MongoTemplate mongo;

    public HospitalStorageLayoutGuard(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (mongo.collectionExists("hospitalBasicCatalog")) {
            throw incompatibleLayout();
        }
        if (!mongo.collectionExists(HospitalCatalog.COLLECTION)) return;

        // The one-time migration verifies every document. Startup reads only one row.
        Document hospital = mongo.getCollection(HospitalCatalog.COLLECTION).find()
                .projection(new Document("active", 1).append("apiFields", 1)
                        .append("detailFetchedAt", 1).append("detailComplete", 1)
                        .append("evidence", 1).append("source", 1))
                .limit(1).first();
        if (hospital == null) return;
        if (!(hospital.get("active") instanceof Boolean) || !(hospital.get("apiFields") instanceof Document)
                || hospital.containsKey("detailFetchedAt") || hospital.containsKey("detailComplete")
                || hospital.containsKey("evidence") || "SAMPLE".equals(hospital.getString("source"))) {
            throw incompatibleLayout();
        }
    }

    private IllegalStateException incompatibleLayout() {
        return new IllegalStateException("Hospital storage names require the verified collection migration: "
                + "hospitals must contain basic data and hospitalDetails must contain detail caches. "
                + "Startup stopped without changing stored data.");
    }
}
