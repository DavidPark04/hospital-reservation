package kr.dmu.hospital.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import kr.dmu.hospital.common.ApiException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FirebasePhoneVerifierTest {
    private static KeyPair keys;
    private static Instant now;
    private static FirebasePhoneVerifier verifier;

    @BeforeAll static void setup() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        now = Instant.now();
        var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        verifier = new FirebasePhoneVerifier("medipick-test", decoder, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test void acceptsOnlyFreshPhoneSignInForConfiguredProject() throws Exception {
        var identity = verifier.verify(token(claims()));
        assertEquals("firebase-user", identity.subject());
        assertEquals("01012345678", identity.phone());
        assertEquals(now.getEpochSecond(), identity.authenticatedAt().getEpochSecond());
    }

    @Test void forgedSignatureAndUnverifiedProviderCannotSupplyPhoneProof() throws Exception {
        String token = token(claims());
        String[] parts = token.split("\\.");
        assertThrows(ApiException.class, () -> verifier.verify(parts[0] + "." + parts[1] + "." + "A".repeat(parts[2].length())));
        assertThrows(ApiException.class, () -> verifier.verify(token(claims().claim("firebase", Map.of("sign_in_provider", "password")))));
        assertThrows(ApiException.class, () -> verifier.verify(token(claims().claim("firebase", Map.of("sign_in_provider", "phone", "tenant", "other")))));
    }

    @Test void expiredForeignOldAuthenticationAndWrongContactTokensAreRejected() throws Exception {
        assertThrows(ApiException.class, () -> verifier.verify(token(claims().audience("foreign-project"))));
        assertThrows(ApiException.class, () -> verifier.verify(token(claims().issuer("https://securetoken.google.com/foreign-project"))));
        assertThrows(ApiException.class, () -> verifier.verify(token(claims().expirationTime(Date.from(now.minusSeconds(1))))));
        assertThrows(ApiException.class, () -> verifier.verify(token(claims().issueTime(Date.from(now.plusSeconds(120))))));
        assertThrows(ApiException.class, () -> verifier.verify(token(claims().claim("auth_time", now.minusSeconds(301).getEpochSecond()))));
        assertThrows(ApiException.class, () -> verifier.verify(token(claims().claim("auth_time", now.plusSeconds(120).getEpochSecond()))));
        assertThrows(ApiException.class, () -> verifier.verify(token(claims().claim("phone_number", "+12025550123"))));
        assertThrows(ApiException.class, () -> verifier.verify(token(claims().claim("phone_number", "+82212345678"))));
    }

    @Test void temporarilyUnavailableGoogleKeysAllowRetryWithoutTreatingTheOtpAsWrong() {
        JwtDecoder unavailable = mock(JwtDecoder.class);
        when(unavailable.decode(anyString())).thenThrow(new JwtException("key service temporarily unavailable"));
        var verification = new FirebasePhoneVerifier("medipick-test", unavailable, Clock.fixed(now, ZoneOffset.UTC));
        assertEquals(503, assertThrows(ApiException.class, () -> verification.verify("provider-token-long-enough")).getStatus());
    }

    private JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().subject("firebase-user").issuer("https://securetoken.google.com/medipick-test")
                .audience("medipick-test").issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("auth_time", now.getEpochSecond()).claim("phone_number", "+821012345678")
                .claim("firebase", Map.of("sign_in_provider", "phone"));
    }

    private String token(JWTClaimsSet.Builder claims) throws Exception {
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims.build());
        token.sign(new RSASSASigner(keys.getPrivate()));
        return token.serialize();
    }
}
