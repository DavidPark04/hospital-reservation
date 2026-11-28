package kr.dmu.hospital.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/verification")
public class ContactVerificationController {
    private final ContactVerificationService verification;
    public ContactVerificationController(ContactVerificationService verification) { this.verification = verification; }
    @GetMapping("/config") public Map<String, Object> config() { return verification.configuration(); }
    @PostMapping("/email/send") public Map<String, Object> sendEmail(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return verification.sendEmail(body, request.getRemoteAddr());
    }
    @PostMapping("/email/confirm") public Map<String, Object> confirmEmail(@RequestBody Map<String, Object> body) { return verification.confirmEmail(body); }
    @PostMapping("/phone/confirm") public Map<String, Object> confirmPhone(@RequestBody Map<String, Object> body) { return verification.confirmPhone(body); }
}
