package kr.dmu.hospital.common;

import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetaController {
    private final MongoTemplate mongo;
    private final String model;
    private final String openaiKey;
    private final String hospitalKey;
    private final String detailKey;
    private volatile boolean ready;
    public MetaController(MongoTemplate mongo, @Value("${app.openai.model}") String model, @Value("${app.openai.api-key}") String openaiKey,
            @Value("${app.hira.hospital-key}") String hospitalKey, @Value("${app.hira.detail-key}") String detailKey) {
        this.mongo = mongo; this.model = model; this.openaiKey = openaiKey; this.hospitalKey = hospitalKey; this.detailKey = detailKey;
    }
    @GetMapping("/api/meta") public Map<String, Object> meta() {
        return Map.of("serviceName", "온유 · 병원예약도우미", "model", model, "virtualReservation", true,
            "departments", List.of(dept("01", "내과"), dept("13", "이비인후과"), dept("05", "정형외과"), dept("14", "피부과"), dept("11", "소아청소년과"), dept("23", "가정의학과"), dept("12", "안과"), dept("10", "산부인과")),
            "integrations", Map.of("openai", !openaiKey.isBlank(), "hira", !hospitalKey.isBlank() && !detailKey.isBlank()));
    }
    @GetMapping("/api/health") public Map<String, Object> health() {
        if (!ready) throw new ApiException(503, "데이터베이스 초기화를 진행하고 있습니다.");
        mongo.executeCommand(new Document("ping", 1));
        return Map.of("status", "UP", "database", "MongoDB", "model", model);
    }
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() { ready = true; }
    private Map<String, String> dept(String code, String name) { return Map.of("code", code, "name", name); }
}
