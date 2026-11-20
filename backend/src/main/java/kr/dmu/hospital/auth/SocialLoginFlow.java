package kr.dmu.hospital.auth;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.bson.Document;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import kr.dmu.hospital.common.ApiException;

@Component
public class SocialLoginFlow {
    public static final String COOKIE_NAME = "hospital_social_handoff";
    private final SocialLoginSettings settings;
    private final SocialMemberService members;
    private final SocialLoginTickets tickets;
    private final MongoAuthorizationRequests authorizationRequests;
    private final SocialWithdrawalService withdrawal;
    public SocialLoginFlow(SocialLoginSettings settings, SocialMemberService members, SocialLoginTickets tickets,
            MongoAuthorizationRequests authorizationRequests, SocialWithdrawalService withdrawal) {
        this.settings = settings; this.members = members; this.tickets = tickets; this.authorizationRequests = authorizationRequests;
        this.withdrawal = withdrawal;
    }
    public void success(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException {
        try {
            OAuth2AuthenticationToken oauth = (OAuth2AuthenticationToken) authentication;
            if (withdrawal.isWithdrawal(request)) {
                String proof = withdrawal.verify(request, oauth.getAuthorizedClientRegistrationId(), oauth.getPrincipal().getAttributes());
                response.addHeader(HttpHeaders.SET_COOKIE, withdrawal.cookie(proof, 300).toString());
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                response.setHeader("Referrer-Policy", "no-referrer");
                response.sendRedirect(settings.getFrontendUrl() + "/mypage?withdrawal=verified");
                return;
            }
            Document user = members.signIn(oauth.getAuthorizedClientRegistrationId(), oauth.getPrincipal().getAttributes());
            String ticket = tickets.create(user);
            response.addHeader(HttpHeaders.SET_COOKIE, cookie(ticket, 60).toString());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.sendRedirect(settings.getFrontendUrl() + "/auth/social/callback");
        } catch (RuntimeException failed) {
            if (withdrawal.isWithdrawal(request)) {
                response.addHeader(HttpHeaders.SET_COOKIE, withdrawal.cookie("", 0).toString());
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                response.setHeader("Referrer-Policy", "no-referrer");
                String error = failed instanceof ApiException api && api.getMessage().contains("다른 소셜 계정") ? "mismatch" : "failed";
                response.sendRedirect(settings.getFrontendUrl() + "/mypage?withdrawal=" + error);
                return;
            }
            response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
            response.sendRedirect(settings.getFrontendUrl() + "/login?socialError=failed");
        } finally { clearSession(request); }
    }
    public void failure(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure) throws IOException {
        String error = failure instanceof OAuth2AuthenticationException oauth && "access_denied".equals(oauth.getError().getErrorCode()) ? "cancelled" : "failed";
        clearSession(request);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (withdrawal.isWithdrawal(request)) {
            response.addHeader(HttpHeaders.SET_COOKIE, withdrawal.cookie("", 0).toString());
            response.sendRedirect(settings.getFrontendUrl() + "/mypage?withdrawal=" + error);
            return;
        }
        response.sendRedirect(settings.getFrontendUrl() + "/login?socialError=" + error);
    }
    public ResponseCookie cookie(String value, long seconds) {
        return ResponseCookie.from(COOKIE_NAME, value).httpOnly(true).secure(settings.secureCookies())
            .sameSite("Lax").path("/api/auth/social").maxAge(seconds).build();
    }
    private void clearSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }
    /** OAuth state survives restarts and callback routing to a different backend instance. */
    public AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequests() {
        return authorizationRequests;
    }
    /** Provider access/refresh tokens are needed only for this callback and must not be retained. */
    public OAuth2AuthorizedClientRepository transientClients() {
        return new OAuth2AuthorizedClientRepository() {
            @Override public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String id, Authentication principal, HttpServletRequest request) { return null; }
            @Override public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal, HttpServletRequest request, HttpServletResponse response) { }
            @Override public void removeAuthorizedClient(String id, Authentication principal, HttpServletRequest request, HttpServletResponse response) { }
        };
    }
}
