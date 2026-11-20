package kr.dmu.hospital.auth;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class MongoAuthorizationRequestsTest {
    private MongoClient client;
    private MongoTemplate mongo;
    private String database;
    private MongoAuthorizationRequests first;
    private MongoAuthorizationRequests second;
    private final SocialLoginSettings settings = new SocialLoginSettings("https://hospital.example", "https://hospital.example",
        "google-test-id", "google-test-secret", "kakao-test-id", "kakao-test-secret");

    @BeforeEach void setUp() {
        String uri = System.getenv("MONGODB_TEST_URI");
        assertTrue(uri.startsWith("mongodb://"));
        for (String host : new ConnectionString(uri).getHosts()) assertTrue(host.matches("(localhost|127\\.0\\.0\\.1|\\[::1\\])(:[0-9]+)?"));
        client = MongoClients.create(uri);
        database = "hospital_oauth_state_test_" + UUID.randomUUID().toString().replace("-", "");
        mongo = new MongoTemplate(client, database);
        new UserIndexes(mongo).run(new DefaultApplicationArguments(new String[0]));
        first = new MongoAuthorizationRequests(mongo, settings);
        second = new MongoAuthorizationRequests(new MongoTemplate(client, database), settings);
    }
    @AfterEach void tearDown() {
        if (mongo != null && database.startsWith("hospital_oauth_state_test_")) mongo.getDb().drop();
        if (client != null) client.close();
    }

    @ParameterizedTest @ValueSource(strings = {"google", "kakao"})
    void realProviderRequestSurvivesInstanceChangeWithoutSessionAndIsSingleUse(String provider) {
        MockHttpServletRequest start = new MockHttpServletRequest("GET", "/oauth2/authorization/" + provider);
        start.setServletPath(start.getRequestURI());
        OAuth2AuthorizationRequest original = new DefaultOAuth2AuthorizationRequestResolver(settings::registration,
            "/oauth2/authorization").resolve(start);
        assertNotNull(original);
        MockHttpServletResponse response = new MockHttpServletResponse();
        first.saveAuthorizationRequest(original, start, response);
        assertNull(start.getSession(false));
        String header = response.getHeader("Set-Cookie");
        assertNotNull(header); assertTrue(header.contains("Secure")); assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Lax")); assertTrue(header.contains("Max-Age=300"));
        assertTrue(header.contains("Path=/login/oauth2/code"));
        Cookie binding = binding(response);
        Document stored = mongo.findById(SocialLoginTickets.hash(original.getState()), Document.class, MongoAuthorizationRequests.COLLECTION);
        assertNotNull(stored); assertFalse(stored.toJson().contains(original.getState())); assertFalse(stored.toJson().contains(binding.getValue()));
        OAuth2AuthorizationRequest loaded = second.loadAuthorizationRequest(callback(original, binding));
        assertNotNull(loaded); assertEquals(original.getState(), loaded.getState());
        assertEquals(original.getAttributes(), loaded.getAttributes());
        assertEquals(original.getAdditionalParameters(), loaded.getAdditionalParameters());
        assertEquals(original.getScopes(), loaded.getScopes());
        assertEquals(original.getRedirectUri(), loaded.getRedirectUri());
        MockHttpServletResponse consumedResponse = new MockHttpServletResponse();
        assertNotNull(second.removeAuthorizationRequest(callback(original, binding), consumedResponse));
        assertTrue(consumedResponse.getHeader("Set-Cookie").contains("Max-Age=0"));
        assertNull(first.removeAuthorizationRequest(callback(original, binding), new MockHttpServletResponse()));
    }

    @Test void missingOrWrongBrowserStateAndProviderCannotConsumeOriginal() {
        OAuth2AuthorizationRequest original = authorization();
        Cookie binding = save(original, first);
        assertNull(second.removeAuthorizationRequest(callback(original, null), new MockHttpServletResponse()));
        assertNull(second.removeAuthorizationRequest(callback(original, new Cookie(MongoAuthorizationRequests.COOKIE_NAME,
            SocialLoginTickets.randomValue())), new MockHttpServletResponse()));
        MockHttpServletRequest wrongState = callback(original, binding);
        wrongState.setParameter("state", SocialLoginTickets.randomValue());
        assertNull(second.removeAuthorizationRequest(wrongState, new MockHttpServletResponse()));
        MockHttpServletRequest wrongProvider = callback(original, binding);
        wrongProvider.setRequestURI("/login/oauth2/code/kakao");
        assertNull(second.removeAuthorizationRequest(wrongProvider, new MockHttpServletResponse()));
        assertNotNull(first.removeAuthorizationRequest(callback(original, binding), new MockHttpServletResponse()));
    }

    @Test void expiredStateIsRejectedEvenBeforeTtlCleanup() {
        Instant base = Instant.now().plusSeconds(3600);
        first = new MongoAuthorizationRequests(mongo, settings, Clock.fixed(base, ZoneOffset.UTC));
        second = new MongoAuthorizationRequests(mongo, settings, Clock.fixed(base.plusSeconds(300), ZoneOffset.UTC));
        OAuth2AuthorizationRequest original = authorization(); Cookie binding = save(original, first);
        assertNull(second.loadAuthorizationRequest(callback(original, binding)));
        assertNull(second.removeAuthorizationRequest(callback(original, binding), new MockHttpServletResponse()));
        assertNotNull(mongo.findById(SocialLoginTickets.hash(original.getState()), Document.class, MongoAuthorizationRequests.COLLECTION));
    }

    @Test void simultaneousCallbacksOnDifferentInstancesHaveOnlyOneWinner() throws Exception {
        OAuth2AuthorizationRequest original = authorization(); Cookie binding = save(original, first);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> calls = List.of(
                () -> first.removeAuthorizationRequest(callback(original, binding), new MockHttpServletResponse()) != null,
                () -> second.removeAuthorizationRequest(callback(original, binding), new MockHttpServletResponse()) != null);
            int succeeded = 0;
            for (var result : executor.invokeAll(calls)) if (result.get()) succeeded++;
            assertEquals(1, succeeded);
        }
    }

    @Test void onlyProtocolFieldsAreStoredAndPkceVerifierIsPreserved() {
        OAuth2AuthorizationRequest original = authorization(); Cookie binding = save(original, first);
        Document stored = mongo.findById(SocialLoginTickets.hash(original.getState()), Document.class, MongoAuthorizationRequests.COLLECTION);
        assertEquals(Set.of("_id", "bindingHash", "provider", "authorizationUri", "clientId", "redirectUri", "scopes", "parameters", "attributes", "expiresAt"), stored.keySet());
        assertFalse(stored.toJson().contains("should-not-save"));
        OAuth2AuthorizationRequest restored = second.removeAuthorizationRequest(callback(original, binding), new MockHttpServletResponse());
        assertEquals("test-verifier", restored.getAttribute("code_verifier"));
        assertEquals("raw-nonce", restored.getAttribute("nonce"));
        assertEquals("nonce-hash", restored.getAdditionalParameters().get("nonce"));
        assertEquals("challenge-hash", restored.getAdditionalParameters().get("code_challenge"));
    }

    @Test void temporaryCollectionsHaveExpiryIndexes() {
        for (String collection : List.of(MongoAuthorizationRequests.COLLECTION, SocialLoginTickets.COLLECTION)) {
            List<Document> indexes = mongo.getCollection(collection).listIndexes().into(new java.util.ArrayList<>());
            assertTrue(indexes.stream().anyMatch(index -> new Document("expiresAt", 1).equals(index.get("key"))
                && index.get("expireAfterSeconds") instanceof Number seconds && seconds.longValue() == 0));
        }
    }

    private OAuth2AuthorizationRequest authorization() {
        return OAuth2AuthorizationRequest.authorizationCode().authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
            .clientId("google-test-id").redirectUri("https://hospital.example/login/oauth2/code/google").scopes(Set.of("openid"))
            .state(SocialLoginTickets.randomValue()).attributes(Map.of("registration_id", "google", "nonce", "raw-nonce",
                "code_verifier", "test-verifier", "name", "should-not-save"))
            .additionalParameters(Map.of("nonce", "nonce-hash", "code_challenge", "challenge-hash", "code_challenge_method", "S256",
                "access_token", "should-not-save")).build();
    }
    private Cookie save(OAuth2AuthorizationRequest authorization, MongoAuthorizationRequests repository) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(authorization, new MockHttpServletRequest(), response);
        return binding(response);
    }
    private Cookie binding(MockHttpServletResponse response) {
        return new Cookie(MongoAuthorizationRequests.COOKIE_NAME, response.getHeader("Set-Cookie").split(";", 2)[0].split("=", 2)[1]);
    }
    private MockHttpServletRequest callback(OAuth2AuthorizationRequest authorization, Cookie binding) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/" + authorization.getAttribute("registration_id"));
        request.setParameter("state", authorization.getState()); request.setParameter("code", "unused-test-code");
        if (binding != null) request.setCookies(binding);
        return request;
    }
}
