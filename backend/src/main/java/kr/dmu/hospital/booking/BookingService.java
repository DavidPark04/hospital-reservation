package kr.dmu.hospital.booking;

import com.mongodb.MongoException;
import kr.dmu.hospital.common.ApiException;
import kr.dmu.hospital.auth.CurrentUser;
import kr.dmu.hospital.auth.HospitalAccountService;
import kr.dmu.hospital.auth.UserPrincipal;
import kr.dmu.hospital.integration.Departments;
import kr.dmu.hospital.integration.HospitalCatalog;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Supplier;

@Service
public class BookingService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int RETRIES = 8;
    private final MongoTemplate mongo;
    private final TransactionTemplate transaction;

    public BookingService(MongoTemplate mongo, MongoTransactionManager transactionManager) {
        this.mongo = mongo;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setTimeout(20);
    }

    public List<Document> doctors(String hospitalId, String departmentCode) {
        requireRegisteredHospital(hospitalId);
        if (!Departments.contains(departmentCode)) throw new ApiException(400, "지원하는 진료과를 선택해 주세요.");
        Query selection = Query.query(Criteria.where("hospitalId").is(hospitalId)
                .and("departmentCode").is(departmentCode).and("active").is(true).and("isVirtual").is(false).and("deleted").ne(true));
        List<Document> result = mongo.find(selection.with(Sort.by("name", "_id")), Document.class, "doctors");
        List<Document> views = new ArrayList<>();
        for (Document doctor : result) {
            views.add(doctorView(doctor));
        }
        return views;
    }

    public List<Document> slots(String doctorId, String dateText, boolean admin) {
        Document doctor = requireDocument("doctors", doctorId, "의료진을 찾을 수 없습니다.");
        requireNotDeletedDoctor(doctor);
        if (!admin) {
            requireRegisteredHospital(doctor.getString("hospitalId"));
            requireActiveDoctor(doctor);
        }
        LocalDate date = parseDate(dateText);
        Date start = atSeoul(date, LocalTime.MIN);
        Date end = atSeoul(date.plusDays(1), LocalTime.MIN);
        Criteria criteria = Criteria.where("doctorId").is(doctorId).and("startAt").gte(start).lt(end);
        if (!admin) criteria.and("isVirtual").is(false);
        List<Document> found = mongo.find(Query.query(criteria).with(Sort.by("startAt")), Document.class, "timeSlots");
        List<Document> result = new ArrayList<>();
        Date now = new Date();
        for (Document slot : found) {
            if (admin || slot.getDate("startAt").after(now)) {
                result.add(slotView(slot));
            }
        }
        return result;
    }

    public List<Document> reservations(String userId) {
        List<Document> found = mongo.find(Query.query(Criteria.where("userId").is(userId))
                .with(Sort.by(Sort.Direction.DESC, "startAt")), Document.class, "reservations");
        List<Document> result = new ArrayList<>();
        for (Document reservation : found) {
            result.add(reservationView(reservation));
        }
        return result;
    }

    public Document create(String userId, String key, String slotId) {
        requirePatientAccount(userId);
        return mutate(userId, key, "CREATE\n" + slotId, () -> {
            Document slot = acquireSlot(slotId);
            Document doctor = bookingDoctor(slot.getString("doctorId"));
            Document hospital = requireRegisteredHospital(doctor.getString("hospitalId"));
            Document reservation = new Document("_id", UUID.randomUUID().toString())
                    .append("userId", userId).append("status", "RESERVED")
                    .append("createdAt", new Date()).append("updatedAt", new Date());
            setAppointment(reservation, slot, doctor, hospital);
            mongo.insert(reservation, "reservations");
            Document response = reservationView(reservation);
            event(reservation.getString("_id"), userId, "CREATE", null, response);
            return response;
        });
    }

    public Document change(String userId, String reservationId, String key, String slotId) {
        requirePatientAccount(userId);
        return mutate(userId, key, "CHANGE\n" + reservationId + "\n" + slotId, () -> {
            Document reservation = ownedReservation(reservationId, userId);
            if (!"RESERVED".equals(reservation.getString("status"))) {
                throw new ApiException(409, "취소된 예약은 변경할 수 없습니다.");
            }
            if (slotId.equals(reservation.getString("slotId"))) {
                return reservationView(reservation);
            }
            ensureFuture(reservation.getDate("startAt"));
            Document before = reservationView(reservation);
            Document nextSlot = acquireSlot(slotId);
            Document doctor = bookingDoctor(nextSlot.getString("doctorId"));
            Document hospital = requireRegisteredHospital(doctor.getString("hospitalId"));
            releaseSlot(reservation.getString("slotId"));
            setAppointment(reservation, nextSlot, doctor, hospital);
            reservation.put("updatedAt", new Date());
            mongo.save(reservation, "reservations");
            Document response = reservationView(reservation);
            event(reservationId, userId, "CHANGE", before, response);
            return response;
        });
    }

    public Document cancel(String userId, String reservationId, String key) {
        return mutate(userId, key, "CANCEL\n" + reservationId, () -> {
            Document reservation = ownedReservation(reservationId, userId);
            if ("CANCELED".equals(reservation.getString("status"))) {
                return reservationView(reservation);
            }
            ensureFuture(reservation.getDate("startAt"));
            Document before = reservationView(reservation);
            releaseSlot(reservation.getString("slotId"));
            reservation.put("status", "CANCELED");
            reservation.put("updatedAt", new Date());
            mongo.save(reservation, "reservations");
            Document response = reservationView(reservation);
            event(reservationId, userId, "CANCEL", before, response);
            return response;
        });
    }

    /** Called by member deletion so account removal, cancellations and capacity commit together. */
    public int cancelMemberReservations(String userId, String adminId) {
        return cancelReservations("userId", userId, adminId);
    }

    /** The registration is removed in the same transaction; booking writes conflict with closing these doctors and slots. */
    public int closeHospitalBookings(String hospitalId, String adminId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Hospital closure requires a transaction.");
        }
        Query doctors = Query.query(Criteria.where("hospitalId").is(hospitalId));
        List<String> doctorIds = mongo.findDistinct(doctors, "_id", "doctors", String.class);
        mongo.updateMulti(doctors, new Update().set("active", false).set("updatedAt", new Date()), "doctors");
        if (!doctorIds.isEmpty()) {
            mongo.updateMulti(Query.query(Criteria.where("doctorId").in(doctorIds)),
                    new Update().set("closed", true), "timeSlots");
        }
        return cancelReservations("hospitalId", hospitalId, adminId);
    }

    private int cancelReservations(String ownerField, String ownerId, String adminId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Reservation cancellation requires a transaction.");
        }
        List<Document> reservations = mongo.find(Query.query(Criteria.where(ownerField).is(ownerId)
                .and("status").is("RESERVED")), Document.class, "reservations");
        int canceled = 0;
        for (Document reservation : reservations) {
            Document before = reservationView(reservation);
            Document changed = mongo.findAndModify(Query.query(Criteria.where("_id").is(reservation.getString("_id"))
                            .and(ownerField).is(ownerId).and("status").is("RESERVED")),
                    new Update().set("status", "CANCELED").set("updatedAt", new Date()),
                    FindAndModifyOptions.options().returnNew(true), Document.class, "reservations");
            if (changed != null) {
                releaseSlot(changed.getString("slotId"));
                event(changed.getString("_id"), adminId, "CANCEL", before, reservationView(changed));
                canceled++;
            }
        }
        return canceled;
    }

    public List<Document> createSlots(String doctorId, String dateText, String startText, String endText,
                                       int intervalMinutes, int capacity) {
        return runWithRetry(() -> createSlotsInTransaction(doctorId, dateText, startText, endText, intervalMinutes, capacity));
    }

    private List<Document> createSlotsInTransaction(String doctorId, String dateText, String startText, String endText,
                                       int intervalMinutes, int capacity) {
        Document doctor = requireDocument("doctors", doctorId, "의료진을 찾을 수 없습니다.");
        requireActiveDoctor(doctor);
        requireRegisteredHospital(doctor.getString("hospitalId"));
        lockRegisteredHospital(doctor.getString("hospitalId"));
        LocalDate date = parseDate(dateText);
        LocalTime start;
        LocalTime end;
        try {
            start = LocalTime.parse(startText);
            end = LocalTime.parse(endText);
        } catch (DateTimeParseException e) {
            throw new ApiException(400, "시간은 HH:mm 형식으로 입력해 주세요.");
        }
        LocalDate today = LocalDate.now(SEOUL);
        if (date.isBefore(today) || date.isAfter(today.plusDays(365)) || !start.isBefore(end)
                || intervalMinutes < 15 || intervalMinutes > 120 || capacity < 1 || capacity > 100) {
            throw new ApiException(400, "날짜, 시간 범위, 간격(15~120분), 정원(1~100명)을 확인해 주세요.");
        }
        BulkOperations bulk = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, "timeSlots");
        int count = 0;
        for (LocalTime time = start; time.isBefore(end); ) {
            Date startAt = atSeoul(date, time);
            if (startAt.after(new Date())) {
                addSlotUpsert(bulk, doctorId, startAt, capacity);
                count++;
            }
            int nextMinute = time.getHour() * 60 + time.getMinute() + intervalMinutes;
            if (nextMinute >= 24 * 60) {
                break;
            }
            time = LocalTime.of(nextMinute / 60, nextMinute % 60);
        }
        if (count == 0) {
            throw new ApiException(400, "이미 지난 시간에는 일정을 생성할 수 없습니다.");
        }
        bulk.execute();
        return slots(doctorId, dateText, true);
    }

    public Document editSlot(String slotId, int capacity, boolean closed) {
        return runWithRetry(() -> editSlotInTransaction(slotId, capacity, closed));
    }

    private Document editSlotInTransaction(String slotId, int capacity, boolean closed) {
        if (capacity < 0 || capacity > 100) {
            throw new ApiException(400, "정원은 0~100명 사이로 입력해 주세요.");
        }
        Document existing = requireDocument("timeSlots", slotId, "시간표를 찾을 수 없습니다.");
        Document doctor = requireDocument("doctors", existing.getString("doctorId"), "의료진을 찾을 수 없습니다.");
        requireNotDeletedDoctor(doctor);
        lockRegisteredHospital(doctor.getString("hospitalId"));
        ensureFuture(existing.getDate("startAt"));
        Criteria criteria = Criteria.where("_id").is(slotId).and("startAt").gt(new Date()).and("reservedCount").lte(capacity);
        if (closed) {
            criteria = Criteria.where("_id").is(slotId).and("startAt").gt(new Date()).and("reservedCount").is(0);
        }
        Document changed = mongo.findAndModify(Query.query(criteria), new Update().set("capacity", capacity)
                .set("closed", closed), FindAndModifyOptions.options().returnNew(true), Document.class, "timeSlots");
        if (changed == null) {
            throw new ApiException(409, closed ? "예약이 있는 시간은 휴진 처리할 수 없습니다. 기존 예약을 먼저 확인해 주세요."
                    : "현재 예약 수보다 정원을 줄일 수 없습니다.");
        }
        return slotView(changed);
    }

    public Document managedHospital() {
        Document registration = requireHospitalManager(false);
        Document hospital = requireDocument("hospitals", registration.getString("_id"), "병원 정보를 찾을 수 없습니다.");
        return HospitalAccountService.basicInformation(hospital)
                .append("businessNumber", registration.getString("businessNumber"))
                .append("departments", registration.getOrDefault("departments", List.of()))
                .append("availableDepartments", Departments.items());
    }

    public List<Document> managedDoctors() {
        String hospitalId = requireHospitalManager(false).getString("_id");
        List<Document> found = mongo.find(Query.query(Criteria.where("hospitalId").is(hospitalId).and("isVirtual").is(false).and("deleted").ne(true))
                .with(Sort.by("name", "_id")), Document.class, "doctors");
        List<Document> result = new ArrayList<>();
        for (Document doctor : found) result.add(doctorView(doctor));
        return result;
    }

    public Document createDoctor(String name, String departmentCode, String specialty) {
        validateDoctorInput(name, departmentCode, specialty);
        return runWithRetry(() -> {
            String hospitalId = requireHospitalManager(true).getString("_id");
            requireRegisteredHospital(hospitalId);
            String id = UUID.randomUUID().toString();
            Document doctor = new Document("_id", id).append("hospitalId", hospitalId)
                    .append("departmentCode", departmentCode).append("departmentName", Departments.name(departmentCode))
                    .append("name", name.trim()).append("specialty", specialty.trim()).append("active", true)
                    .append("isVirtual", false).append("sampleCode", "managed-" + id).append("createdAt", new Date());
            mongo.insert(doctor, "doctors");
            refreshManagedDepartments(hospitalId);
            return doctorView(doctor);
        });
    }

    public Document editDoctor(String doctorId, String name, String departmentCode, String specialty, boolean active) {
        validateDoctorInput(name, departmentCode, specialty);
        return runWithRetry(() -> {
            String hospitalId = requireHospitalManager(true).getString("_id");
            Document doctor = ownedDoctor(doctorId, hospitalId);
            if (!departmentCode.equals(doctor.getString("departmentCode")) && mongo.exists(Query.query(
                    Criteria.where("doctorId").is(doctorId).and("status").is("RESERVED").and("startAt").gt(new Date())), "reservations")) {
                throw new ApiException(409, "예정된 예약이 있는 의료진의 진료과는 변경할 수 없습니다. 예약을 먼저 변경하거나 취소해 주세요.");
            }
            doctor.put("name", name.trim()); doctor.put("departmentCode", departmentCode);
            doctor.put("departmentName", Departments.name(departmentCode)); doctor.put("specialty", specialty.trim());
            doctor.put("active", active); doctor.put("updatedAt", new Date());
            mongo.save(doctor, "doctors");
            refreshManagedDepartments(hospitalId);
            return doctorView(doctor);
        });
    }

    public Document deleteDoctor(String doctorId) {
        return runWithRetry(() -> {
            String hospitalId = requireHospitalManager(true).getString("_id");
            Document doctor = mongo.findOne(Query.query(Criteria.where("_id").is(doctorId).and("hospitalId").is(hospitalId)
                    .and("isVirtual").is(false)), Document.class, "doctors");
            if (doctor == null) throw new ApiException(404, "우리 병원에 등록된 의료진을 찾을 수 없습니다.");
            Document response = new Document("id", doctorId).append("deleted", true);
            if (Boolean.TRUE.equals(doctor.getBoolean("deleted"))) return response;
            Date now = new Date();
            if (mongo.exists(Query.query(Criteria.where("doctorId").is(doctorId).and("status").is("RESERVED")
                    .and("startAt").gt(now)), "reservations")) {
                throw new ApiException(409, "예정된 예약이 있는 의료진은 삭제할 수 없습니다. 예약을 먼저 변경하거나 취소해 주세요.");
            }
            // Booking also writes this doctor, so deletion cannot commit alongside a new reservation.
            doctor.put("active", false); doctor.put("deleted", true);
            doctor.put("deletedAt", now); doctor.put("updatedAt", now);
            mongo.save(doctor, "doctors");
            mongo.updateMulti(Query.query(Criteria.where("doctorId").is(doctorId).and("startAt").gt(now).and("reservedCount").is(0)),
                    new Update().set("closed", true), "timeSlots");
            refreshManagedDepartments(hospitalId);
            return response;
        });
    }

    public List<Document> managedSlots(String doctorId, String date) {
        String hospitalId = requireHospitalManager(false).getString("_id");
        ownedDoctor(doctorId, hospitalId);
        return slots(doctorId, date, true);
    }

    public List<Document> createManagedSlots(String doctorId, String date, String startTime, String endTime, int interval, int capacity) {
        return runWithRetry(() -> {
            String hospitalId = requireHospitalManager(true).getString("_id");
            ownedDoctor(doctorId, hospitalId);
            return createSlots(doctorId, date, startTime, endTime, interval, capacity);
        });
    }

    public Document editManagedSlot(String slotId, int capacity, boolean closed) {
        return runWithRetry(() -> {
            String hospitalId = requireHospitalManager(true).getString("_id");
            Document slot = requireDocument("timeSlots", slotId, "시간표를 찾을 수 없습니다.");
            ownedDoctor(slot.getString("doctorId"), hospitalId);
            return editSlot(slotId, capacity, closed);
        });
    }

    public List<Document> managedReservations(String date, String status) {
        String hospitalId = requireHospitalManager(false).getString("_id");
        Criteria criteria = Criteria.where("hospitalId").is(hospitalId);
        if (date != null && !date.isBlank()) {
            LocalDate day = parseDate(date);
            criteria.and("startAt").gte(atSeoul(day, LocalTime.MIN)).lt(atSeoul(day.plusDays(1), LocalTime.MIN));
        }
        if (status != null && !status.isBlank()) {
            if (!List.of("RESERVED", "CANCELED").contains(status)) throw new ApiException(400, "예약 상태를 확인해 주세요.");
            criteria.and("status").is(status);
        }
        List<Document> found = mongo.find(Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "startAt")), Document.class, "reservations");
        List<Document> result = new ArrayList<>();
        Map<String, Document> patients = new HashMap<>();
        List<String> patientIds = found.stream().map(item -> item.getString("userId")).distinct().toList();
        if (!patientIds.isEmpty()) {
            Query members = Query.query(Criteria.where("_id").in(patientIds));
            members.fields().include("name").include("phone");
            for (Document patient : mongo.find(members, Document.class, "users")) patients.put(patient.getString("_id"), patient);
        }
        for (Document reservation : found) {
            Document view = reservationView(reservation);
            Document patient = patients.get(reservation.getString("userId"));
            view.put("patientName", patient == null ? "탈퇴한 회원" : Objects.requireNonNullElse(patient.getString("name"), ""));
            view.put("patientPhone", patient == null ? "" : Objects.requireNonNullElse(patient.getString("phone"), ""));
            result.add(view);
        }
        return result;
    }

    public Document changeManagedReservation(String reservationId, String key, String slotId) {
        String hospitalId = requireHospitalManager(false).getString("_id");
        String actorId = CurrentUser.require().getId();
        return mutate(actorId, key, "HOSPITAL_CHANGE\n" + hospitalId + "\n" + reservationId + "\n" + slotId, () -> {
            requireHospitalManager(true);
            Document reservation = hospitalReservation(reservationId, hospitalId);
            if (!"RESERVED".equals(reservation.getString("status"))) throw new ApiException(409, "취소된 예약은 변경할 수 없습니다.");
            ensureFuture(reservation.getDate("startAt"));
            if (slotId.equals(reservation.getString("slotId"))) return reservationView(reservation);
            lockPatient(reservation.getString("userId"));
            Document nextSlot = acquireSlot(slotId);
            ownedDoctor(nextSlot.getString("doctorId"), hospitalId);
            Document doctor = bookingDoctor(nextSlot.getString("doctorId"));
            Document hospital = requireRegisteredHospital(hospitalId);
            Document before = reservationView(reservation);
            releaseSlot(reservation.getString("slotId"));
            setAppointment(reservation, nextSlot, doctor, hospital);
            reservation.put("updatedAt", new Date());
            mongo.save(reservation, "reservations");
            Document response = reservationView(reservation);
            event(reservationId, actorId, "HOSPITAL_CHANGE", before, response);
            return response;
        });
    }

    public Document cancelManagedReservation(String reservationId, String key) {
        String hospitalId = requireHospitalManager(false).getString("_id");
        String actorId = CurrentUser.require().getId();
        return mutate(actorId, key, "HOSPITAL_CANCEL\n" + hospitalId + "\n" + reservationId, () -> {
            requireHospitalManager(true);
            Document reservation = hospitalReservation(reservationId, hospitalId);
            if ("CANCELED".equals(reservation.getString("status"))) return reservationView(reservation);
            ensureFuture(reservation.getDate("startAt"));
            lockPatient(reservation.getString("userId"));
            Document before = reservationView(reservation);
            releaseSlot(reservation.getString("slotId"));
            reservation.put("status", "CANCELED"); reservation.put("updatedAt", new Date());
            mongo.save(reservation, "reservations");
            Document response = reservationView(reservation);
            event(reservationId, actorId, "HOSPITAL_CANCEL", before, response);
            return response;
        });
    }

    private Document requireHospitalManager(boolean lock) {
        UserPrincipal user = CurrentUser.require();
        if (!"HOSPITAL".equals(user.getRole()) || user.getHospitalId() == null || !user.isProfileComplete()) {
            throw new ApiException(403, "병원 계정으로 로그인해 주세요.");
        }
        Query query = Query.query(Criteria.where("_id").is(user.getHospitalId()).and("userId").is(user.getId()).and("status").is("ACTIVE"));
        Document registration = lock ? mongo.findAndModify(query, new Update().inc("managementVersion", 1),
                FindAndModifyOptions.options().returnNew(true), Document.class, "hospitalRegistrations")
                : mongo.findOne(query, Document.class, "hospitalRegistrations");
        if (registration == null) throw new ApiException(403, "관리 권한이 있는 병원 정보를 찾을 수 없습니다.");
        return registration;
    }

    private Document ownedDoctor(String id, String hospitalId) {
        Document doctor = mongo.findOne(Query.query(Criteria.where("_id").is(id).and("hospitalId").is(hospitalId).and("isVirtual").is(false)), Document.class, "doctors");
        if (doctor == null) throw new ApiException(404, "우리 병원에 등록된 의료진을 찾을 수 없습니다.");
        requireNotDeletedDoctor(doctor);
        return doctor;
    }

    private Document hospitalReservation(String id, String hospitalId) {
        Document reservation = mongo.findOne(Query.query(Criteria.where("_id").is(id).and("hospitalId").is(hospitalId)), Document.class, "reservations");
        if (reservation == null) throw new ApiException(404, "우리 병원의 예약을 찾을 수 없습니다.");
        return reservation;
    }

    private Document requireRegisteredHospital(String id) {
        Document hospital = HospitalCatalog.require(mongo, id);
        if (!mongo.exists(Query.query(Criteria.where("_id").is(id).and("status").is("ACTIVE")), "hospitalRegistrations")) {
            throw new ApiException(404, "메디픽에 등록된 병원을 찾을 수 없습니다.");
        }
        return hospital;
    }

    private void lockRegisteredHospital(String id) {
        if (mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and("status").is("ACTIVE")),
                new Update().inc("managementVersion", 1), HospitalAccountService.REGISTRATIONS).getMatchedCount() != 1) {
            throw new ApiException(404, "메디픽에 등록된 병원을 찾을 수 없습니다.");
        }
    }

    private void requireNotDeletedDoctor(Document doctor) {
        if (Boolean.TRUE.equals(doctor.getBoolean("deleted"))) throw new ApiException(404, "삭제된 의료진입니다. 의료진 목록을 다시 확인해 주세요.");
    }

    private void requireActiveDoctor(Document doctor) {
        if (Boolean.TRUE.equals(doctor.getBoolean("deleted")) || !Boolean.TRUE.equals(doctor.getBoolean("active")) || !Boolean.FALSE.equals(doctor.getBoolean("isVirtual"))) {
            throw new ApiException(409, "현재 예약을 받는 의료진이 아닙니다. 다른 의료진을 선택해 주세요.");
        }
    }

    private Document bookingDoctor(String id) {
        Document doctor = requireDocument("doctors", id, "의료진을 찾을 수 없습니다.");
        requireActiveDoctor(doctor);
        // Updating this document makes concurrent department/availability edits conflict with this booking.
        Document locked = mongo.findAndModify(Query.query(Criteria.where("_id").is(id).and("active").is(true).and("isVirtual").is(false).and("deleted").ne(true)),
                new Update().inc("bookingVersion", 1), FindAndModifyOptions.options().returnNew(true), Document.class, "doctors");
        if (locked == null) throw new ApiException(409, "의료진의 예약 설정이 변경되었습니다. 다시 확인해 주세요.");
        return locked;
    }

    private void requirePatientAccount(String userId) {
        Document user = mongo.findById(userId, Document.class, "users");
        if (user == null) throw new ApiException(401, "로그인이 필요합니다.");
        if ("HOSPITAL".equals(user.getString("role"))) throw new ApiException(403, "진료 예약은 개인 계정으로 로그인해 주세요.");
    }

    private void lockPatient(String userId) {
        if (mongo.updateFirst(Query.query(Criteria.where("_id").is(userId)), new Update().inc("bookingVersion", 1), "users").getMatchedCount() != 1) {
            throw new ApiException(409, "예약한 회원의 정보가 변경되었습니다. 예약 목록을 다시 확인해 주세요.");
        }
    }

    private void validateDoctorInput(String name, String departmentCode, String specialty) {
        if (name == null || name.isBlank() || name.length() > 50 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(400, "의료진 이름은 1~50자로 입력해 주세요.");
        }
        if (!Departments.contains(departmentCode)) throw new ApiException(400, "지원하는 진료과를 선택해 주세요.");
        if (specialty == null || specialty.length() > 100 || specialty.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(400, "전문 분야는 100자 이내로 입력해 주세요.");
        }
    }

    private void refreshManagedDepartments(String hospitalId) {
        Query query = Query.query(Criteria.where("hospitalId").is(hospitalId).and("active").is(true).and("isVirtual").is(false).and("deleted").ne(true));
        query.fields().include("departmentCode");
        Set<String> uniqueCodes = new TreeSet<>();
        for (Document doctor : mongo.find(query, Document.class, "doctors")) uniqueCodes.add(doctor.getString("departmentCode"));
        List<String> codes = new ArrayList<>(uniqueCodes);
        List<Document> departments = new ArrayList<>();
        for (String code : codes) departments.add(new Document("code", code).append("name", Departments.name(code)));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(hospitalId)), new Update().set("departments", departments).set("departmentCodes", codes), "hospitalRegistrations");
    }

    private Document mutate(String userId, String key, String body, Supplier<Document> operation) {
        if (key == null || key.isBlank() || key.length() > 128 || !key.matches("[A-Za-z0-9._:-]+")) {
            throw new ApiException(400, "요청 식별 키(Idempotency-Key)가 필요합니다.");
        }
        String hash = hash(body);
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            Document previous = previousResponse(userId, key, hash);
            if (previous != null) {
                return previous;
            }
            try {
                return transaction.execute(status -> {
                    // Serialize booking changes with account deletion on the user document.
                    long found = mongo.updateFirst(Query.query(Criteria.where("_id").is(userId)),
                            new Update().inc("bookingVersion", 1), "users").getMatchedCount();
                    if (found != 1) {
                        throw new ApiException(401, "회원 정보를 찾을 수 없습니다. 다시 로그인해 주세요.");
                    }
                    Document replay = previousResponse(userId, key, hash);
                    if (replay != null) {
                        return replay;
                    }
                    Document result = operation.get();
                    mongo.insert(new Document("_id", UUID.randomUUID().toString()).append("userId", userId)
                            .append("key", key).append("requestHash", hash).append("responseSnapshot", result)
                            .append("createdAt", new Date()), "requests");
                    return result;
                });
            } catch (RuntimeException error) {
                if (error instanceof ApiException) {
                    throw error;
                }
                if (!retryable(error)) {
                    throw error;
                }
                if (attempt == RETRIES - 1) {
                    Document committed = previousResponse(userId, key, hash);
                    if (committed != null) {
                        return committed;
                    }
                    throw new ApiException(409, "같은 시각의 예약이 있거나 다른 요청이 처리 중입니다. 예약 목록을 확인한 뒤 다시 시도해 주세요.");
                }
                pause(attempt);
            }
        }
        throw new ApiException(409, "예약 요청을 다시 확인해 주세요.");
    }

    private Document previousResponse(String userId, String key, String hash) {
        Document existing = mongo.findOne(Query.query(Criteria.where("userId").is(userId).and("key").is(key)),
                Document.class, "requests");
        if (existing == null) {
            return null;
        }
        if (!hash.equals(existing.getString("requestHash"))) {
            throw new ApiException(409, "같은 요청 키를 다른 내용에 사용할 수 없습니다. 새 요청으로 진행해 주세요.");
        }
        return existing.get("responseSnapshot", Document.class);
    }

    private Document acquireSlot(String slotId) {
        Document filter = new Document("_id", slotId).append("closed", false).append("isVirtual", false)
                .append("startAt", new Document("$gt", new Date()))
                .append("$expr", new Document("$lt", List.of("$reservedCount", "$capacity")));
        Document slot = mongo.findAndModify(new BasicQuery(filter), new Update().inc("reservedCount", 1),
                FindAndModifyOptions.options().returnNew(true), Document.class, "timeSlots");
        if (slot == null) {
            throw new ApiException(409, "선택한 시간이 마감되었거나 예약할 수 없습니다. 다른 시간을 선택해 주세요.");
        }
        return slot;
    }

    private void releaseSlot(String slotId) {
        long changed = mongo.updateFirst(Query.query(Criteria.where("_id").is(slotId).and("reservedCount").gt(0)),
                new Update().inc("reservedCount", -1), "timeSlots").getModifiedCount();
        if (changed != 1) {
            throw new ApiException(409, "예약 정원을 확인하지 못해 변경을 취소했습니다. 다시 시도해 주세요.");
        }
    }

    private void setAppointment(Document reservation, Document slot, Document doctor, Document hospital) {
        reservation.put("slotId", slot.getString("_id"));
        reservation.put("startAt", slot.getDate("startAt"));
        reservation.put("doctorId", doctor.getString("_id"));
        reservation.put("hospitalId", hospital.getString("_id"));
        reservation.put("hospitalName", hospital.getString("name"));
        reservation.put("departmentCode", doctor.getString("departmentCode"));
        reservation.put("departmentName", doctor.getString("departmentName"));
        reservation.put("doctorName", doctor.getString("name"));
        reservation.put("isVirtual", false);
        reservation.put("snapshot", new Document("hospitalName", hospital.getString("name"))
                .append("address", hospital.getString("address")).append("tel", hospital.getString("tel"))
                .append("doctorName", doctor.getString("name")).append("isVirtual", false)
                .append("source", hospital.getString("source")));
    }

    private Document ownedReservation(String id, String userId) {
        Document reservation = mongo.findOne(Query.query(Criteria.where("_id").is(id).and("userId").is(userId)),
                Document.class, "reservations");
        if (reservation == null) {
            throw new ApiException(404, "본인의 예약을 찾을 수 없습니다.");
        }
        return reservation;
    }

    private void event(String id, String actorId, String action, Document before, Document after) {
        mongo.insert(new Document("_id", UUID.randomUUID().toString()).append("reservationId", id)
                .append("actorId", actorId).append("action", action).append("before", before)
                .append("after", after).append("createdAt", new Date()), "reservationEvents");
    }

    private void addSlotUpsert(BulkOperations bulk, String doctorId, Date startAt, int capacity) {
        String id = stableId("slot", doctorId, startAt.toInstant().toString());
        bulk.upsert(Query.query(Criteria.where("_id").is(id)), slotInsert(doctorId, startAt, capacity));
    }

    private Update slotInsert(String doctorId, Date startAt, int capacity) {
        return new Update().setOnInsert("doctorId", doctorId).setOnInsert("startAt", startAt)
                .setOnInsert("capacity", capacity).setOnInsert("reservedCount", 0)
                .setOnInsert("closed", false).setOnInsert("isVirtual", false).setOnInsert("createdAt", new Date());
    }

    private <T> T runWithRetry(Supplier<T> operation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return operation.get();
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            try {
                return transaction.execute(status -> operation.get());
            } catch (RuntimeException error) {
                if (!retryable(error) || attempt == RETRIES - 1) {
                    throw error;
                }
                pause(attempt);
            }
        }
        throw new ApiException(409, "동시 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
    }

    private boolean retryable(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof DuplicateKeyException) {
                return true;
            }
            if (cause instanceof MongoException mongoError) {
                if (mongoError.hasErrorLabel("TransientTransactionError")
                        || mongoError.hasErrorLabel("UnknownTransactionCommitResult")
                        || mongoError.getCode() == 112 || mongoError.getCode() == 11000
                        || mongoError.getCode() == 251) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    private void pause(int attempt) {
        try {
            Thread.sleep(15L * (attempt + 1));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "요청이 중단되었습니다. 예약 목록을 확인해 주세요.");
        }
    }

    private Document requireDocument(String collection, String id, String message) {
        Document document = mongo.findById(id, Document.class, collection);
        if (document == null) {
            throw new ApiException(404, message);
        }
        return document;
    }

    private Document doctorView(Document doctor) {
        return new Document("id", doctor.getString("_id")).append("hospitalId", doctor.getString("hospitalId"))
                .append("departmentCode", doctor.getString("departmentCode"))
                .append("departmentName", doctor.getString("departmentName")).append("name", doctor.getString("name"))
                .append("specialty", doctor.getString("specialty")).append("active", doctor.getBoolean("active", false))
                .append("isVirtual", doctor.getBoolean("isVirtual", true));
    }

    private Document slotView(Document slot) {
        int capacity = ((Number) slot.get("capacity")).intValue();
        int reservedCount = ((Number) slot.get("reservedCount")).intValue();
        return new Document("id", slot.getString("_id")).append("doctorId", slot.getString("doctorId"))
                .append("startAt", slot.getDate("startAt").toInstant().toString())
                .append("capacity", capacity).append("reservedCount", reservedCount)
                .append("remaining", Math.max(0, capacity - reservedCount)).append("closed", slot.getBoolean("closed", false));
    }

    private Document reservationView(Document reservation) {
        return new Document("id", reservation.getString("_id")).append("status", reservation.getString("status"))
                .append("startAt", reservation.getDate("startAt").toInstant().toString())
                .append("slotId", reservation.getString("slotId")).append("doctorId", reservation.getString("doctorId"))
                .append("hospitalId", reservation.getString("hospitalId"))
                .append("hospitalName", reservation.getString("hospitalName"))
                .append("departmentCode", reservation.getString("departmentCode"))
                .append("departmentName", reservation.getString("departmentName"))
                .append("doctorName", reservation.getString("doctorName"))
                .append("createdAt", reservation.getDate("createdAt").toInstant().toString())
                .append("isVirtual", reservation.getBoolean("isVirtual", true));
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException error) {
            throw new ApiException(400, "날짜는 YYYY-MM-DD 형식으로 입력해 주세요.");
        }
    }

    private Date atSeoul(LocalDate date, LocalTime time) {
        return Date.from(ZonedDateTime.of(date, time, SEOUL).toInstant());
    }

    private void ensureFuture(Date startAt) {
        if (!startAt.after(new Date())) {
            throw new ApiException(409, "이미 지난 예약은 변경하거나 취소할 수 없습니다.");
        }
    }

    private String stableId(String... parts) {
        return UUID.nameUUIDFromBytes(String.join("\n", parts).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
