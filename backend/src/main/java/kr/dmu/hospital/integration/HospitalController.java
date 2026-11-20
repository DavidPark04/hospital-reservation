package kr.dmu.hospital.integration;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HospitalController {
    private final HospitalService hospitals;
    public HospitalController(HospitalService hospitals) { this.hospitals = hospitals; }
    @GetMapping("/api/hospitals")
    public Map<String, Object> search(@RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String region, @RequestParam(defaultValue = "") String department) {
        return hospitals.search(query, region, department);
    }
    @GetMapping("/api/hospitals/{id}")
    public Map<String, Object> detail(@PathVariable String id) { return hospitals.detail(id); }
}
