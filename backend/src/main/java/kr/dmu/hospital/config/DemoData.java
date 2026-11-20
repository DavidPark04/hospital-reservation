package kr.dmu.hospital.config;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class DemoData implements ApplicationRunner {
    private final MongoTemplate mongo;
    private final boolean enabled;
    public DemoData(MongoTemplate mongo, @Value("${app.demo-enabled}") boolean enabled) {
        this.mongo = mongo; this.enabled = enabled;
    }
    @Override public void run(ApplicationArguments args) {
        if (!enabled) return;
        addHospital("sample-ent", "영통 맑은숨 이비인후과 (샘플)", "경기도 수원시 영통구 · 시연용 주소", "13", "이비인후과");
        addHospital("sample-internal", "영통 온유내과 (샘플)", "경기도 수원시 영통구 · 시연용 주소", "01", "내과");
        addHospital("sample-ortho", "수원 바른걸음 정형외과 (샘플)", "경기도 수원시 팔달구 · 시연용 주소", "05", "정형외과");
        addHospital("sample-skin", "영통 오늘피부과 (샘플)", "경기도 수원시 영통구 · 시연용 주소", "14", "피부과");
        addHospital("sample-family", "서울 함께가정의학과 (샘플)", "서울특별시 강남구 · 시연용 주소", "23", "가정의학과");
        addHospital("sample-child", "수원 아이봄소아청소년과 (샘플)", "경기도 수원시 영통구 · 시연용 주소", "11", "소아청소년과");
    }
    private void addHospital(String id, String name, String address, String code, String department) {
        if (mongo.findById(id, Document.class, "hospitals") != null) return;
        Date now = new Date();
        Document evidence = new Document("kind", "SAMPLE").append("label", "시연용 진료과")
            .append("value", department + " 예약 흐름을 확인하기 위한 가상 병원입니다.")
                .append("sourceUrl", "").append("basePeriod", null).append("fetchedAt", now);
        mongo.insert(new Document("_id", id).append("provider", "SAMPLE").append("externalId", id)
                .append("name", name).append("address", address).append("tel", "").append("location", null).append("basicFetchedAt", now)
            .append("departmentCode", code).append("departmentName", department)
            .append("departments", List.of(new Document("code", code).append("name", department)))
            .append("evidence", List.of(evidence)).append("source", "SAMPLE").append("fetchedAt", now), "hospitals");
        mongo.insert(new Document(evidence).append("_id", UUID.randomUUID().toString()).append("hospitalId", id), "hospitalEvidence");
    }
}
