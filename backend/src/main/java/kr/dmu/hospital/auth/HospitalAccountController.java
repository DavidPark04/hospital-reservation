package kr.dmu.hospital.auth;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/hospital")
public class HospitalAccountController {
    private final HospitalAccountService service;
    public HospitalAccountController(HospitalAccountService service) { this.service = service; }
    @PostMapping("/lookup") public Map<String, Object> lookup(@RequestBody Map<String, Object> body) { return service.lookup(body); }
    @PostMapping("/register") public Map<String, Object> register(@RequestBody Map<String, Object> body) { return service.register(body); }
    @PostMapping("/login") public Map<String, Object> login(@RequestBody Map<String, Object> body) { return service.login(body); }
}
