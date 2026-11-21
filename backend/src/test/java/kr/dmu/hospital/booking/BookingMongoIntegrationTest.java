package kr.dmu.hospital.booking;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import kr.dmu.hospital.common.ApiException;
import kr.dmu.hospital.integration.HospitalCatalog;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Runs against an actual MongoDB replica set; uses a new isolated database for every test. */
@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class BookingMongoIntegrationTest {
    private MongoClient client;
    private MongoTemplate mongo;
    private BookingService booking;
    private String database;
    private String date;
    private List<Document> doctors;

    @BeforeEach
    void setUp() {
        client = MongoClients.create(System.getenv("MONGODB_TEST_URI"));
        database = "hospital_booking_test_" + UUID.randomUUID().toString().replace("-", "");
        SimpleMongoClientDatabaseFactory factory = new SimpleMongoClientDatabaseFactory(client, database);
        mongo = new MongoTemplate(factory);
        new BookingIndexes(mongo).run(new DefaultApplicationArguments(new String[0]));
        for (String id : List.of("user-a", "user-b")) {
            mongo.insert(new Document("_id", id).append("role", "USER"), "users");
        }
        for (int i = 0; i < 12; i++) {
            mongo.insert(new Document("_id", "concurrent-" + i).append("role", "USER"), "users");
        }
        mongo.insert(new Document("_id", "test-hospital").append("name", "시험용 가상 병원")
                .append("address", "테스트 주소").append("tel", "000-0000-0000")
                .append("source", "SAMPLE").append("departmentCode", "01")
                .append("departments", List.of(new Document("code", "01").append("name", "내과"))), "hospitalDetails");
        booking = new BookingService(mongo, new MongoTransactionManager(factory));
        doctors = booking.doctors("test-hospital", "01");
        LocalDate next = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);
        if (next.getDayOfWeek() == DayOfWeek.SUNDAY) {
            next = next.plusDays(1);
        }
        date = next.toString();
    }

    @AfterEach
    void tearDown() {
        if (database != null && database.startsWith("hospital_booking_test_")) {
            mongo.getDb().drop();
        }
        if (client != null) {
            client.close();
        }
    }

    @Test
    void doctorAssignmentIsPersistentAndDepartmentIsValidated() {
        assertEquals(3, doctors.size());
        assertEquals(doctors, booking.doctors("test-hospital", "01"));
        assertThrows(ApiException.class, () -> booking.doctors("test-hospital", "13"));
        assertThrows(ApiException.class, () -> booking.doctors("missing", "01"));
    }

    @Test
    void createCommitsReservationCapacityRequestAndEventTogether() {
        String slot = slot(0, 0);
        Document reservation = booking.create("user-a", "create-1", slot);
        assertEquals("RESERVED", reservation.getString("status"));
        assertEquals(1, count(slot));
        assertEquals(1, mongo.getCollection("reservations").countDocuments());
        assertEquals(1, mongo.getCollection("requests").countDocuments());
        assertEquals(1, mongo.getCollection("reservationEvents").countDocuments());
    }

    @Test
    void identicalRetryReturnsTheOriginalResponseWithoutTakingAnotherSeat() {
        String slot = slot(0, 0);
        Document first = booking.create("user-a", "same-request", slot);
        Document retry = booking.create("user-a", "same-request", slot);
        assertEquals(first, retry);
        assertEquals(1, count(slot));
        assertEquals(1, mongo.getCollection("reservations").countDocuments());
    }

    @Test
    void reuseOfRequestKeyWithDifferentBodyIsRejected() {
        String firstSlot = slot(0, 0);
        String nextSlot = slot(0, 1);
        booking.create("user-a", "same-request", firstSlot);
        assertThrows(ApiException.class, () -> booking.create("user-a", "same-request", nextSlot));
        assertEquals(1, count(firstSlot));
        assertEquals(0, count(nextSlot));
    }

    @Test
    void anotherUserCannotReadMutateOrCancelTheReservation() {
        String slot = slot(0, 0);
        Document own = booking.create("user-a", "own", slot);
        assertTrue(booking.reservations("user-b").isEmpty());
        assertThrows(ApiException.class, () -> booking.cancel("user-b", own.getString("id"), "intruder"));
        assertThrows(ApiException.class, () -> booking.change("user-b", own.getString("id"), "intruder-2", slot(0, 1)));
        assertEquals(1, count(slot));
    }

    @Test
    void concurrentRequestsCannotExceedCapacity() throws Exception {
        String slot = slot(0, 0);
        booking.editSlot(slot, 2, false);
        ExecutorService executor = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String user = "concurrent-" + i;
            outcomes.add(executor.submit(() -> {
                start.await();
                try {
                    booking.create(user, "request-" + user, slot);
                    return true;
                } catch (ApiException full) {
                    return false;
                }
            }));
        }
        start.countDown();
        int successes = 0;
        try {
            for (Future<Boolean> outcome : outcomes) {
                if (outcome.get(30, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(2, successes);
        assertEquals(2, count(slot));
        assertEquals(2, mongo.getCollection("reservations").countDocuments());
        assertEquals(2, mongo.getCollection("requests").countDocuments());
        assertEquals(2, mongo.getCollection("reservationEvents").countDocuments());
    }

    @Test
    void duplicateActiveStartTimeRollsBackTheSecondSlotCounter() {
        String first = slot(0, 0);
        String sameTimeOtherDoctor = slot(1, 0);
        booking.create("user-a", "original", first);
        assertThrows(ApiException.class, () -> booking.create("user-a", "duplicate-time", sameTimeOtherDoctor));
        assertEquals(1, count(first));
        assertEquals(0, count(sameTimeOtherDoctor));
        assertEquals(1, mongo.getCollection("requests").countDocuments());
    }

    @Test
    void cancellationRestoresCapacityExactlyOnceEvenWithNewRequestKey() {
        String slot = slot(0, 0);
        String id = booking.create("user-a", "create", slot).getString("id");
        Document canceled = booking.cancel("user-a", id, "cancel");
        assertEquals("CANCELED", canceled.getString("status"));
        assertEquals(canceled, booking.cancel("user-a", id, "cancel"));
        assertEquals(canceled, booking.cancel("user-a", id, "cancel-again"));
        assertEquals(0, count(slot));
        assertEquals(2, mongo.getCollection("reservationEvents").countDocuments());
    }

    @Test
    void unsuccessfulChangePreservesOriginalReservationAndCapacity() {
        String first = slot(0, 0);
        String closed = slot(0, 1);
        String id = booking.create("user-a", "create", first).getString("id");
        booking.editSlot(closed, 4, true);
        assertThrows(ApiException.class, () -> booking.change("user-a", id, "change-closed", closed));
        assertEquals(1, count(first));
        assertEquals(0, count(closed));
        assertEquals(first, booking.reservations("user-a").getFirst().getString("slotId"));
        assertEquals(1, mongo.getCollection("reservationEvents").countDocuments());
        assertEquals(1, mongo.getCollection("requests").countDocuments());
    }

    @Test
    void successfulChangeTransfersCapacityAndRecordsOneChangeEvent() {
        String first = slot(0, 0);
        String next = slot(0, 1);
        String id = booking.create("user-a", "create", first).getString("id");
        Document changed = booking.change("user-a", id, "change", next);
        assertEquals(id, changed.getString("id"));
        assertEquals(next, changed.getString("slotId"));
        assertEquals(0, count(first));
        assertEquals(1, count(next));
        assertEquals(changed, booking.change("user-a", id, "change", next));
        assertEquals(2, mongo.getCollection("reservationEvents").countDocuments());
    }

    @Test
    void adminCannotReduceCapacityBelowReservationsOrCloseOccupiedSlot() {
        String slot = slot(0, 0);
        booking.create("user-a", "create", slot);
        assertThrows(ApiException.class, () -> booking.editSlot(slot, 0, false));
        assertThrows(ApiException.class, () -> booking.editSlot(slot, 4, true));
        assertEquals(1, count(slot));
        assertFalse(mongo.findById(slot, Document.class, "timeSlots").getBoolean("closed"));
    }

    @Test
    void repeatedSlotQueriesPreserveAdminEditsAndClosedSlotsRejectBooking() {
        String slot = slot(0, 0);
        booking.editSlot(slot, 2, true);
        booking.slots(doctors.getFirst().getString("id"), date, false);
        Document persisted = mongo.findById(slot, Document.class, "timeSlots");
        assertEquals(2, persisted.getInteger("capacity"));
        assertTrue(persisted.getBoolean("closed"));
        assertThrows(ApiException.class, () -> booking.create("user-a", "closed", slot));
        assertEquals(0, count(slot));
    }

    @Test
    void catalogOnlyHospitalCanAssignDoctorsBeforeDetailIsOpened() {
        mongo.insert(catalogHospital("catalog-only"), HospitalCatalog.COLLECTION);
        List<Document> assigned = booking.doctors("catalog-only", "01");
        assertEquals(3, assigned.size());
        assertEquals(assigned, booking.doctors("catalog-only", "01"));
        assertNull(mongo.findById("catalog-only", Document.class, "hospitalDetails"));
    }

    @Test
    void reservationUsesCurrentCatalogBasicFactsInsteadOfOldDetailCache() {
        String slot = slot(0, 0);
        mongo.insert(catalogHospital("test-hospital"), HospitalCatalog.COLLECTION);
        Document reservation = booking.create("user-a", "catalog-current", slot);
        assertEquals("갱신된 병원", reservation.getString("hospitalName"));
        Document saved = mongo.findById(reservation.getString("id"), Document.class, "reservations");
        assertEquals("서울 갱신 주소", saved.get("snapshot", Document.class).getString("address"));
        assertEquals("시험용 가상 병원", mongo.findById("test-hospital", Document.class, "hospitalDetails").getString("name"));
    }

    @Test
    void retirementBlocksNewBookingButPreservesExistingHistoryAndCancellation() {
        mongo.insert(catalogHospital("test-hospital"), HospitalCatalog.COLLECTION);
        String first = slot(0, 0);
        String next = slot(0, 1);
        Document reservation = booking.create("user-a", "before-retirement", first);
        mongo.updateFirst(Query.query(Criteria.where("_id").is("test-hospital")),
                new org.springframework.data.mongodb.core.query.Update().set("active", false), HospitalCatalog.COLLECTION);
        assertEquals(410, assertThrows(ApiException.class, () -> booking.doctors("test-hospital", "01")).getStatus());
        assertEquals(410, assertThrows(ApiException.class, () -> booking.slots(doctors.getFirst().getString("id"), date, false)).getStatus());
        assertEquals(410, assertThrows(ApiException.class, () -> booking.create("user-b", "after-retirement", next)).getStatus());
        assertEquals(410, assertThrows(ApiException.class, () -> booking.change("user-a", reservation.getString("id"), "change-retired", next)).getStatus());
        assertEquals(1, count(first));
        assertEquals(0, count(next));
        assertEquals(1, booking.reservations("user-a").size());
        assertEquals(1, mongo.getCollection("requests").countDocuments());
        Document canceled = booking.cancel("user-a", reservation.getString("id"), "cancel-retired");
        assertEquals("CANCELED", canceled.getString("status"));
        assertEquals(0, count(first));
    }

    private Document catalogHospital(String id) {
        return new Document("_id", id).append("provider", "HIRA").append("externalId", id)
                .append("name", "갱신된 병원").append("address", "서울 갱신 주소").append("tel", "02-123-4567")
                .append("active", true).append("basicFetchedAt", new Date()).append("departmentCodes", List.of("01"))
                .append("departments", List.of(new Document("code", "01").append("name", "내과")));
    }

    private String slot(int doctor, int number) {
        return booking.slots(doctors.get(doctor).getString("id"), date, false).get(number).getString("id");
    }

    private int count(String slot) {
        return mongo.findById(slot, Document.class, "timeSlots").getInteger("reservedCount");
    }
}
