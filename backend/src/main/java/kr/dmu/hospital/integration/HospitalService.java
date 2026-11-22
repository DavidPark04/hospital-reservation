package kr.dmu.hospital.integration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
    private static final String DETAIL_SOURCE = "https://www.data.go.kr/data/15001699/openapi.do";
    private final MongoTemplate mongo;
    private final HiraClient hira;
    private final TransactionTemplate transaction;
    @org.springframework.beans.factory.annotation.Value("${app.demo-enabled:false}")
    private boolean demoEnabled;

    public HospitalService(MongoTemplate mongo, HiraClient hira, MongoTransactionManager transactionManager) {
        this.mongo = mongo;
        this.hira = hira;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setTimeout(20);
    }

    public Map<String, Object> search(String query, String region, String department) {
        return search(query, region, department, false);
    }

    public Map<String, Object> search(String query, String region, String department, boolean exactName) {
        query = clean(query, 80);
        region = clean(region, 80);
        department = clean(department, 2);
        if (!department.isBlank() && !Departments.contains(department)) throw new ApiException(400, "지원하는 진료과를 선택해 주세요.");
        List<Criteria> filters = searchFilters(query, region, exactName);
        filters.add(Criteria.where("provider").is("HIRA"));
        filters.add(Criteria.where("active").is(true));
        if (!department.isBlank()) filters.add(Criteria.where("departmentCodes").is(department));
        Query selection = Query.query(new Criteria().andOperator(filters.toArray(Criteria[]::new)))
                .with(Sort.by("name", "_id")).limit(30);
        List<Document> rows = mongo.find(HospitalCatalog.publicFields(selection), Document.class, HospitalCatalog.COLLECTION);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Document row : rows) items.add(publicHospital(HospitalCatalog.combine(row, null), "CACHE"));
        if (items.isEmpty() && demoEnabled && !mongo.exists(new Query(), HospitalCatalog.COLLECTION)) {
            List<Criteria> sampleFilters = searchFilters(query, region, exactName);
            sampleFilters.add(Criteria.where("source").is("SAMPLE"));
            if (!department.isBlank()) sampleFilters.add(Criteria.where("departments.code").is(department));
            List<Document> samples = mongo.find(Query.query(new Criteria().andOperator(sampleFilters.toArray(Criteria[]::new)))
                    .with(Sort.by("name", "_id")).limit(30), Document.class, "hospitalDetails");
            for (Document sample : samples) items.add(publicHospital(sample, "SAMPLE"));
            if (!items.isEmpty()) return result(items, "SAMPLE", "전국 병원 정보가 아직 준비되지 않아 예약 체험용 샘플을 표시합니다.");
        }
        return result(items, "CACHE", items.isEmpty() ? "저장된 병원 목록에서 검색 결과를 찾지 못했어요. 지역이나 병원명을 바꿔 보세요."
                : "매일 갱신해 저장한 병원 기본 정보입니다.");
    }

    private List<Criteria> searchFilters(String name, String region, boolean exactName) {
        List<Criteria> filters = new ArrayList<>();
        if (!name.isBlank()) {
            Criteria field = Criteria.where("name");
            filters.add(exactName ? field.is(name) : field.regex(Pattern.quote(name), "i"));
        }
        if (!region.isBlank()) {
            for (String token : region.split("\\s+")) filters.add(Criteria.where("address").regex(regionPattern(token), "i"));
        }
        return filters;
    }

    private String regionPattern(String token) {
        List<String> alternatives = switch (token) {
            case "충북", "충청북도" -> List.of("충북", "충청북도");
            case "충남", "충청남도" -> List.of("충남", "충청남도");
            case "전북", "전라북도", "전북특별자치도" -> List.of("전북", "전라북도");
            case "전남", "전라남도" -> List.of("전남", "전라남도");
            case "경북", "경상북도" -> List.of("경북", "경상북도");
            case "경남", "경상남도" -> List.of("경남", "경상남도");
            case "강원", "강원도", "강원특별자치도" -> List.of("강원");
            case "제주", "제주도", "제주특별자치도" -> List.of("제주");
            default -> List.of(token);
        };
        return String.join("|", alternatives.stream().map(Pattern::quote).toList());
    }

    public Map<String, Object> detail(String id) {
        Document hospital = materialize(id);
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
            Map<String, Object> result = publicHospital(HospitalCatalog.require(mongo, id), complete ? "LIVE" : "CACHE");
            if (!complete) result.put("notice", "일부 상세정보를 새로 확인하지 못해 해당 항목은 이전 근거를 유지했습니다. 각 근거의 조회 시각을 확인해 주세요.");
            return result;
        } catch (HiraClient.UpstreamUnavailable | CompletionException unavailable) {
            Map<String, Object> result = publicHospital(HospitalCatalog.require(mongo, id), "CACHE");
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

    private Document materialize(String id) {
        Document hospital = HospitalCatalog.require(mongo, id);
        if ("SAMPLE".equals(hospital.getString("source"))) return hospital;
        if (mongo.exists(Query.query(Criteria.where("_id").is(id)), "hospitalDetails")) return hospital;
        return writeTogether(() -> {
            Document current = HospitalCatalog.require(mongo, id);
            Update insert = new Update();
            for (Map.Entry<String, Object> entry : current.entrySet()) {
                if (!"_id".equals(entry.getKey())) insert.setOnInsert(entry.getKey(), entry.getValue());
            }
            boolean created = mongo.upsert(Query.query(Criteria.where("_id").is(id)), insert, "hospitalDetails").getUpsertedId() != null;
            if (created && current.get("evidence") instanceof List<?> facts) {
                for (Object value : facts) if (value instanceof Document fact && "BASIC".equals(fact.getString("kind"))) mongo.save(fact, "hospitalEvidence");
            }
            return HospitalCatalog.require(mongo, id);
        });
    }

    private Document saveDetails(String id, List<Document> departments, List<Document> facts,
                                 Set<String> refreshedKinds, boolean complete, Date now) {
        return writeTogether(() -> {
            Document current = mongo.findById(id, Document.class, "hospitalDetails");
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
            mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), update, "hospitalDetails");
            List<Object> currentFactIds = new ArrayList<>();
            for (Document fact : facts) currentFactIds.add(fact.get("_id"));
            mongo.remove(Query.query(Criteria.where("hospitalId").is(id).and("kind").in(refreshedKinds)
                    .and("_id").nin(currentFactIds)), "hospitalEvidence");
            for (Document fact : facts) mongo.save(fact, "hospitalEvidence");
            return mongo.findById(id, Document.class, "hospitalDetails");
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
    private record DetailResult(List<Map<String, String>> rows, boolean successful) { }
}
