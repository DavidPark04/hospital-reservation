package kr.dmu.hospital.booking;

import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Reservation schema version 1. Collections exist before any transaction starts. */
@Component
@Order(10)
public class BookingIndexes implements ApplicationRunner {
    private final MongoTemplate mongo;

    public BookingIndexes(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String collection : List.of("doctors", "timeSlots", "reservations", "requests", "reservationEvents")) {
            if (!mongo.collectionExists(collection)) {
                mongo.createCollection(collection);
            }
        }
        mongo.getCollection("doctors").createIndex(
                new Document("hospitalId", 1).append("departmentCode", 1).append("sampleCode", 1),
                new IndexOptions().unique(true).name("doctor_assignment_v1"));
        mongo.getCollection("timeSlots").createIndex(
                new Document("doctorId", 1).append("startAt", 1),
                new IndexOptions().unique(true).name("doctor_start_v1"));
        mongo.getCollection("reservations").createIndex(
                new Document("userId", 1).append("startAt", 1),
                new IndexOptions().unique(true).name("active_user_start_v1")
                        .partialFilterExpression(new Document("status", "RESERVED")));
        mongo.getCollection("requests").createIndex(
                new Document("userId", 1).append("key", 1),
                new IndexOptions().unique(true).name("user_request_key_v1"));
        mongo.getCollection("reservationEvents").createIndex(
                new Document("reservationId", 1).append("createdAt", 1));

        Document slotProperties = new Document("capacity", new Document("bsonType", "int").append("minimum", 0))
                .append("reservedCount", new Document("bsonType", "int").append("minimum", 0))
                .append("closed", new Document("bsonType", "bool"))
                .append("doctorId", new Document("bsonType", "string"))
                .append("startAt", new Document("bsonType", "date"));
        Document slotSchema = new Document("bsonType", "object")
                .append("required", List.of("doctorId", "startAt", "capacity", "reservedCount", "closed"))
                .append("properties", slotProperties);
        mongo.executeCommand(new Document("collMod", "timeSlots")
                .append("validator", new Document("$and", List.of(
                        new Document("$jsonSchema", slotSchema),
                        new Document("$expr", new Document("$lte", List.of("$reservedCount", "$capacity"))))))
                .append("validationLevel", "strict").append("validationAction", "error"));
        mongo.executeCommand(new Document("collMod", "reservations")
                .append("validator", new Document("$jsonSchema", new Document("bsonType", "object")
                        .append("required", List.of("userId", "slotId", "startAt", "status"))
                        .append("properties", new Document("status", new Document("enum", List.of("RESERVED", "CANCELED")))
                                .append("startAt", new Document("bsonType", "date"))
                                .append("userId", new Document("bsonType", "string")))))
                .append("validationLevel", "strict").append("validationAction", "error"));
    }
}
