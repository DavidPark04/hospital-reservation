package kr.dmu.hospital.integration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.mongodb.MongoException;
import java.util.function.Supplier;
import kr.dmu.hospital.common.ApiException;

@Service
public class HospitalService {
    private static final String BASIC_SOURCE = "https://www.data.go.kr/data/15001698/openapi.do";
    private static final String DETAIL_SOURCE = "https://www.data.go.kr/data/15001699/openapi.do";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String CACHE_MESSAGE = "최근 조회해 저장한 병원 중 검색 조건에 맞는 결과입니다. 전체 검색 결과와 다를 수 있습니다.";
    private final MongoTemplate mongo;
    private final HiraClient hira;
    private final TransactionTemplate transaction;
    private final Map<String, SearchCache> searchCache = new LinkedHashMap<>();

    public HospitalService(MongoTemplate mongo, HiraClient hira, MongoTransactionManager transactionManager) {
        this.mongo = mongo;
        this.hira = hira;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setTimeout(20);
    }

    public Map<String, Object> search(String query, String region, String department) {
        query = clean(query, 80);
        region = clean(region, 80);
        department = clean(department, 2);
        if (!department.isBlank() && !Departments.contains(department)) throw new ApiException(400, "지원하는 진료과를 선택해 주세요.");
        Instant lookupTime = Instant.now();
        String cacheKey = query + "|" + region + "|" + department + "|" + lookupTime.atZone(SEOUL).toLocalDate();
        Date freshSince = recentCacheCutoff(lookupTime);
        SearchCache cached;
        synchronized (searchCache) { cached = searchCache.get(cacheKey); }
        if (cached != null && cached.createdAt().isAfter(freshSince.toInstant())) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (String id : cached.ids()) {
                Document row = mongo.findById(id, Document.class, "hospitals");
                if (row != null && row.get("basicFetchedAt") instanceof Date basicTime && !basicTime.before(freshSince)) {
                    items.add(publicHospital(row, "CACHE"));
                }
            }
            if (!items.isEmpty() || cached.ids().isEmpty()) return result(items, "CACHE", CACHE_MESSAGE);
        }
        List<Document> recent = findCached(query, region, department, false, freshSince);
        if (!recent.isEmpty()) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Document row : recent) items.add(publicHospital(row, "CACHE"));
            return result(items, "CACHE", CACHE_MESSAGE);
        }
        try {
            List<Map<String, String>> rows = hira.search(query, region, department);
            List<Map<String, Object>> hospitals = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (Map<String, String> row : rows) {
                if (row.getOrDefault("ykiho", "").isBlank() || row.getOrDefault("yadmNm", "").isBlank()) continue;
                Document saved = saveBasic(row, department);
                hospitals.add(publicHospital(saved, "LIVE"));
                ids.add(saved.getString("_id"));
            }
            synchronized (searchCache) {
                if (searchCache.size() >= 200) searchCache.remove(searchCache.keySet().iterator().next());
                searchCache.put(cacheKey, new SearchCache(ids, Instant.now()));
            }
            return result(hospitals, "LIVE", hospitals.isEmpty() ? "검색 결과가 없습니다. 지역이나 병원명을 바꿔 보세요." : "건강보험심사평가원에서 조회한 병원 정보입니다.");
        } catch (HiraClient.UpstreamUnavailable unavailable) {
            List<Document> rows = findCached(query, region, department, false, null);
            String source = "CACHE";
            String message = "공공 API 연결이 원활하지 않아 마지막으로 확인한 병원 정보를 표시합니다. 조회 시점을 확인해 주세요.";
            if (rows.isEmpty()) {
                rows = findCached(query, region, department, true, null);
                source = "SAMPLE";
                message = "공공 API에 연결하지 못했습니다. 아래는 예약 흐름 체험용 샘플 병원이며 실제 병원 추천이 아닙니다.";
            }
            List<Map<String, Object>> hospitals = new ArrayList<>();
            for (Document row : rows) hospitals.add(publicHospital(row, source));
            return result(hospitals, source, message);
        }
    }

    public Map<String, Object> detail(String id) {
        Document hospital = mongo.findById(id, Document.class, "hospitals");
        if (hospital == null) throw new ApiException(404, "병원 정보를 찾을 수 없습니다. 다시 검색해 주세요.");
        if ("SAMPLE".equals(hospital.getString("source"))) return publicHospital(hospital, "SAMPLE");
        Object detailTime = hospital.get("detailFetchedAt");
        if (hospital.getBoolean("detailComplete", true) && detailTime instanceof Date date
                && date.toInstant().isAfter(Instant.now().minusSeconds(3600))) {
            return publicHospital(hospital, "CACHE");
        }
        if (!hira.detailsConfigured()) {
            Map<String, Object> result = publicHospital(hospital, "CACHE");
            result.put("notice", "상세정보 API가 설정되지 않았습니다. 기본 정보만 표시합니다.");
            return result;
        }
        try {
            String ykiho = hospital.getString("externalId");
            CompletableFuture<List<Map<String, String>>> departmentRows = CompletableFuture.supplyAsync(() -> hira.detail("getDgsbjtInfo2.8", ykiho));
            CompletableFuture<DetailResult> specialRows = CompletableFuture.supplyAsync(() -> optionalDetails("getSpclDiagInfo2.8", ykiho));
            CompletableFuture<DetailResult> designationRows = CompletableFuture.supplyAsync(() -> optionalDetails("getSpclHospAsgFldList2.8", ykiho));
            List<Map<String, String>> rows = departmentRows.join();
            List<Document> departments = new ArrayList<>();
            List<Document> evidence = new ArrayList<>();
            Date now = new Date();
            for (Map<String, String> row : rows) {
                String code = first(row, "dgsbjtCd");
                String name = first(row, "dgsbjtCdNm", "dgsbjtNm");
                if (!code.isBlank() && !name.isBlank()) {
                    departments.add(new Document("code", code).append("name", name));
                    evidence.add(evidence(id, "DEPARTMENT", "진료과목", name, DETAIL_SOURCE, now));
                }
            }
            DetailResult special = specialRows.join();
            DetailResult designation = designationRows.join();
            addOptionalEvidence(id, special.rows(), "SPECIAL", "특수진료", evidence, now);
            addOptionalEvidence(id, designation.rows(), "DESIGNATION", "전문병원 지정 분야", evidence, now);
            Set<String> refreshedKinds = new HashSet<>(Set.of("DEPARTMENT"));
            if (special.successful()) refreshedKinds.add("SPECIAL");
            if (designation.successful()) refreshedKinds.add("DESIGNATION");
            Document saved = saveDetails(id, departments, evidence, refreshedKinds,
                    special.successful() && designation.successful(), now);
            boolean complete = saved.getBoolean("detailComplete", true);
            Map<String, Object> result = publicHospital(saved, complete ? "LIVE" : "CACHE");
            if (!complete) result.put("notice", "일부 상세정보를 새로 확인하지 못해 해당 항목은 이전 근거를 유지했습니다. 각 근거의 조회 시각을 확인해 주세요.");
            return result;
        } catch (HiraClient.UpstreamUnavailable | CompletionException unavailable) {
            Map<String, Object> result = publicHospital(hospital, "CACHE");
            result.put("notice", "상세정보를 새로 확인하지 못했습니다. 마지막 조회 정보만 표시합니다.");
            return result;
        }
    }

    private DetailResult optionalDetails(String endpoint, String ykiho) {
        try {
            return new DetailResult(hira.detail(endpoint, ykiho), true);
        } catch (HiraClient.UpstreamUnavailable ignored) {
            // An unavailable designation is unknown, never a negative evaluation.
            return new DetailResult(List.of(), false);
        }
    }

    private void addOptionalEvidence(String id, List<Map<String, String>> rows, String kind, String label, List<Document> evidence, Date now) {
        for (Map<String, String> row : rows) {
            String value = first(row, "srchCdNm", "spclCdNm", "spclDiagNm", "spclHospAsgFldNm", "spclHospAsgFldCdNm", "dgsbjtCdNm");
            if (!value.isBlank()) evidence.add(evidence(id, kind, label, value, DETAIL_SOURCE, now));
        }
    }

    private Document saveBasic(Map<String, String> row, String department) {
        String externalId = row.get("ykiho");
        String id = UUID.nameUUIDFromBytes(("HIRA:" + externalId).getBytes(StandardCharsets.UTF_8)).toString();
        Date now = new Date();
        return writeTogether(() -> {
            Document current = mongo.findById(id, Document.class, "hospitals");
            if (current != null && current.get("basicFetchedAt") instanceof Date previous && previous.after(now)) return current;
            Document baseEvidence = evidence(id, "BASIC", "공공 병원 정보", "병원명·주소·연락처", BASIC_SOURCE, now);
            Double longitude = coordinate(row.get("XPos"), 180);
            Double latitude = coordinate(row.get("YPos"), 90);
            Document location = longitude != null && latitude != null
                    ? new Document("latitude", latitude).append("longitude", longitude) : null;
            Update update = new Update().set("provider", "HIRA").set("externalId", externalId)
                    .set("name", row.get("yadmNm")).set("address", row.getOrDefault("addr", ""))
                    .set("tel", row.getOrDefault("telno", "")).set("source", "LIVE")
                    .set("basicFetchedAt", now).set("fetchedAt", latestFetchedAt(current, now)).set("location", location)
                    .set("evidence", mergeEvidence(current, List.of(baseEvidence), Set.of("BASIC")));
            if (location != null) update.set("longitude", longitude).set("latitude", latitude);
            else update.unset("longitude").unset("latitude");
            if (!department.isBlank()) {
                update.set("departmentCode", department).set("departmentName", Departments.name(department));
                update.addToSet("departments", new Document("code", department).append("name", Departments.name(department)));
            }
            mongo.upsert(Query.query(Criteria.where("_id").is(id)), update, "hospitals");
            mongo.save(baseEvidence, "hospitalEvidence");
            return mongo.findById(id, Document.class, "hospitals");
        });
    }

    private Document saveDetails(String id, List<Document> departments, List<Document> facts,
                                 Set<String> refreshedKinds, boolean complete, Date now) {
        return writeTogether(() -> {
            Document current = mongo.findById(id, Document.class, "hospitals");
            if (current == null) throw new ApiException(404, "병원 정보를 찾을 수 없습니다. 다시 검색해 주세요.");
            if (current.get("detailFetchedAt") instanceof Date previous && previous.after(now)) return current;
            // The timestamp orders concurrent responses; only complete results get the one-hour cache shortcut.
            Update update = new Update().set("detailFetchedAt", now).set("detailComplete", complete)
                    .set("fetchedAt", latestFetchedAt(current, now))
                    .set("evidence", mergeEvidence(current, facts, refreshedKinds)).set("departments", departments);
            Document primaryDepartment = null;
            for (Document department : departments) {
                if (department.getString("code").equals(current.getString("departmentCode"))) primaryDepartment = department;
            }
            if (primaryDepartment == null) update.unset("departmentCode").unset("departmentName");
            else update.set("departmentName", primaryDepartment.getString("name"));
            mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), update, "hospitals");
            List<Object> currentFactIds = new ArrayList<>();
            for (Document fact : facts) currentFactIds.add(fact.get("_id"));
            mongo.remove(Query.query(Criteria.where("hospitalId").is(id).and("kind").in(refreshedKinds)
                    .and("_id").nin(currentFactIds)), "hospitalEvidence");
            for (Document fact : facts) mongo.save(fact, "hospitalEvidence");
            return mongo.findById(id, Document.class, "hospitals");
        });
    }

    private List<Document> mergeEvidence(Document current, List<Document> replacement, Set<String> replacedKinds) {
        List<Document> merged = new ArrayList<>();
        if (current != null && current.get("evidence") instanceof List<?> existing) {
            for (Object value : existing) {
                if (value instanceof Document fact && !replacedKinds.contains(fact.getString("kind"))) merged.add(fact);
            }
        }
        merged.addAll(replacement);
        return merged;
    }

    private static Date latestFetchedAt(Document current, Date now) {
        return current != null && current.get("fetchedAt") instanceof Date previous && previous.after(now) ? previous : now;
    }

    static Date recentCacheCutoff(Instant now) {
        Instant startOfDay = now.atZone(SEOUL).toLocalDate().atStartOfDay(SEOUL).toInstant();
        Instant fiveMinutesAgo = now.minusSeconds(300);
        return Date.from(startOfDay.isAfter(fiveMinutesAgo) ? startOfDay : fiveMinutesAgo);
    }

    private List<Document> findCached(String name, String region, String department, boolean sample, Date freshSince) {
        List<Criteria> filters = new ArrayList<>();
        filters.add(sample ? Criteria.where("source").is("SAMPLE") : Criteria.where("source").ne("SAMPLE"));
        if (freshSince != null) {
            filters.add(Criteria.where("provider").is("HIRA"));
            filters.add(Criteria.where("basicFetchedAt").gte(freshSince));
        }
        if (!name.isBlank()) filters.add(Criteria.where("name").regex(Pattern.quote(name), "i"));
        if (!region.isBlank()) {
            String[] tokens = region.trim().split("\\s+");
            for (String token : tokens) filters.add(Criteria.where("address").regex(Pattern.quote(token), "i"));
        }
        if (!department.isBlank()) filters.add(new Criteria().orOperator(Criteria.where("departmentCode").is(department), Criteria.where("departments.code").is(department)));
        Query query = Query.query(new Criteria().andOperator(filters.toArray(Criteria[]::new)));
        return mongo.find(query.with(Sort.by(Sort.Direction.DESC, freshSince == null ? "fetchedAt" : "basicFetchedAt")).limit(30), Document.class, "hospitals");
    }

    public static Map<String, Object> publicHospital(Document row, String source) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.getString("_id"));
        for (String field : List.of("name", "address", "tel", "departmentCode", "departmentName", "departments", "evidence", "fetchedAt", "location", "latitude", "longitude")) {
            if (row.containsKey(field)) result.put(field, jsonValue(row.get(field)));
        }
        result.putIfAbsent("departments", List.of());
        result.putIfAbsent("evidence", List.of());
        result.put("source", source);
        return result;
    }

    private static Object jsonValue(Object value) {
        if (value instanceof Date date) return date.toInstant().toString();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!key.equals("_id") && !key.equals("hospitalId")) result.put(key, jsonValue(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(HospitalService::jsonValue).toList();
        return value;
    }

    private static Map<String, Object> result(List<Map<String, Object>> items, String source, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("source", source);
        result.put("message", message);
        if (!items.isEmpty()) result.put("fetchedAt", items.getFirst().get("fetchedAt"));
        return result;
    }
    private static Document evidence(String id, String kind, String label, String value, String source, Date now) {
        String evidenceId = UUID.nameUUIDFromBytes((id + kind + value).getBytes(StandardCharsets.UTF_8)).toString();
        return new Document("_id", evidenceId).append("hospitalId", id).append("kind", kind).append("label", label)
                .append("value", value).append("sourceUrl", source).append("basePeriod", null).append("fetchedAt", now);
    }
    private static Double coordinate(String raw, double limit) {
        try {
            if (raw != null && !raw.isBlank()) {
                double value = Double.parseDouble(raw);
                if (Double.isFinite(value) && Math.abs(value) <= limit) return value;
            }
        } catch (NumberFormatException ignored) { }
        return null;
    }

    private <T> T writeTogether(Supplier<T> operation) {
        for (int attempt = 0; attempt < 8; attempt++) {
            try {
                return transaction.execute(status -> operation.get());
            } catch (RuntimeException error) {
                boolean retry = false;
                for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                    if (cause instanceof DuplicateKeyException) retry = true;
                    if (cause instanceof MongoException mongoError && (mongoError.hasErrorLabel("TransientTransactionError")
                            || mongoError.hasErrorLabel("UnknownTransactionCommitResult") || mongoError.getCode() == 112)) retry = true;
                }
                if (!retry || attempt == 7) throw error;
                try { Thread.sleep(20L * (attempt + 1)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw error; }
            }
        }
        throw new IllegalStateException("병원 캐시를 저장하지 못했습니다.");
    }
    private static String first(Map<String, String> row, String... keys) {
        for (String key : keys) if (row.get(key) != null && !row.get(key).isBlank()) return row.get(key);
        return "";
    }
    private static String clean(String value, int max) {
        if (value == null) return "";
        if (value.length() > max) throw new ApiException(400, "검색 조건이 너무 깁니다.");
        return value.trim();
    }
    private record SearchCache(List<String> ids, Instant createdAt) { }
    private record DetailResult(List<Map<String, String>> rows, boolean successful) { }
}
