package kr.dmu.hospital.auth;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.stereotype.Component;

@Component
public class SocialLoginSettings {
    private final String frontendUrl;
    private final String backendUrl;
    private final List<ClientRegistration> registrations = new ArrayList<>();

    public SocialLoginSettings(@Value("${app.social.frontend-url}") String frontendUrl,
            @Value("${app.social.backend-url}") String backendUrl,
            @Value("${app.social.google-client-id}") String googleId,
            @Value("${app.social.google-client-secret}") String googleSecret,
            @Value("${app.social.kakao-client-id}") String kakaoId,
            @Value("${app.social.kakao-client-secret}") String kakaoSecret) {
        this.frontendUrl = origin(frontendUrl); this.backendUrl = origin(backendUrl);
        if (!URI.create(this.frontendUrl).getHost().equals(URI.create(this.backendUrl).getHost())) {
            throw new IllegalArgumentException("Social login frontend and backend must share a host for the login handoff cookie.");
        }
        if (!googleId.isBlank() && !googleSecret.isBlank()) {
            registrations.add(base("google", googleId, googleSecret)
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://www.googleapis.com/oauth2/v3/userinfo")
                .userNameAttributeName("sub").jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .issuerUri("https://accounts.google.com").scope("openid").clientName("Google").build());
        }
        if (!kakaoId.isBlank() && !kakaoSecret.isBlank()) {
            registrations.add(base("kakao", kakaoId, kakaoSecret)
                .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .tokenUri("https://kauth.kakao.com/oauth/token")
                .userInfoUri("https://kapi.kakao.com/v2/user/me")
                .userNameAttributeName("id").scope(List.of()).clientName("Kakao").build());
        }
    }

    private ClientRegistration.Builder base(String provider, String id, String secret) {
        return ClientRegistration.withRegistrationId(provider).clientId(id).clientSecret(secret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(backendUrl + "/login/oauth2/code/" + provider);
    }
    public ClientRegistration registration(String provider) {
        for (ClientRegistration registration : registrations) {
            if (registration.getRegistrationId().equals(provider)) return registration;
        }
        return null;
    }
    public List<Map<String, Object>> providers() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : List.of("google", "kakao")) {
            result.add(Map.of("id", id, "enabled", registration(id) != null,
                "authorizationUrl", backendUrl + "/oauth2/authorization/" + id));
        }
        return result;
    }
    public String getFrontendUrl() { return frontendUrl; }
    public String getBackendUrl() { return backendUrl; }
    public boolean secureCookies() { return backendUrl.startsWith("https://"); }
    private static String origin(String value) {
        URI uri = URI.create(value);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                || !(uri.getScheme().equals("https") || uri.getScheme().equals("http"))) {
            throw new IllegalArgumentException("Social login URL must be an absolute HTTP(S) origin.");
        }
        if (!uri.getScheme().equals("https") && !List.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())) {
            throw new IllegalArgumentException("Social login requires HTTPS outside loopback development.");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
