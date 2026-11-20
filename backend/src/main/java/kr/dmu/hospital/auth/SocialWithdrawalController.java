package kr.dmu.hospital.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/social/withdrawal")
public class SocialWithdrawalController {
    private final SocialWithdrawalService withdrawal;
    private final SocialLoginSettings settings;
    public SocialWithdrawalController(SocialWithdrawalService withdrawal, SocialLoginSettings settings) {
        this.withdrawal = withdrawal; this.settings = settings;
    }
    @PostMapping(value = "/start", consumes = "application/json")
    public Map<String, String> start(HttpServletRequest request, HttpServletResponse response) {
        String userId = CurrentUser.require().getId();
        checkOrigin(request, response);
        return withdrawal.start(userId, request, response);
    }
    @PostMapping(value = "/confirm", consumes = "application/json")
    public Document confirm(@CookieValue(name = SocialWithdrawalService.COOKIE_NAME, required = false) String value,
            HttpServletRequest request, HttpServletResponse response) {
        String userId = CurrentUser.require().getId();
        checkOrigin(request, response);
        Document result = withdrawal.confirm(userId, value);
        response.addHeader(HttpHeaders.SET_COOKIE, withdrawal.cookie("", 0).toString());
        return result;
    }
    @PostMapping(value = "/cancel", consumes = "application/json")
    public Map<String, String> cancel(@CookieValue(name = SocialWithdrawalService.COOKIE_NAME, required = false) String value,
            HttpServletRequest request, HttpServletResponse response) {
        String userId = CurrentUser.require().getId();
        checkOrigin(request, response);
        withdrawal.cancel(userId, value);
        response.addHeader(HttpHeaders.SET_COOKIE, withdrawal.cookie("", 0).toString());
        return Map.of("message", "탈퇴를 취소했습니다.");
    }
    private void checkOrigin(HttpServletRequest request, HttpServletResponse response) {
        if (!settings.getFrontendUrl().equals(request.getHeader(HttpHeaders.ORIGIN))) {
            throw new ApiException(403, "탈퇴 요청 출처를 확인할 수 없습니다.");
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
