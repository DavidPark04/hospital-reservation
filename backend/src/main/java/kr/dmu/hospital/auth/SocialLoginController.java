package kr.dmu.hospital.auth;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.dmu.hospital.common.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/social")
public class SocialLoginController {
    private final SocialLoginSettings settings;
    private final SocialLoginTickets tickets;
    private final SocialLoginFlow flow;
    private final AuthService auth;
    public SocialLoginController(SocialLoginSettings settings, SocialLoginTickets tickets, SocialLoginFlow flow, AuthService auth) {
        this.settings = settings; this.tickets = tickets; this.flow = flow; this.auth = auth;
    }
    @GetMapping("/providers") public Map<String, Object> providers() { return Map.of("providers", settings.providers()); }
    @PostMapping("/profile") public Map<String, Object> completeProfile(@RequestBody Map<String, Object> body) {
        return auth.completeSocialProfile(body);
    }
    @ExceptionHandler(MemberIdentityConflict.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> duplicateMember(MemberIdentityConflict error) { return error.toPublic(); }
    @PostMapping(value = "/exchange", consumes = "application/json")
    public Map<String, Object> exchange(@CookieValue(name = SocialLoginFlow.COOKIE_NAME, required = false) String value,
            HttpServletRequest request, HttpServletResponse response) {
        if (!settings.getFrontendUrl().equals(request.getHeader(HttpHeaders.ORIGIN))) {
            throw new ApiException(403, "로그인 요청 출처를 확인할 수 없습니다.");
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.addHeader(HttpHeaders.SET_COOKIE, flow.cookie("", 0).toString());
        SocialLoginTickets.Ticket ticket = tickets.consume(value);
        return auth.issueSocialSession(ticket.getUserId(), ticket.getTokenVersion(), ticket.isSignupPending());
    }
}
