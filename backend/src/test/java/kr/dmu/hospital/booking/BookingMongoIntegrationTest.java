package kr.dmu.hospital.booking;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import kr.dmu.hospital.common.ApiException;
import kr.dmu.hospital.auth.UserPrincipal;
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
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

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
        mongo.insert(new Document("_id", "test-hospital").append("userId", "hospital-owner").append("businessNumber", "1234567890")
                .append("status", "ACTIVE"), "hospitalRegistrations");
        mongo.insert(new Document("_id", "hospital-owner").append("role", "HOSPITAL").append("hospitalId", "test-hospital")
                .append("authProvider", "LOCAL").append("name", "시험 병원"), "users");
        for (int i = 0; i < 3; i++) {
            mongo.insert(new Document("_id", "doctor-" + i).append("hospitalId", "test-hospital")
                    .append("departmentCode", "01").append("departmentName", "내과").append("name", "의료진 " + i)
                    .append("specialty", "내과").append("sampleCode", "managed-" + i).append("active", true).append("isVirtual", false), "doctors");
        }
        booking = new BookingService(mongo, new MongoTransactionManager(factory));
        doctors = booking.doctors("test-hospital", "01");
        LocalDate next = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);
        if (next.getDayOfWeek() == DayOfWeek.SUNDAY) {
            next = next.plusDays(1);
        }
        date = next.toString();
        for (Document doctor : doctors) booking.createSlots(doctor.getString("id"), date, "09:00", "12:00", 30, 4);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
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
        assertTrue(booking.doctors("test-hospital", "13").isEmpty());
        assertThrows(ApiException.class, () -> booking.doctors("test-hospital", "99"));
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
    void catalogOnlyHospitalCannotCreateVirtualDoctorsOrAcceptBookings() {
        mongo.insert(catalogHospital("catalog-only"), HospitalCatalog.COLLECTION);
        assertEquals(404, assertThrows(ApiException.class, () -> booking.doctors("catalog-only", "01")).getStatus());
        mongo.insert(new Document("_id", "catalog-only").append("userId", "another-owner").append("status", "ACTIVE"), "hospitalRegistrations");
        assertTrue(booking.doctors("catalog-only", "01").isEmpty());
        assertEquals(0, mongo.getCollection("doctors").countDocuments(new Document("hospitalId", "catalog-only")));
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

    @Test
    void hospitalOwnsOnlyItsDoctorsSchedulesAndReservations() {
        authenticate("hospital-owner");
        String ownSlot = slot(0, 0);
        Document own = booking.create("user-a", "own-booking", ownSlot);
        seedOtherHospital();
        assertEquals(3, booking.managedDoctors().size());
        assertEquals(1, booking.managedReservations(null, null).size());
        assertThrows(ApiException.class, () -> booking.editDoctor("other-doctor", "다른 의사", "01", "", true));
        assertThrows(ApiException.class, () -> booking.managedSlots("other-doctor", date));
        assertThrows(ApiException.class, () -> booking.createManagedSlots("other-doctor", date, "09:00", "10:00", 30, 3));
        String otherSlot = booking.createSlots("other-doctor", date, "09:00", "10:00", 30, 3).getFirst().getString("id");
        assertThrows(ApiException.class, () -> booking.editManagedSlot(otherSlot, 1, false));
        Document other = booking.create("user-b", "other-booking", otherSlot);
        assertThrows(ApiException.class, () -> booking.cancelManagedReservation(other.getString("id"), "foreign-cancel"));
        assertThrows(ApiException.class, () -> booking.changeManagedReservation(other.getString("id"), "foreign-change", ownSlot));
        assertThrows(ApiException.class, () -> booking.changeManagedReservation(own.getString("id"), "cross-hospital", otherSlot));
        assertEquals(1, count(ownSlot)); assertEquals(1, count(otherSlot));
    }

    @Test
    void hospitalCanManageRealDoctorsAndSchedulesWithoutGeneratingSamples() {
        mongo.insert(catalogHospital("test-hospital"), HospitalCatalog.COLLECTION);
        authenticate("hospital-owner");
        Document doctor = booking.createDoctor("등록 의료진", "05", "관절 진료");
        String doctorId = doctor.getString("id");
        assertFalse(doctor.getBoolean("isVirtual"));
        assertTrue(booking.slots(doctorId, date, false).isEmpty());
        assertTrue(booking.managedHospital().getList("departments", Document.class).stream().anyMatch(d -> "05".equals(d.getString("code"))));
        List<Document> created = booking.createManagedSlots(doctorId, date, "13:00", "14:00", 30, 2);
        assertEquals(2, created.size());
        Document edited = booking.editManagedSlot(created.getFirst().getString("id"), 1, false);
        assertEquals(1, edited.getInteger("capacity"));
        booking.editDoctor(doctorId, "수정 의료진", "05", "관절·운동 진료", false);
        assertTrue(booking.doctors("test-hospital", "05").isEmpty());
        assertThrows(ApiException.class, () -> booking.create("user-a", "inactive", created.getFirst().getString("id")));
        assertEquals(0, count(created.getFirst().getString("id")));
    }

    @Test
    void hospitalChangeAndCancelAreIdempotentAndKeepCapacityAndEventsTogether() {
        authenticate("hospital-owner");
        String first = slot(0, 0); String next = slot(0, 1);
        String id = booking.create("user-a", "create", first).getString("id");
        Document changed = booking.changeManagedReservation(id, "hospital-change", next);
        assertEquals(next, changed.getString("slotId"));
        assertEquals(changed, booking.changeManagedReservation(id, "hospital-change", next));
        assertEquals(0, count(first)); assertEquals(1, count(next));
        assertEquals(1, mongo.getCollection("reservationEvents").countDocuments(new Document("action", "HOSPITAL_CHANGE")));
        assertThrows(ApiException.class, () -> booking.editManagedSlot(next, 0, false));
        assertThrows(ApiException.class, () -> booking.editManagedSlot(next, 4, true));
        Document canceled = booking.cancelManagedReservation(id, "hospital-cancel");
        assertEquals("CANCELED", canceled.getString("status"));
        assertEquals(canceled, booking.cancelManagedReservation(id, "hospital-cancel"));
        assertEquals(canceled, booking.cancelManagedReservation(id, "hospital-cancel-again"));
        assertEquals(0, count(next));
        assertEquals(1, mongo.getCollection("reservationEvents").countDocuments(new Document("action", "HOSPITAL_CANCEL")));
    }

    @Test
    void personalAndRevokedHospitalAccountsCannotManageHospitalData() {
        authenticate("user-a");
        assertEquals(403, assertThrows(ApiException.class, () -> booking.managedDoctors()).getStatus());
        authenticate("hospital-owner");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("test-hospital")), new Update().set("status", "DISABLED"), "hospitalRegistrations");
        assertEquals(403, assertThrows(ApiException.class, () -> booking.managedDoctors()).getStatus());
        assertEquals(403, assertThrows(ApiException.class, () -> booking.createDoctor("이름", "01", "")).getStatus());
    }

    @Test
    void hospitalReservationResponseOnlyIncludesNeededPatientContact() {
        mongo.updateFirst(Query.query(Criteria.where("_id").is("user-a")), new Update().set("name", "예약 회원").set("phone", "01012345678")
                .set("email", "private@example.com").set("passwordHash", "private-hash"), "users");
        booking.create("user-a", "create", slot(0, 0));
        authenticate("hospital-owner");
        Document view = booking.managedReservations(date, "RESERVED").getFirst();
        assertEquals("예약 회원", view.getString("patientName"));
        assertEquals("01012345678", view.getString("patientPhone"));
        assertFalse(view.containsKey("email")); assertFalse(view.containsKey("passwordHash")); assertFalse(view.containsKey("userId"));
    }

    @Test
    void doctorDepartmentCannotChangeWhileItHasFutureReservations() {
        authenticate("hospital-owner");
        String doctorId = doctors.getFirst().getString("id");
        String id = booking.create("user-a", "create", slot(0, 0)).getString("id");
        assertThrows(ApiException.class, () -> booking.editDoctor(doctorId, "이름", "05", "", true));
        booking.cancelManagedReservation(id, "cancel");
        assertEquals("05", booking.editDoctor(doctorId, "이름", "05", "", true).getString("departmentCode"));
    }

    @Test
    void hospitalAccountCannotReserveAsPatientAndPastSchedulesCannotBeEdited() {
        assertEquals(403, assertThrows(ApiException.class, () -> booking.create("hospital-owner", "create", slot(0, 0))).getStatus());
        authenticate("hospital-owner");
        String id = slot(0, 0);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set("startAt", new Date(1)), "timeSlots");
        assertThrows(ApiException.class, () -> booking.editManagedSlot(id, 2, false));
        assertEquals(4, mongo.findById(id, Document.class, "timeSlots").getInteger("capacity"));
    }

    private void seedOtherHospital() {
        mongo.insert(catalogHospital("other-hospital"), HospitalCatalog.COLLECTION);
        mongo.insert(new Document("_id", "other-hospital").append("userId", "other-owner").append("status", "ACTIVE"), "hospitalRegistrations");
        mongo.insert(new Document("_id", "other-doctor").append("hospitalId", "other-hospital").append("name", "다른 의료진")
                .append("departmentCode", "01").append("departmentName", "내과").append("sampleCode", "managed-other")
                .append("active", true).append("isVirtual", false), "doctors");
    }

    private void authenticate(String id) {
        UserPrincipal user = new UserPrincipal(mongo.findById(id, Document.class, "users"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
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
