package kr.dmu.hospital.integration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** The daily catalog owns basic facts; hospitals retains selected-hospital detail caches. */
public final class HospitalCatalog {
    public static final String COLLECTION = "hospitalBasicCatalog";
    private static final String SOURCE = "https://www.data.go.kr/data/15001698/openapi.do";
    private static final List<String> BASIC_FIELDS = List.of("provider", "externalId", "name", "address", "tel",
            "location", "latitude", "longitude", "basicFetchedAt");

    private HospitalCatalog() { }

    public static Query publicFields(Query query) {
        query.fields().exclude("apiFields").exclude("basicSourceHash").exclude("catalogHash");
        return query;
    }

    public static Document find(MongoTemplate mongo, String id) {
        return mongo.findOne(publicFields(Query.query(Criteria.where("_id").is(id))), Document.class, COLLECTION);
    }

    public static Document require(MongoTemplate mongo, String id) {
        Document basic = find(mongo, id);
        Document cached = mongo.findById(id, Document.class, "hospitals");
        if (basic == null) {
            if (cached != null && "SAMPLE".equals(cached.getString("source"))) return cached;
            throw new ApiException(404, "저장된 병원 정보를 찾을 수 없습니다. 다시 검색해 주세요.");
        }
        if (Boolean.FALSE.equals(basic.getBoolean("active"))) {
            throw new ApiException(410, "현재 병원 목록에서 제공되지 않는 병원입니다. 다른 병원을 선택해 주세요.");
        }
        return combine(basic, cached);
    }

    public static Document combine(Document basic, Document cached) {
        Document result = cached == null ? new Document() : new Document(cached);
        result.put("_id", basic.get("_id"));
        for (String field : BASIC_FIELDS) {
            if (basic.containsKey(field)) result.put(field, basic.get(field));
            else result.remove(field);
        }
        result.put("source", "LIVE");
        List<Document> departments = departments(basic);
        if (cached == null || !(cached.get("detailFetchedAt") instanceof Date)) result.put("departments", departments);
        if (result.get("departments") instanceof List<?> existing && !existing.isEmpty()) {
            String primary = result.getString("departmentCode");
            Document selected = null;
            for (Object value : existing) {
                if (value instanceof Document department) {
                    if (selected == null) selected = department;
                    if (Objects.equals(primary, department.getString("code"))) { selected = department; break; }
                }
            }
            if (selected != null) {
                result.put("departmentCode", selected.getString("code"));
                result.put("departmentName", selected.getString("name"));
            }
        } else {
            result.remove("departmentCode");
            result.remove("departmentName");
        }
        Date basicTime = basic.get("basicFetchedAt") instanceof Date date ? date
                : basic.get("fetchedAt") instanceof Date date ? date : null;
        Date latest = basicTime;
        if (cached != null && cached.get("detailFetchedAt") instanceof Date detailTime
                && (latest == null || detailTime.after(latest))) latest = detailTime;
        if (latest != null) result.put("fetchedAt", latest);
        List<Document> facts = new ArrayList<>();
        if (cached != null && cached.get("evidence") instanceof List<?> existing) {
            for (Object value : existing) if (value instanceof Document fact && !"BASIC".equals(fact.getString("kind"))) facts.add(fact);
        }
        facts.add(basicEvidence(basic.getString("_id"), basicTime));
        result.put("evidence", facts);
        return result;
    }

    private static List<Document> departments(Document basic) {
        List<Document> result = new ArrayList<>();
        if (basic.get("departments") instanceof List<?> values) {
            for (Object value : values) if (value instanceof Document department) result.add(new Document(department));
        } else if (basic.get("departmentCodes") instanceof List<?> codes) {
            for (Object value : codes) if (value instanceof String code && Departments.contains(code)) {
                result.add(new Document("code", code).append("name", Departments.name(code)));
            }
        }
        return result;
    }

    public static Document basicEvidence(String id, Date time) {
        String value = "병원명·주소·연락처";
        String evidenceId = UUID.nameUUIDFromBytes((id + "BASIC" + value).getBytes(StandardCharsets.UTF_8)).toString();
        return new Document("_id", evidenceId).append("hospitalId", id).append("kind", "BASIC")
                .append("label", "공공 병원 정보").append("value", value).append("sourceUrl", SOURCE)
                .append("basePeriod", null).append("fetchedAt", time);
    }
}
