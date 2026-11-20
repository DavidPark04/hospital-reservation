package kr.dmu.hospital.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

/** Short-lived OAuth protocol state shared by all backend instances, bound to the initiating browser. */
@Component
public class MongoAuthorizationRequests implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    static final String COLLECTION = "oauth_authorization_requests";
    static final String COOKIE_NAME = "hospital_oauth_binding";
    private final MongoTemplate mongo;
    private final SocialLoginSettings settings;
    private final Clock clock;

    @Autowired public MongoAuthorizationRequests(MongoTemplate mongo, SocialLoginSettings settings) {
        this(mongo, settings, Clock.systemUTC());
    }
    MongoAuthorizationRequests(MongoTemplate mongo, SocialLoginSettings settings, Clock clock) {
        this.mongo = mongo; this.settings = settings; this.clock = clock;
    }

    @Override public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorization,
            HttpServletRequest request, HttpServletResponse response) {
        if (authorization == null) { removeAuthorizationRequest(request, response); return; }
        String binding = SocialLoginTickets.randomValue();
        String provider = authorization.getAttribute("registration_id");
        if (!List.of("google", "kakao").contains(provider) || !validState(authorization.getState())) {
            throw new IllegalArgumentException("Invalid OAuth authorization request.");
        }
        // Never serialize a Java object graph, provider tokens, profile data, or the raw browser binding.
        Document stored = new Document("_id", SocialLoginTickets.hash(authorization.getState()))
            .append("bindingHash", SocialLoginTickets.hash(binding)).append("provider", provider)
            .append("authorizationUri", authorization.getAuthorizationUri()).append("clientId", authorization.getClientId())
            .append("redirectUri", authorization.getRedirectUri()).append("scopes", List.copyOf(authorization.getScopes()))
            .append("parameters", strings(authorization.getAdditionalParameters(), List.of("nonce", "code_challenge", "code_challenge_method", "prompt")))
            .append("attributes", strings(authorization.getAttributes(), List.of("nonce", "code_verifier",
                "withdrawal_user_id", "withdrawal_identity", "withdrawal_version")))
            .append("expiresAt", Date.from(clock.instant().plusSeconds(300)));
        mongo.insert(stored, COLLECTION);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(binding, 300).toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @Override public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        Query query = selection(request);
        return query == null ? null : restore(mongo.findOne(query, Document.class, COLLECTION), request.getParameter("state"));
    }

    @Override public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        Query query = selection(request);
        Document stored = query == null ? null : mongo.findAndRemove(query, Document.class, COLLECTION);
        if (stored != null && stored.get("attributes", Document.class).containsKey("withdrawal_user_id")) {
            request.setAttribute(SocialWithdrawalService.REQUEST_ATTRIBUTE, stored.get("attributes", Document.class));
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
        return restore(stored, request.getParameter("state"));
    }

    private Query selection(HttpServletRequest request) {
        String state = request.getParameter("state");
        if (!validState(state)) return null;
        String binding = null;
        if (request.getCookies() != null) for (Cookie cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) { binding = cookie.getValue(); break; }
        }
        if (binding == null || !binding.matches("[A-Za-z0-9_-]{43}")) return null;
        String provider = null;
        for (String candidate : List.of("google", "kakao")) {
            if ((request.getContextPath() + "/login/oauth2/code/" + candidate).equals(request.getRequestURI())) provider = candidate;
        }
        if (provider == null) return null;
        return Query.query(Criteria.where("_id").is(SocialLoginTickets.hash(state))
            .and("bindingHash").is(SocialLoginTickets.hash(binding)).and("provider").is(provider)
            .and("expiresAt").gt(Date.from(clock.instant())));
    }

    private OAuth2AuthorizationRequest restore(Document stored, String state) {
        if (stored == null) return null;
        Map<String, Object> attributes = new HashMap<>(stored.get("attributes", Document.class));
        attributes.put("registration_id", stored.getString("provider"));
        return OAuth2AuthorizationRequest.authorizationCode().authorizationUri(stored.getString("authorizationUri"))
            .clientId(stored.getString("clientId")).redirectUri(stored.getString("redirectUri"))
            .scopes(new HashSet<>(stored.getList("scopes", String.class))).state(state)
            .additionalParameters(new HashMap<>(stored.get("parameters", Document.class))).attributes(attributes).build();
    }

    private Document strings(Map<String, Object> values, List<String> allowed) {
        Document result = new Document();
        for (String key : allowed) if (values.get(key) instanceof String value) result.put(key, value);
        return result;
    }
    private boolean validState(String state) { return state != null && state.length() >= 20 && state.length() <= 512; }
    private ResponseCookie cookie(String value, long seconds) {
        return ResponseCookie.from(COOKIE_NAME, value).httpOnly(true).secure(settings.secureCookies())
            .sameSite("Lax").path("/login/oauth2/code").maxAge(seconds).build();
    }
}
