package kr.dmu.hospital.integration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/** Selected-hospital detail cache schema, version 1. Existing facts are retained. */
@Component
@Order(5)
public class HospitalIndexes implements ApplicationRunner {
    private final MongoTemplate mongo;

    public HospitalIndexes(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String name : List.of("hospitalDetails", "hospitalEvidence")) {
            if (!mongo.collectionExists(name)) {
                mongo.createCollection(name);
            }
        }
        MongoCollection<Document> hospitals = mongo.getCollection("hospitalDetails");
        MongoCollection<Document> evidence = mongo.getCollection("hospitalEvidence");
        hospitals.createIndex(new Document("provider", 1).append("externalId", 1),
                new IndexOptions().unique(true).name("hospital_provider_external_v1"));

        // A missing source period means unknown; a fetch time is never a source period.
        evidence.updateMany(new Document("basePeriod", new Document("$exists", false)),
                new Document("$set", new Document("basePeriod", null)));
        try (MongoCursor<Document> cursor = hospitals.find().iterator()) {
            while (cursor.hasNext()) {
                backfill(hospitals, evidence, cursor.next());
            }
        }
    }

    private void backfill(MongoCollection<Document> hospitals, MongoCollection<Document> evidence,
                          Document hospital) {
        Document filter = new Document("_id", hospital.get("_id"));
        Document changes = new Document();
        if (!hospital.containsKey("location")) {
            filter.put("location", new Document("$exists", false));
            matchObserved(filter, hospital, "latitude");
            matchObserved(filter, hospital, "longitude");
            changes.put("location", location(hospital));
        }

        List<Document> basicFacts = evidence.find(new Document("hospitalId", hospital.get("_id"))
                .append("kind", "BASIC")).into(new ArrayList<>());
        Object originalEvidence = hospital.get("evidence");
        Date basicTime = latestTime(basicFacts);
        if (basicTime == null && originalEvidence instanceof List<?> entries) {
            basicTime = latestEmbeddedTime(entries);
        }
        if (!hospital.containsKey("basicFetchedAt")) {
            filter.put("basicFetchedAt", new Document("$exists", false));
            matchObserved(filter, hospital, "evidence");
            changes.put("basicFetchedAt", basicTime);
        }

        if (originalEvidence instanceof List<?> entries) {
            List<Object> updated = new ArrayList<>();
            boolean hasBasic = false;
            for (Object entry : entries) {
                if (!(entry instanceof Document fact)) {
                    updated.add(entry);
                    continue;
                }
                Document copy = new Document(fact);
                if ("BASIC".equals(fact.get("kind"))) {
                    hasBasic = true;
                    Document latest = matchingNewerBasic(fact, basicFacts);
                    if (latest != null) {
                        copy.putAll(latest);
                        // A previously recorded period remains an explicit source fact.
                        if (fact.get("basePeriod") != null) copy.put("basePeriod", fact.get("basePeriod"));
                    }
                }
                if (!copy.containsKey("basePeriod")) copy.put("basePeriod", null);
                updated.add(copy);
            }
            if (!hasBasic) {
                Document latest = latestBasicFact(basicFacts);
                if (latest != null) updated.add(new Document(latest));
            }
            if (!updated.equals(entries)) {
                filter.put("evidence", originalEvidence);
                changes.put("evidence", updated);
            }
        } else if (!hospital.containsKey("evidence")) {
            Document latest = latestBasicFact(basicFacts);
            if (latest != null) {
                filter.put("evidence", new Document("$exists", false));
                changes.put("evidence", List.of(new Document(latest)));
            }
        }

        if (!changes.isEmpty()) {
            // Conditional updates cannot replace fields refreshed by a concurrent request.
            hospitals.updateOne(filter, new Document("$set", changes));
        }
    }

    private void matchObserved(Document filter, Document original, String field) {
        if (original.containsKey(field)) {
            filter.put(field, new Document("$eq", original.get(field)).append("$exists", true));
        } else {
            filter.put(field, new Document("$exists", false));
        }
    }

    private Document location(Document hospital) {
        Object latitude = hospital.get("latitude");
        Object longitude = hospital.get("longitude");
        if (!(latitude instanceof Number lat) || !(longitude instanceof Number lon)) return null;
        double latValue = lat.doubleValue();
        double lonValue = lon.doubleValue();
        if (!Double.isFinite(latValue) || !Double.isFinite(lonValue)
                || latValue < -90 || latValue > 90 || lonValue < -180 || lonValue > 180) return null;
        return new Document("latitude", latValue).append("longitude", lonValue);
    }

    private Date latestTime(List<Document> facts) {
        Date latest = null;
        for (Document fact : facts) {
            if (fact.get("fetchedAt") instanceof Date date && (latest == null || date.after(latest))) {
                latest = date;
            }
        }
        return latest;
    }

    private Date latestEmbeddedTime(List<?> entries) {
        List<Document> basic = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof Document fact && "BASIC".equals(fact.get("kind"))) basic.add(fact);
        }
        return latestTime(basic);
    }

    private Document latestBasicFact(List<Document> facts) {
        Document latest = null;
        for (Document fact : facts) {
            if (latest == null || (fact.get("fetchedAt") instanceof Date time
                    && (!(latest.get("fetchedAt") instanceof Date previous) || time.after(previous)))) latest = fact;
        }
        return latest;
    }

    private Document matchingNewerBasic(Document embedded, List<Document> facts) {
        if (!(embedded.get("fetchedAt") instanceof Date originalTime)) return null;
        Document latest = null;
        for (Document fact : facts) {
            boolean sameFact = embedded.containsKey("_id")
                    ? Objects.equals(embedded.get("_id"), fact.get("_id"))
                    : Objects.equals(embedded.get("value"), fact.get("value"));
            if (sameFact && fact.get("fetchedAt") instanceof Date time && time.after(originalTime)
                    && (latest == null || time.after(latest.getDate("fetchedAt")))) latest = fact;
        }
        return latest;
    }
}
