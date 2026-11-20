package kr.dmu.hospital.auth;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.dmu.hospital.common.ApiErrorHandler;
import kr.dmu.hospital.common.ApiException;
import kr.dmu.hospital.member.MemberService;
import kr.dmu.hospital.booking.BookingService;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.util.UriComponentsBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class SocialWithdrawalMongoTest {
    private MongoClient client;
    private MongoTemplate mongo;
    private String database;
    private MongoAuthorizationRequests requests;
    private SocialWithdrawalService withdrawal;
    private MemberService members;
    private SocialMemberService social;
    private SocialLoginTickets tickets;
    private SocialLoginFlow flow;
    private MockMvc mvc;
    private final SocialLoginSettings settings = new SocialLoginSettings("https://hospital.example", "https://hospital.example",
        "google-test", "google-secret", "kakao-test", "kakao-secret");

    @BeforeEach void setUp() {
        String uri = System.getenv("MONGODB_TEST_URI");
        assertTrue(uri.startsWith("mongodb://"));
        for (String host : new ConnectionString(uri).getHosts()) assertTrue(host.matches("(localhost|127\\.0\\.0\\.1|\\[::1\\])(:[0-9]+)?"));
        client = MongoClients.create(uri);
        database = "hospital_withdrawal_test_" + UUID.randomUUID().toString().replace("-", "");
        mongo = new MongoTemplate(client, database);
        new UserIndexes(mongo).run(new DefaultApplicationArguments(new String[0]));
        for (String provider : List.of("google", "kakao")) {
            mongo.insert(new Document("_id", provider).append("loginId", provider + "_member").append("role", "USER")
                .append("authProvider", provider.toUpperCase()).append("socialIdentity", provider.toUpperCase() + ":123")
                .append("socialSubject", "123")
                .append("profileComplete", true).append("tokenVersion", 2), "users");
        }
        requests = new MongoAuthorizationRequests(mongo, settings);
        members = mock(MemberService.class);
        when(members.withdrawSocial(anyString(), anyString(), anyInt())).thenAnswer(call -> new Document("deletedUserId", call.getArgument(0))
            .append("canceledReservations", 0).append("message", "회원 탈퇴가 완료되었습니다."));
        withdrawal = new SocialWithdrawalService(mongo, settings, requests, members);
        social = mock(SocialMemberService.class); tickets = mock(SocialLoginTickets.class);
        flow = new SocialLoginFlow(settings, social, tickets, requests, withdrawal);
        mvc = MockMvcBuilders.standaloneSetup(new SocialWithdrawalController(withdrawal, settings))
            .setControllerAdvice(new ApiErrorHandler()).build();
    }
    @AfterEach void tearDown() {
        SecurityContextHolder.clearContext();
        if (mongo != null && database.startsWith("hospital_withdrawal_test_")) mongo.getDb().drop();
        if (client != null) client.close();
    }

    @ParameterizedTest @ValueSource(strings = {"google", "kakao"})
    void providerConfirmationNeverDeletesUntilExplicitAuthenticatedPost(String provider) throws Exception {
        MockHttpServletRequest callback = begin(provider);
        MockHttpServletResponse response = new MockHttpServletResponse();
        flow.success(callback, response, authentication(provider, "123"));
        assertEquals("https://hospital.example/mypage?withdrawal=verified", response.getRedirectedUrl());
        assertNotNull(mongo.findById(provider, Document.class, "users"));
        verifyNoInteractions(members, social, tickets);
        Cookie proof = cookie(response, SocialWithdrawalService.COOKIE_NAME);
        assertFalse(response.getRedirectedUrl().contains(proof.getValue()));
        String header = response.getHeaders("Set-Cookie").stream().filter(value -> value.startsWith(proof.getName() + "=")).findFirst().orElseThrow();
        assertTrue(header.contains("HttpOnly")); assertTrue(header.contains("Secure")); assertTrue(header.contains("SameSite=Strict"));
        signIn(provider);
        mvc.perform(post("/api/auth/social/withdrawal/confirm").contentType("application/json").content("{}")
            .header("Origin", settings.getFrontendUrl()).cookie(proof))
            .andExpect(status().isOk()).andExpect(jsonPath("$.deletedUserId").value(provider));
        verify(members).withdrawSocial(provider, provider.toUpperCase() + ":123", 2);
        mvc.perform(post("/api/auth/social/withdrawal/confirm").contentType("application/json").content("{}")
            .header("Origin", settings.getFrontendUrl()).cookie(proof)).andExpect(status().isForbidden());
        verifyNoMoreInteractions(members);
    }

    @Test void wrongProviderAccountAndCancelledLoginCannotCreateSignupOrDelete() throws Exception {
        MockHttpServletResponse mismatch = new MockHttpServletResponse();
        flow.success(begin("google"), mismatch, authentication("google", "999"));
        assertEquals("https://hospital.example/mypage?withdrawal=mismatch", mismatch.getRedirectedUrl());
        MockHttpServletResponse cancelled = new MockHttpServletResponse();
        flow.failure(begin("kakao"), cancelled, new OAuth2AuthenticationException(new OAuth2Error("access_denied")));
        assertEquals("https://hospital.example/mypage?withdrawal=cancelled", cancelled.getRedirectedUrl());
        assertEquals(0, mongo.getCollection(SocialWithdrawalService.COLLECTION).countDocuments());
        verifyNoInteractions(members, social, tickets);
    }

    @Test void stolenProofWrongOriginAndFabricatedCallbackFlagCannotDelete() throws Exception {
        String proof = proof("google");
        assertEquals(403, assertThrows(ApiException.class, () -> withdrawal.confirm("kakao", proof)).getStatus());
        signIn("google");
        mvc.perform(post("/api/auth/social/withdrawal/confirm").contentType("application/json").content("{}")
            .header("Origin", "https://attacker.example").cookie(new Cookie(SocialWithdrawalService.COOKIE_NAME, proof)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/social/withdrawal/confirm?withdrawal=verified").contentType("application/json").content("{}")
            .header("Origin", settings.getFrontendUrl())).andExpect(status().isForbidden());
        assertNotNull(withdrawal.confirm("google", proof));
        verify(members).withdrawSocial("google", "GOOGLE:123", 2);
    }

    @Test void expiredProofAndLoggedOutSessionCannotBeReused() {
        String proof = proof("google");
        SocialWithdrawalService later = new SocialWithdrawalService(mongo, settings, requests, members,
            Clock.fixed(Instant.now().plusSeconds(301), ZoneOffset.UTC));
        assertEquals(403, assertThrows(ApiException.class, () -> later.confirm("google", proof)).getStatus());
        mongo.updateFirst(Query.query(Criteria.where("_id").is("google")), new Update().set("tokenVersion", 3), "users");
        assertEquals(403, assertThrows(ApiException.class, () -> withdrawal.confirm("google", proof)).getStatus());
        verifyNoInteractions(members);
    }

    @Test void logoutDuringProviderAuthenticationInvalidatesIntent() {
        MockHttpServletRequest callback = begin("google");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("google")), new Update().set("tokenVersion", 3), "users");
        assertThrows(ApiException.class, () -> withdrawal.verify(callback, "google", Map.of("sub", "123")));
        assertEquals(0, mongo.getCollection(SocialWithdrawalService.COLLECTION).countDocuments());
        verifyNoInteractions(members);
    }

    @Test void cancellationInvalidatesProofAndNewStartInvalidatesOldProof() {
        String first = proof("google");
        withdrawal.cancel("google", first);
        assertThrows(ApiException.class, () -> withdrawal.confirm("google", first));
        String second = proof("google");
        begin("google");
        assertThrows(ApiException.class, () -> withdrawal.confirm("google", second));
        verifyNoInteractions(members);
    }

    @Test void failedDeletionRollsBackProofConsumption() {
        String proof = proof("google");
        when(members.withdrawSocial("google", "GOOGLE:123", 2)).thenThrow(new ApiException(409, "예약 처리 충돌"));
        assertEquals(409, assertThrows(ApiException.class, () -> withdrawal.confirm("google", proof)).getStatus());
        assertNotNull(mongo.findById(SocialLoginTickets.hash(proof), Document.class, SocialWithdrawalService.COLLECTION));
        doReturn(new Document("deletedUserId", "google")).when(members).withdrawSocial("google", "GOOGLE:123", 2);
        assertNotNull(withdrawal.confirm("google", proof));
    }

    @Test void proofAndRealMemberDeletionShareOneRollbackAndCommit() {
        String proof = proof("google");
        BookingService booking = mock(BookingService.class);
        when(booking.cancelMemberReservations("google", "google"))
            .thenThrow(new ApiException(409, "예약 처리 충돌")).thenReturn(0);
        MemberService actualMembers = new MemberService(mongo, booking,
            new MongoTransactionManager(mongo.getMongoDatabaseFactory()), new BCryptPasswordEncoder());
        SocialWithdrawalService actual = new SocialWithdrawalService(mongo, settings, requests, actualMembers);
        assertEquals(409, assertThrows(ApiException.class, () -> actual.confirm("google", proof)).getStatus());
        assertNotNull(mongo.findById("google", Document.class, "users"));
        assertNotNull(mongo.findById(SocialLoginTickets.hash(proof), Document.class, SocialWithdrawalService.COLLECTION));
        assertEquals("google", actual.confirm("google", proof).getString("deletedUserId"));
        assertNull(mongo.findById("google", Document.class, "users"));
        assertNull(mongo.findById(SocialLoginTickets.hash(proof), Document.class, SocialWithdrawalService.COLLECTION));
    }

    @Test void endpointsRequireAuthenticatedUserAndAdminOrLocalCannotStart() throws Exception {
        for (String action : List.of("start", "confirm", "cancel")) {
            mvc.perform(post("/api/auth/social/withdrawal/" + action).contentType("application/json").content("{}")
                .header("Origin", settings.getFrontendUrl())).andExpect(status().isUnauthorized());
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is("google")), new Update().set("role", "ADMIN"), "users");
        assertEquals(403, assertThrows(ApiException.class, () -> begin("google")).getStatus());
        mongo.updateFirst(Query.query(Criteria.where("_id").is("google")), new Update().set("role", "USER").set("authProvider", "LOCAL"), "users");
        assertEquals(403, assertThrows(ApiException.class, () -> begin("google")).getStatus());
        verifyNoInteractions(members);
    }

    @Test void proofHasExpiryIndexAndStoresOnlyHashedSecret() {
        String value = proof("google");
        Document stored = mongo.findById(SocialLoginTickets.hash(value), Document.class, SocialWithdrawalService.COLLECTION);
        assertNotNull(stored); assertFalse(stored.toJson().contains(value));
        assertTrue(mongo.getCollection(SocialWithdrawalService.COLLECTION).listIndexes().into(new java.util.ArrayList<>()).stream()
            .anyMatch(index -> new Document("expiresAt", 1).equals(index.get("key"))
                && index.get("expireAfterSeconds") instanceof Number seconds && seconds.intValue() == 0));
    }

    private MockHttpServletRequest begin(String provider) {
        MockHttpServletRequest start = new MockHttpServletRequest("POST", "/api/auth/social/withdrawal/start");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String url = withdrawal.start(provider, start, response).get("authorizationUrl");
        var parameters = UriComponentsBuilder.fromUriString(url).build().getQueryParams();
        assertEquals("kakao".equals(provider) ? "login" : "select_account", parameters.getFirst("prompt"));
        assertFalse(url.contains("withdrawal_user_id")); assertFalse(url.contains("withdrawal_identity"));
        MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/oauth2/code/" + provider);
        callback.setParameter("state", URLDecoder.decode(parameters.getFirst("state"), StandardCharsets.UTF_8));
        callback.setParameter("code", "unused-test-code");
        callback.setCookies(cookie(response, MongoAuthorizationRequests.COOKIE_NAME));
        assertNotNull(requests.removeAuthorizationRequest(callback, new MockHttpServletResponse()));
        assertTrue(withdrawal.isWithdrawal(callback));
        return callback;
    }
    private String proof(String provider) {
        return withdrawal.verify(begin(provider), provider, "google".equals(provider) ? Map.of("sub", "123") : Map.of("id", 123L));
    }
    private OAuth2AuthenticationToken authentication(String provider, String subject) {
        Map<String, Object> attributes = "google".equals(provider) ? Map.of("sub", subject) : Map.of("id", Long.parseLong(subject));
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(List.of(), attributes, "google".equals(provider) ? "sub" : "id"), List.of(), provider);
    }
    private Cookie cookie(MockHttpServletResponse response, String name) {
        String header = response.getHeaders("Set-Cookie").stream().filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
        return new Cookie(name, header.split(";", 2)[0].split("=", 2)[1]);
    }
    private void signIn(String userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new UserPrincipal(mongo.findById(userId, Document.class, "users")), null, List.of()));
    }
}
