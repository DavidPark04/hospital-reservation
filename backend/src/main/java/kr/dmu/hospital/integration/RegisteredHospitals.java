package kr.dmu.hospital.integration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** The full HIRA catalogue is retained; only participating hospitals are exposed to patients. */
public final class RegisteredHospitals {
    public static final String COLLECTION = "hospitalRegistrations";
    private RegisteredHospitals() { }

    public static Map<String, Document> active(MongoTemplate mongo, String department) {
        Criteria criteria = Criteria.where("status").is("ACTIVE");
        if (department != null && !department.isBlank()) criteria.and("departmentCodes").is(department);
        Query query = Query.query(criteria);
        query.fields().include("_id").include("departments").include("departmentCodes");
        Map<String, Document> result = new LinkedHashMap<>();
        for (Document registration : mongo.find(query, Document.class, COLLECTION)) {
            result.put(registration.getString("_id"), registration);
        }
        return result;
    }

    public static Document require(MongoTemplate mongo, String hospitalId) {
        Document registration = mongo.findOne(Query.query(Criteria.where("_id").is(hospitalId)
                .and("status").is("ACTIVE")), Document.class, COLLECTION);
        if (registration == null) throw new ApiException(404, "메디픽에 등록된 병원이 아닙니다.");
        return registration;
    }

    public static Document withManagedDepartments(Document hospital, Document registration) {
        Document result = new Document(hospital);
        List<Document> departments = new ArrayList<>();
        if (registration != null && registration.get("departments") instanceof List<?> values) {
            for (Object value : values) if (value instanceof Document department) {
                departments.add(new Document("code", department.getString("code")).append("name", department.getString("name")));
            }
        }
        result.put("departments", departments);
        result.put("departmentCodes", departments.stream().map(value -> value.getString("code")).toList());
        result.remove("departmentCode");
        result.remove("departmentName");
        if (!departments.isEmpty()) {
            result.put("departmentCode", departments.getFirst().getString("code"));
            result.put("departmentName", departments.getFirst().getString("name"));
        }
        return result;
    }
}
