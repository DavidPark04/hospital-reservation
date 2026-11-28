package kr.dmu.hospital.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.dmu.hospital.common.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class FirebasePhoneVerifier {
    private static final String KEYS = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
    private final String project;
    private final JwtDecoder decoder;
    private final Clock clock;

    @Autowired public FirebasePhoneVerifier(@Value("${app.verification.firebase-project-id:}") String project) {
        this(project, project.isBlank() ? null : decoder(project), Clock.systemUTC());
    }

    FirebasePhoneVerifier(String project, JwtDecoder decoder, Clock clock) {
        this.project = project.trim();
        this.decoder = decoder;
        this.clock = clock;
    }

    private static JwtDecoder decoder(String project) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(5000);
        requests.setReadTimeout(5000);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(KEYS)
                .jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(new RestTemplate(requests)).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("https://securetoken.google.com/" + project.trim()));
        return decoder;
    }

    public boolean enabled() { return !project.isBlank() && decoder != null; }

    public PhoneIdentity verify(String token) {
        if (!enabled()) throw new ApiException(503, "문자 인증 설정을 확인해 주세요.");
        if (token == null || token.length() < 20 || token.length() > 16000) throw invalid();
        Jwt jwt;
        try { jwt = decoder.decode(token); }
        catch (BadJwtException error) { throw invalid(); }
        catch (JwtException error) { throw new ApiException(503, "문자 인증을 확인하는 중 연결이 지연됐어요. 잠시 후 다시 시도해 주세요."); }
        try {
            Instant now = clock.instant();
            Object authenticationTime = jwt.getClaim("auth_time");
            Map<String, Object> firebase = jwt.getClaim("firebase");
            String phone = jwt.getClaimAsString("phone_number");
            if (!"RS256".equals(jwt.getHeaders().get("alg")) || !jwt.getAudience().equals(List.of(project))
                    || jwt.getIssuer() == null || !("https://securetoken.google.com/" + project).equals(jwt.getIssuer().toString())
                    || jwt.getSubject() == null || jwt.getSubject().isBlank() || jwt.getSubject().length() > 128
                    || jwt.getIssuedAt() == null || jwt.getIssuedAt().isAfter(now.plusSeconds(30))
                    || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)
                    || !(authenticationTime instanceof Number)
                    || firebase == null || !"phone".equals(firebase.get("sign_in_provider")) || firebase.containsKey("tenant")
                    || phone == null || !phone.matches("\\+821[016789][0-9]{7,8}")) throw invalid();
            Instant authenticatedAt = Instant.ofEpochSecond(((Number) authenticationTime).longValue());
            if (authenticatedAt.isAfter(now.plusSeconds(30)) || authenticatedAt.isBefore(now.minusSeconds(300))) throw invalid();
            return new PhoneIdentity(jwt.getSubject(), "0" + phone.substring(3), authenticatedAt);
        } catch (RuntimeException error) { throw invalid(); }
    }

    private static ApiException invalid() {
        return new ApiException(400, "문자 인증을 확인할 수 없어요. 인증번호를 다시 받아 인증해 주세요.");
    }

    public record PhoneIdentity(String subject, String phone, Instant authenticatedAt) { }
}
