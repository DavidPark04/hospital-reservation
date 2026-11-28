package kr.dmu.hospital.auth;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import kr.dmu.hospital.common.ApiErrorHandler;
import kr.dmu.hospital.config.SecurityConfig;
import kr.dmu.hospital.member.MemberService;
import kr.dmu.hospital.member.MyMemberController;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = SocialLoginSecurityTest.TestConfig.class)
class SocialLoginSecurityTest {
    @Configuration @EnableWebMvc @EnableWebSecurity @Import(SecurityConfig.class)
    static class TestConfig {
        @Bean AuthService auth() { return mock(AuthService.class); }
        @Bean JwtAuthFilter jwt(AuthService auth) { return new JwtAuthFilter(auth); }
        @Bean SocialMemberService members() { return mock(SocialMemberService.class); }
        @Bean SocialLoginSettings settings() { return new SocialLoginSettings("http://127.0.0.1:5174", "http://127.0.0.1:18081", "test-google-id", "test-google-secret", "test-kakao-id", "test-kakao-secret"); }
        @Bean SocialLoginTickets tickets() { return mock(SocialLoginTickets.class); }
        @Bean SocialWithdrawalService withdrawal() { return mock(SocialWithdrawalService.class); }
        @Bean MongoAuthorizationRequests requests(SocialLoginSettings settings) { return new MongoAuthorizationRequests(mock(MongoTemplate.class), settings); }
        @Bean SocialLoginFlow flow(SocialLoginSettings settings, SocialMemberService members, SocialLoginTickets tickets, MongoAuthorizationRequests requests, SocialWithdrawalService withdrawal) { return new SocialLoginFlow(settings, members, tickets, requests, withdrawal); }
        @Bean SocialLoginController social(SocialLoginSettings settings, SocialLoginTickets tickets, SocialLoginFlow flow, AuthService auth) { return new SocialLoginController(settings, tickets, flow, auth); }
        @Bean AuthController authController(AuthService auth) { return new AuthController(auth); }
        @Bean HospitalAccountService hospitalAccounts() { return mock(HospitalAccountService.class); }
        @Bean HospitalAccountController hospitalController(HospitalAccountService accounts) { return new HospitalAccountController(accounts); }
        @Bean ContactVerificationService contactVerification() { return mock(ContactVerificationService.class); }
        @Bean ContactVerificationController contactController(ContactVerificationService verification) { return new ContactVerificationController(verification); }
        @Bean MemberService memberDeletion() { return mock(MemberService.class); }
        @Bean MyMemberController myMemberController(MemberService members) { return new MyMemberController(members); }
        @Bean ApiErrorHandler errors() { return new ApiErrorHandler(); }
        @Bean ProtectedTestController protectedApis() { return new ProtectedTestController(); }
    }
    @RestController static class ProtectedTestController {
        @GetMapping("/api/reservations") public Map<String, Object> reservations() { return Map.of("items", List.of()); }
    }
    @Autowired WebApplicationContext context;
    @Autowired AuthService auth;
    @Autowired HospitalAccountService hospitalAccounts;
    @Autowired ContactVerificationService contactVerification;
    @Autowired MemberService memberDeletion;
    @Autowired SocialMemberService members;
    @Autowired SocialLoginTickets tickets;
    @Autowired SocialLoginFlow flow;
    private MockMvc mvc;
    @BeforeEach void setUp() {
        reset(auth, members, tickets, hospitalAccounts, contactVerification, memberDeletion);
        when(tickets.create(any())).thenReturn(SocialLoginTickets.randomValue());
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void tearDown() { SecurityContextHolder.clearContext(); }

    @Test void hospitalCanWithdrawOnlyItselfWithTheCurrentMemberToken() throws Exception {
        String body = "{\"password\":\" CurrentHospitalPassword! \",\"userId\":\"other-user\"}";
        mvc.perform(delete("/api/members/me").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        when(auth.authenticate("pending-token")).thenReturn(UserPrincipal.forSignup(new Document("_id", "pending")
                .append("role", "USER").append("authProvider", "GOOGLE")));
        mvc.perform(delete("/api/members/me").header("Authorization", "Bearer pending-token")
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(memberDeletion);
        UserPrincipal hospital = new UserPrincipal(new Document("_id", "hospital-owner").append("loginId", "hospital:1234567890")
                .append("role", "HOSPITAL").append("authProvider", "LOCAL").append("profileComplete", true)
                .append("hospitalId", "hospital").append("businessNumber", "1234567890"));
        when(auth.authenticate("hospital-token")).thenReturn(hospital);
        when(memberDeletion.withdrawVerified("hospital-owner", " CurrentHospitalPassword! ", null))
                .thenReturn(new Document("deletedUserId", "hospital-owner").append("canceledReservations", 0));
        mvc.perform(delete("/api/members/me").param("userId", "other-user").header("Authorization", "Bearer hospital-token")
                .contentType("application/json").content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedUserId").value("hospital-owner"));
        verify(memberDeletion).withdrawVerified("hospital-owner", " CurrentHospitalPassword! ", null);
        mvc.perform(get("/api/reservations").header("Authorization", "Bearer hospital-token")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/members/other-user").header("Authorization", "Bearer hospital-token"))
                .andExpect(status().isForbidden());
        verifyNoMoreInteractions(memberDeletion);
    }

    @Test void pendingSocialSignupCanVerifyContactsWithItsOwnPrincipal() throws Exception {
        UserPrincipal pending = UserPrincipal.forSignup(new Document("_id", "pending-person").append("role", "USER")
                .append("authProvider", "GOOGLE").append("tokenVersion", 3));
        when(auth.authenticate("pending-token")).thenReturn(pending);
        when(contactVerification.configuration()).thenReturn(Map.of("emailEnabled", true, "phoneEnabled", true));
        when(contactVerification.sendEmail(anyMap(), anyString())).thenAnswer(call -> {
            assertSame(pending, CurrentUser.require());
            assertTrue(CurrentUser.require().isSignupPending());
            return Map.of("challengeId", "challenge", "expiresIn", 300, "retryAfter", 60);
        });
        when(contactVerification.confirmEmail(anyMap())).thenAnswer(call -> {
            assertSame(pending, CurrentUser.require());
            return Map.of("verificationToken", "email-proof", "contact", "person@example.com", "expiresIn", 600);
        });
        when(contactVerification.confirmPhone(anyMap())).thenAnswer(call -> {
            assertSame(pending, CurrentUser.require());
            assertEquals(3, CurrentUser.require().getTokenVersion());
            return Map.of("verificationToken", "phone-proof", "contact", "01012345678", "expiresIn", 600);
        });
        mvc.perform(get("/api/auth/verification/config").header("Authorization", "Bearer pending-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phoneEnabled").value(true));
        mvc.perform(post("/api/auth/verification/email/send").header("Authorization", "Bearer pending-token")
                        .contentType("application/json").content("{\"purpose\":\"SIGNUP\",\"email\":\"person@example.com\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.challengeId").value("challenge"));
        mvc.perform(post("/api/auth/verification/email/confirm").header("Authorization", "Bearer pending-token")
                        .contentType("application/json").content("{\"challengeId\":\"challenge\",\"code\":\"123456\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verificationToken").value("email-proof"));
        mvc.perform(post("/api/auth/verification/phone/confirm").header("Authorization", "Bearer pending-token")
                        .contentType("application/json").content("{\"purpose\":\"SIGNUP\",\"idToken\":\"firebase-proof\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verificationToken").value("phone-proof"));
        mvc.perform(get("/api/reservations").header("Authorization", "Bearer pending-token")).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/verification/unlisted").header("Authorization", "Bearer pending-token")
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }

    @Test void hospitalApplicantCanPostACandidateSelectionBeforeLogin() throws Exception {
        Map<String, Object> selection = Map.of("businessNumber", "1108200472", "selectionToken", "choice", "hospitalId", "dental");
        when(hospitalAccounts.select(selection)).thenReturn(Map.of("businessNumber", "1108200472",
                "hospital", Map.of("id", "dental", "name", "연세대학교치과대학치과병원"), "verificationToken", "verified"));
        mvc.perform(post("/api/auth/hospital/lookup/select").contentType("application/json")
                        .content("{\"businessNumber\":\"1108200472\",\"selectionToken\":\"choice\",\"hospitalId\":\"dental\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hospital.id").value("dental"))
                .andExpect(jsonPath("$.verificationToken").value("verified"));
        verify(hospitalAccounts).select(selection);
    }

    @Test void passwordChangeRequiresCompletedMemberTokenAndPreservesRawFields() throws Exception {
        String body = "{\"currentPassword\":\" CurrentPassword! \",\"newPassword\":\" ChangedPassword! \"}";
        mvc.perform(patch("/api/auth/password").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        when(auth.authenticate("pending-token")).thenReturn(UserPrincipal.forSignup(new Document("_id", "pending")
            .append("role", "USER").append("authProvider", "GOOGLE")));
        mvc.perform(patch("/api/auth/password").header("Authorization", "Bearer pending-token")
            .contentType("application/json").content(body)).andExpect(status().isForbidden());
        verify(auth, never()).changePassword(anyMap());
        UserPrincipal member = new UserPrincipal(new Document("_id", "member").append("loginId", "member1")
            .append("role", "USER").append("authProvider", "LOCAL"));
        when(auth.authenticate("member-token")).thenReturn(member);
        Map<String, Object> fields = Map.of("currentPassword", " CurrentPassword! ", "newPassword", " ChangedPassword! ");
        when(auth.changePassword(fields)).thenReturn(Map.of("token", "refreshed-token", "user", member.toPublic()));
        mvc.perform(patch("/api/auth/password").header("Authorization", "Bearer member-token")
            .contentType("application/json").content(body)).andExpect(status().isOk())
            .andExpect(jsonPath("$.token").value("refreshed-token")).andExpect(jsonPath("$.user.id").value("member"));
        verify(auth).changePassword(fields);
    }

    @Test void passwordChangeReturnsServiceValidationErrorsAndAllowsLocalAdmin() throws Exception {
        UserPrincipal admin = new UserPrincipal(new Document("_id", "admin").append("loginId", "admin").append("role", "ADMIN"));
        when(auth.authenticate("admin-token")).thenReturn(admin);
        when(auth.changePassword(anyMap())).thenThrow(new kr.dmu.hospital.common.ApiException(400, "현재 비밀번호가 맞지 않습니다."));
        mvc.perform(patch("/api/auth/password").header("Authorization", "Bearer admin-token")
            .contentType("application/json").content("{\"currentPassword\":\"wrong\",\"newPassword\":\"ChangedPassword!\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("현재 비밀번호가 맞지 않습니다."));
        verify(auth).changePassword(anyMap());
    }

    @Test void providerListExposesOnlyPublicConfiguration() throws Exception {
        String json = mvc.perform(get("/api/auth/social/providers")).andExpect(status().isOk())
            .andExpect(jsonPath("$.providers[0].id").value("google"))
            .andExpect(jsonPath("$.providers[1].enabled").value(true)).andReturn().getResponse().getContentAsString();
        assertFalse(json.contains("test-google-id")); assertFalse(json.contains("secret"));
    }
    @Test void authorizationUsesConfiguredCallbackAndRandomState() throws Exception {
        var first = mvc.perform(get("/oauth2/authorization/google").header("Host", "attacker.example"))
            .andExpect(status().is3xxRedirection()).andReturn();
        String url = first.getResponse().getRedirectedUrl();
        assertNotNull(url); assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"));
        assertTrue(url.contains("redirect_uri=http://127.0.0.1:18081/login/oauth2/code/google")
            || url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A18081%2Flogin%2Foauth2%2Fcode%2Fgoogle"));
        assertTrue(url.contains("state=")); assertTrue(url.contains("nonce=")); assertFalse(url.contains("attacker.example"));
        assertTrue(url.contains("scope=openid")); assertFalse(url.contains("scope=openid%20"));
        String second = mvc.perform(get("/oauth2/authorization/google")).andReturn().getResponse().getRedirectedUrl();
        assertNotEquals(url, second);
        assertNull(first.getRequest().getSession(false));
        String binding = first.getResponse().getHeader("Set-Cookie");
        assertNotNull(binding); assertTrue(binding.startsWith(MongoAuthorizationRequests.COOKIE_NAME + "="));
        assertTrue(binding.contains("HttpOnly")); assertTrue(binding.contains("SameSite=Lax"));
    }
    @Test void callbackWithoutBindingOrWithWrongStateCannotAuthenticate() throws Exception {
        mvc.perform(get("/login/oauth2/code/google").param("code", "not-used").param("state", "forged"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("http://127.0.0.1:5174/login?socialError=failed"));
        var start = mvc.perform(get("/oauth2/authorization/kakao")).andReturn();
        assertNotNull(start.getResponse().getRedirectedUrl());
        assertFalse(start.getResponse().getRedirectedUrl().contains("scope="));
        Cookie binding = new Cookie(MongoAuthorizationRequests.COOKIE_NAME, SocialLoginTickets.randomValue());
        mvc.perform(get("/login/oauth2/code/kakao").cookie(binding).param("code", "not-used").param("state", "wrong-state"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("http://127.0.0.1:5174/login?socialError=failed"));
        verifyNoInteractions(members);
    }
    @Test void oauthSessionAloneCannotAuthorizeApplicationApis() throws Exception {
        UserPrincipal admin = new UserPrincipal(new Document("_id", "admin-id").append("loginId", "admin").append("name", "관리자").append("role", "ADMIN"));
        var authentication = new UsernamePasswordAuthenticationToken(admin, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(authentication));
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/members").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void handoffRequiresOriginAndCookieAndCanOnlyBeExchangedOnce() throws Exception {
        String value = tickets.create(new Document("_id", "member").append("tokenVersion", 2));
        when(tickets.consume(value)).thenReturn(new SocialLoginTickets.Ticket("member", 2, false, java.time.Instant.now().plusSeconds(60)))
            .thenThrow(new kr.dmu.hospital.common.ApiException(401, "expired"));
        when(tickets.consume(isNull())).thenThrow(new kr.dmu.hospital.common.ApiException(401, "expired"));
        Cookie cookie = new Cookie(SocialLoginFlow.COOKIE_NAME, value);
        when(auth.issueSocialSession("member", 2, false)).thenReturn(Map.of("token", "app-jwt", "user", Map.of("id", "member", "authProvider", "GOOGLE")));
        mvc.perform(post("/api/auth/social/exchange").contentType("application/json").content("{}").cookie(cookie)
            .header("Origin", "https://attacker.example")).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/social/exchange").contentType("application/json").content("{}").cookie(cookie))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/social/exchange").contentType("application/json").content("{}").cookie(cookie)
            .header("Origin", "http://127.0.0.1:5174")).andExpect(status().isOk()).andExpect(jsonPath("$.token").value("app-jwt"))
            .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/auth/social/exchange").contentType("application/json").content("{}").cookie(cookie)
            .header("Origin", "http://127.0.0.1:5174")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/social/exchange").contentType("application/json").content("{}")
            .header("Origin", "http://127.0.0.1:5174")).andExpect(status().isUnauthorized());
        verify(auth, times(1)).issueSocialSession("member", 2, false);
    }
    @Test void successUsesHttpOnlyCookieWithoutUrlTokenAndClearsSession() throws Exception {
        Map<String, Object> attributes = Map.of("sub", "google-sub", "name", "시험 이름");
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var oauth = new OAuth2AuthenticationToken(new DefaultOAuth2User(authorities, attributes, "sub"), authorities, "google");
        when(members.signIn("google", attributes)).thenReturn(new Document("_id", "member").append("tokenVersion", 0));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession(); request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityContextHolder.getContext().setAuthentication(oauth);
        flow.success(request, response, oauth);
        assertEquals("http://127.0.0.1:5174/auth/social/callback", response.getRedirectedUrl());
        String cookie = response.getHeader("Set-Cookie"); assertNotNull(cookie);
        assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("SameSite=Lax"));
        assertTrue(cookie.contains("Path=/api/auth/social")); assertTrue(cookie.contains("Max-Age=60"));
        assertTrue(session.isInvalid()); assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
    @Test void unavailableCredentialsDisableProviderAndDifferentCookieHostsAreRejected() {
        SocialLoginSettings missing = new SocialLoginSettings("http://127.0.0.1:5174", "http://127.0.0.1:18081", "", "", "", "");
        assertNull(missing.registration("google")); assertEquals(false, missing.providers().get(0).get("enabled"));
        SocialLoginSettings minimal = new SocialLoginSettings("http://127.0.0.1:5174", "http://127.0.0.1:18081", "google", "secret", "kakao", "secret");
        assertEquals(java.util.Set.of("openid"), minimal.registration("google").getScopes());
        var kakaoScopes = minimal.registration("kakao").getScopes();
        assertTrue(kakaoScopes == null || kakaoScopes.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new SocialLoginSettings("http://localhost:5174", "http://127.0.0.1:18081", "", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new SocialLoginSettings("http://example.com", "http://example.com", "", "", "", ""));
    }
    @Test void pendingProfileCanReadSelfCompleteAndLogoutButCannotUseProtectedFeatures() throws Exception {
        Document document = new Document("_id", "pending-id").append("loginId", "google_pending").append("name", "")
            .append("role", "USER").append("authProvider", "GOOGLE").append("profileComplete", false);
        when(auth.authenticate("pending-token")).thenReturn(UserPrincipal.forSignup(document));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer pending-token")).andExpect(status().isOk())
            .andExpect(jsonPath("$.profileComplete").value(false)).andExpect(jsonPath("$.name").value(""));
        mvc.perform(get("/api/reservations").header("Authorization", "Bearer pending-token")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/members").header("Authorization", "Bearer pending-token")).andExpect(status().isForbidden());
        Map<String, Object> profile = Map.of("name", "입력 이름", "email", "manual@example.com", "phone", "01012345678");
        when(auth.completeSocialProfile(profile)).thenReturn(Map.of("token", "completed-token", "user", Map.of("profileComplete", true)));
        mvc.perform(post("/api/auth/social/profile").header("Authorization", "Bearer pending-token")
            .contentType("application/json").content("{\"name\":\"입력 이름\",\"email\":\"manual@example.com\",\"phone\":\"01012345678\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.profileComplete").value(true));
        verify(auth).completeSocialProfile(profile);
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer pending-token")).andExpect(status().isOk());
        verify(auth).logout();
        document.put("profileComplete", true); document.put("name", "입력 이름"); document.put("phone", "01012345678");
        when(auth.authenticate("complete-token")).thenReturn(new UserPrincipal(document));
        mvc.perform(get("/api/reservations").header("Authorization", "Bearer complete-token")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/social/profile").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
    }
    @Test void identityConflictNeverReturnsProviderAdviceFromSignupEndpoints() throws Exception {
        String message = "이미 가입된 정보가 있어요. 기존에 가입한 방법으로 로그인해 주세요.";
        when(auth.register(anyMap())).thenThrow(new MemberIdentityConflict());
        mvc.perform(post("/api/auth/register").contentType("application/json").content("{}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEMBER_ALREADY_EXISTS"))
            .andExpect(jsonPath("$.existingAuthProvider").doesNotExist())
            .andExpect(jsonPath("$.message").value(message))
            .andExpect(jsonPath("$.*", org.hamcrest.Matchers.hasSize(2)));
        for (String incomingProvider : List.of("GOOGLE", "KAKAO")) {
            UserPrincipal pending = UserPrincipal.forSignup(new Document("_id", "pending").append("name", "")
                .append("role", "USER").append("authProvider", incomingProvider).append("profileComplete", false));
            when(auth.authenticate("pending-token")).thenReturn(pending);
            doThrow(new MemberIdentityConflict()).when(auth).completeSocialProfile(anyMap());
            mvc.perform(post("/api/auth/social/profile").header("Authorization", "Bearer pending-token").contentType("application/json").content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEMBER_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.existingAuthProvider").doesNotExist())
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.*", org.hamcrest.Matchers.hasSize(2)));
        }
    }

    @Test void unmatchedNameContactConflictExposesOnlyGenericMessageForEverySignupEndpoint() throws Exception {
        String message = "이미 가입된 정보가 있어요. 기존에 가입한 방법으로 로그인해 주세요.";
        when(auth.register(anyMap())).thenThrow(new kr.dmu.hospital.common.ApiException(409, message));
        mvc.perform(post("/api/auth/register").contentType("application/json").content("{}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(message))
            .andExpect(jsonPath("$.code").doesNotExist()).andExpect(jsonPath("$.existingAuthProvider").doesNotExist())
            .andExpect(jsonPath("$.*", org.hamcrest.Matchers.hasSize(1)));
        for (String incomingProvider : List.of("GOOGLE", "KAKAO")) {
            UserPrincipal pending = UserPrincipal.forSignup(new Document("_id", "pending").append("role", "USER").append("authProvider", incomingProvider));
            when(auth.authenticate("pending-token")).thenReturn(pending);
            doThrow(new kr.dmu.hospital.common.ApiException(409, message)).when(auth).completeSocialProfile(anyMap());
            mvc.perform(post("/api/auth/social/profile").header("Authorization", "Bearer pending-token").contentType("application/json").content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.code").doesNotExist()).andExpect(jsonPath("$.existingAuthProvider").doesNotExist())
                .andExpect(jsonPath("$.*", org.hamcrest.Matchers.hasSize(1)));
        }
    }

    @Test void nonContactConflictsAndExpiredSignupKeepTheirOriginalResponses() throws Exception {
        doThrow(new kr.dmu.hospital.common.ApiException(409, "이미 사용 중인 아이디입니다.")).when(auth).register(anyMap());
        mvc.perform(post("/api/auth/register").contentType("application/json").content("{}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("이미 사용 중인 아이디입니다."))
            .andExpect(jsonPath("$.code").doesNotExist()).andExpect(jsonPath("$.existingAuthProvider").doesNotExist())
            .andExpect(jsonPath("$.*", org.hamcrest.Matchers.hasSize(1)));
        UserPrincipal pending = UserPrincipal.forSignup(new Document("_id", "pending").append("role", "USER").append("authProvider", "GOOGLE"));
        when(auth.authenticate("pending-token")).thenReturn(pending);
        doThrow(new kr.dmu.hospital.common.ApiException(409, "이미 회원 정보 등록을 완료했습니다.")).when(auth).completeSocialProfile(anyMap());
        mvc.perform(post("/api/auth/social/profile").header("Authorization", "Bearer pending-token").contentType("application/json").content("{}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("이미 회원 정보 등록을 완료했습니다."))
            .andExpect(jsonPath("$.code").doesNotExist()).andExpect(jsonPath("$.existingAuthProvider").doesNotExist())
            .andExpect(jsonPath("$.*", org.hamcrest.Matchers.hasSize(1)));
        doThrow(new kr.dmu.hospital.common.ApiException(401, "가입 인증 시간이 지났어요. 소셜 로그인을 다시 진행해 주세요.")).when(auth).completeSocialProfile(anyMap());
        mvc.perform(post("/api/auth/social/profile").header("Authorization", "Bearer pending-token").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("가입 인증 시간이 지났어요. 소셜 로그인을 다시 진행해 주세요."))
            .andExpect(jsonPath("$.code").doesNotExist()).andExpect(jsonPath("$.existingAuthProvider").doesNotExist())
            .andExpect(jsonPath("$.*", org.hamcrest.Matchers.hasSize(1)));
    }
}
