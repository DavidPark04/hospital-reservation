package kr.dmu.hospital.integration;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import kr.dmu.hospital.auth.UserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {
    private final ChatService chat;
    public ChatController(ChatService chat) { this.chat = chat; }
    @PostMapping("/api/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UserPrincipal member = auth != null && auth.isAuthenticated()
            && auth.getPrincipal() instanceof UserPrincipal user && user.isProfileComplete() ? user : null;
        String caller = member != null ? "user:" + member.getId() : "ip:" + request.getRemoteAddr();
        return chat.chat(body, caller, member != null);
    }
}
