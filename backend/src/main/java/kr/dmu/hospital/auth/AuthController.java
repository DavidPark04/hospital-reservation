package kr.dmu.hospital.auth;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;
    public AuthController(AuthService service) { this.service = service; }
    @PostMapping("/register") public Map<String, Object> register(@RequestBody Map<String, Object> body) { return service.register(body); }
    @PostMapping("/login") public Map<String, Object> login(@RequestBody Map<String, Object> body) { return service.login(body); }
    @PatchMapping("/password") public Map<String, Object> changePassword(@RequestBody Map<String, Object> body) { return service.changePassword(body); }
    @PatchMapping("/email") public Map<String, Object> changeEmail(@RequestBody Map<String, Object> body) { return service.changeEmail(body); }
    @PostMapping("/password/reset") public Map<String, String> resetPassword(@RequestBody Map<String, Object> body) { return service.resetPassword(body); }
    @GetMapping("/me") public Map<String, Object> me() { return CurrentUser.require().toPublic(); }
    @PostMapping("/logout") public Map<String, String> logout() { service.logout(); return Map.of("message", "로그아웃되었습니다."); }
    @ExceptionHandler(MemberIdentityConflict.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> duplicateMember(MemberIdentityConflict error) { return error.toPublic(); }
}
