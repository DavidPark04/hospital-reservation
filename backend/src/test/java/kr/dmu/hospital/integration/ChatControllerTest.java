package kr.dmu.hospital.integration;

import java.util.List;
import java.util.Map;
import kr.dmu.hospital.auth.UserPrincipal;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.mockito.Mockito.*;

class ChatControllerTest {
    private final ChatService service = mock(ChatService.class);
    private final ChatController controller = new ChatController(service);
    private final Map<String, Object> body = Map.of("messages", List.of(Map.of("role", "user", "content", "병원을 찾아주세요")));

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test void separatelyAuthenticatedRequestsForTheSameMemberUseTheSameDatabaseId() {
        // JwtAuthFilter reconstructs a fresh UserPrincipal on every request.
        signIn(new UserPrincipal(member("member-123")));
        controller.chat(body, request());
        signIn(new UserPrincipal(member("member-123")));
        controller.chat(body, request());
        verify(service, times(2)).chat(body, "user:member-123", true);
        verifyNoMoreInteractions(service);
    }

    @Test void differentMembersHaveSeparateQuotasEvenWithTheSameDisplayNameAndIp() {
        signIn(new UserPrincipal(member("member-123")));
        controller.chat(body, request());
        signIn(new UserPrincipal(member("member-456")));
        controller.chat(body, request());
        verify(service).chat(body, "user:member-123", true);
        verify(service).chat(body, "user:member-456", true);
        verifyNoMoreInteractions(service);
    }

    @Test void unfinishedSocialSignupUsesTheAnonymousIpQuota() {
        signIn(UserPrincipal.forSignup(new Document("_id", "signup-123").append("authProvider", "GOOGLE")));
        controller.chat(body, request());
        verify(service).chat(body, "ip:192.0.2.10", false);
        verifyNoMoreInteractions(service);
    }

    @Test void anonymousAndUnauthenticatedRequestsUseTheSameIpQuota() {
        controller.chat(body, request());
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("test", "anonymousUser",
            List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        controller.chat(body, request());
        verify(service, times(2)).chat(body, "ip:192.0.2.10", false);
        verifyNoMoreInteractions(service);
    }

    private Document member(String id) {
        return new Document("_id", id).append("loginId", id).append("name", "동일 표시 이름")
            .append("role", "USER").append("authProvider", "LOCAL");
    }
    private void signIn(UserPrincipal user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null,
            List.of(new SimpleGrantedAuthority(user.isProfileComplete() ? "ROLE_USER" : "ROLE_PROFILE_PENDING"))));
    }
    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest(); request.setRemoteAddr("192.0.2.10"); return request;
    }
}
