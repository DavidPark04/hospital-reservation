package kr.dmu.hospital.booking;

import com.mongodb.MongoException;
import kr.dmu.hospital.common.ApiException;
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
        Document hospital = HospitalCatalog.require(mongo, hospitalId);
        validateDepartment(hospital, departmentCode);
        Query selection = Query.query(Criteria.where("hospitalId").is(hospitalId)
                .and("departmentCode").is(departmentCode));
        List<Document> existing = mongo.find(selection, Document.class, "doctors");
        if (existing.isEmpty()) {
            runWithRetry(() -> {
                List<Document> assigned = mongo.find(selection, Document.class, "doctors");
                if (!assigned.isEmpty()) {
                    return null;
                }
                List<String> names = new ArrayList<>(List.of("김하늘", "이서준", "박서연", "정지우", "최도윤", "윤수민"));
                Collections.shuffle(names);
                String[] specialties = specialties(departmentCode);
                for (int i = 0; i < 3; i++) {
                    Document doctor = new Document("_id", stableId("doctor", hospitalId, departmentCode, String.valueOf(i)))
                            .append("hospitalId", hospitalId).append("departmentCode", departmentCode)
                            .append("departmentName", Departments.name(departmentCode))
                            .append("sampleCode", "sample-" + i).append("name", names.get(i))
                            .append("specialty", specialties[i]).append("isVirtual", true)
                            .append("createdAt", new Date());
                    mongo.insert(doctor, "doctors");
                }
                return null;
            });
        }
        List<Document> result = mongo.find(selection.with(Sort.by("sampleCode")), Document.class, "doctors");
        List<Document> views = new ArrayList<>();
        for (Document doctor : result) {
            views.add(doctorView(doctor));
        }
        return views;
    }

    public List<Document> slots(String doctorId, String dateText, boolean admin) {
        Document doctor = requireDocument("doctors", doctorId, "의료진을 찾을 수 없습니다.");
        if (!admin) HospitalCatalog.require(mongo, doctor.getString("hospitalId"));
        LocalDate date = parseDate(dateText);
        LocalDate today = LocalDate.now(SEOUL);
        if (!date.isBefore(today) && date.isBefore(today.plusDays(14))) {
            ensureSampleSlots(doctorId, today);
        }
        Date start = atSeoul(date, LocalTime.MIN);
        Date end = atSeoul(date.plusDays(1), LocalTime.MIN);
        Criteria criteria = Criteria.where("doctorId").is(doctorId).and("startAt").gte(start).lt(end);
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
        return mutate(userId, key, "CREATE\n" + slotId, () -> {
            Document slot = acquireSlot(slotId);
            Document doctor = requireDocument("doctors", slot.getString("doctorId"), "의료진을 찾을 수 없습니다.");
            Document hospital = HospitalCatalog.require(mongo, doctor.getString("hospitalId"));
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
            Document doctor = requireDocument("doctors", nextSlot.getString("doctorId"), "의료진을 찾을 수 없습니다.");
            Document hospital = HospitalCatalog.require(mongo, doctor.getString("hospitalId"));
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
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Member reservation cancellation requires a transaction.");
        }
        List<Document> reservations = mongo.find(Query.query(Criteria.where("userId").is(userId)
                .and("status").is("RESERVED")), Document.class, "reservations");
        int canceled = 0;
        for (Document reservation : reservations) {
            Document before = reservationView(reservation);
            Document changed = mongo.findAndModify(Query.query(Criteria.where("_id").is(reservation.getString("_id"))
                            .and("userId").is(userId).and("status").is("RESERVED")),
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
        Document doctor = requireDocument("doctors", doctorId, "의료진을 찾을 수 없습니다.");
        HospitalCatalog.require(mongo, doctor.getString("hospitalId"));
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
        if (capacity < 0 || capacity > 100) {
            throw new ApiException(400, "정원은 0~100명 사이로 입력해 주세요.");
        }
        requireDocument("timeSlots", slotId, "시간표를 찾을 수 없습니다.");
        Criteria criteria = Criteria.where("_id").is(slotId).and("reservedCount").lte(capacity);
        if (closed) {
            criteria = Criteria.where("_id").is(slotId).and("reservedCount").is(0);
        }
        Document changed = mongo.findAndModify(Query.query(criteria), new Update().set("capacity", capacity)
                .set("closed", closed), FindAndModifyOptions.options().returnNew(true), Document.class, "timeSlots");
        if (changed == null) {
            throw new ApiException(409, closed ? "예약이 있는 시간은 휴진 처리할 수 없습니다. 기존 예약을 먼저 확인해 주세요."
                    : "현재 예약 수보다 정원을 줄일 수 없습니다.");
        }
        return slotView(changed);
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
        Document filter = new Document("_id", slotId).append("closed", false)
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
        reservation.put("snapshot", new Document("hospitalName", hospital.getString("name"))
                .append("address", hospital.getString("address")).append("tel", hospital.getString("tel"))
                .append("doctorName", doctor.getString("name")).append("isVirtual", true)
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

    private void ensureSampleSlots(String doctorId, LocalDate today) {
        BulkOperations bulk = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, "timeSlots");
        for (int day = 0; day < 14; day++) {
            LocalDate date = today.plusDays(day);
            if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            for (int hour : List.of(9, 10, 11, 14, 15, 16)) {
                if (date.getDayOfWeek() == DayOfWeek.SATURDAY && hour >= 14) {
                    continue;
                }
                addSlotUpsert(bulk, doctorId, atSeoul(date, LocalTime.of(hour, 0)), 4);
                addSlotUpsert(bulk, doctorId, atSeoul(date, LocalTime.of(hour, 30)), 4);
            }
        }
        try {
            bulk.execute();
        } catch (RuntimeException error) {
            // Another first visitor can insert the same deterministic slots concurrently.
            // Re-running only setOnInsert operations preserves capacity and admin edits.
            if (!retryable(error)) {
                throw error;
            }
            ensureSampleSlotsOnce(doctorId, today);
        }
    }

    private void ensureSampleSlotsOnce(String doctorId, LocalDate today) {
        for (int day = 0; day < 14; day++) {
            LocalDate date = today.plusDays(day);
            if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            for (int hour : List.of(9, 10, 11, 14, 15, 16)) {
                if (date.getDayOfWeek() == DayOfWeek.SATURDAY && hour >= 14) {
                    continue;
                }
                for (int minute : List.of(0, 30)) {
                    Date start = atSeoul(date, LocalTime.of(hour, minute));
                    String id = stableId("slot", doctorId, start.toInstant().toString());
                    try {
                        mongo.upsert(Query.query(Criteria.where("_id").is(id)), slotInsert(doctorId, start, 4), "timeSlots");
                    } catch (DuplicateKeyException ignored) {
                        // A concurrent request already inserted this exact slot.
                    }
                }
            }
        }
    }

    private void addSlotUpsert(BulkOperations bulk, String doctorId, Date startAt, int capacity) {
        String id = stableId("slot", doctorId, startAt.toInstant().toString());
        bulk.upsert(Query.query(Criteria.where("_id").is(id)), slotInsert(doctorId, startAt, capacity));
    }

    private Update slotInsert(String doctorId, Date startAt, int capacity) {
        return new Update().setOnInsert("doctorId", doctorId).setOnInsert("startAt", startAt)
                .setOnInsert("capacity", capacity).setOnInsert("reservedCount", 0)
                .setOnInsert("closed", false).setOnInsert("isVirtual", true).setOnInsert("createdAt", new Date());
    }

    private <T> T runWithRetry(Supplier<T> operation) {
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

    private void validateDepartment(Document hospital, String code) {
        if (!Departments.contains(code)) {
            throw new ApiException(400, "지원하는 진료과를 선택해 주세요.");
        }
        Object raw = hospital.get("departments");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && code.equals(map.get("code"))) {
                    return;
                }
            }
            throw new ApiException(400, "이 병원에서 확인된 진료과를 선택해 주세요.");
        }
        String declared = hospital.getString("departmentCode");
        if (declared != null && !declared.isBlank() && !declared.equals(code)) {
            throw new ApiException(400, "이 병원의 진료과와 선택한 진료과가 다릅니다.");
        }
    }

    private String[] specialties(String code) {
        if ("13".equals(code)) {
            return new String[]{"코·부비동 상담", "귀·어지럼 상담", "목·음성 상담"};
        }
        if ("05".equals(code)) {
            return new String[]{"관절 상담", "척추·자세 상담", "운동 손상 상담"};
        }
        if ("14".equals(code)) {
            return new String[]{"일반 피부 상담", "피부 염증 상담", "피부 관리 상담"};
        }
        return new String[]{Departments.name(code) + " 일반 상담", Departments.name(code) + " 건강 상담", Departments.name(code) + " 경과 상담"};
    }

    private Document doctorView(Document doctor) {
        return new Document("id", doctor.getString("_id")).append("hospitalId", doctor.getString("hospitalId"))
                .append("departmentCode", doctor.getString("departmentCode"))
                .append("departmentName", doctor.getString("departmentName")).append("name", doctor.getString("name"))
                .append("specialty", doctor.getString("specialty")).append("isVirtual", true);
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
                .append("createdAt", reservation.getDate("createdAt").toInstant().toString()).append("isVirtual", true);
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
