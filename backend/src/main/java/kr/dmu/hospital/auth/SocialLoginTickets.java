package kr.dmu.hospital.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
public class SocialLoginTickets {
    static final String COLLECTION = "social_login_tickets";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final MongoTemplate mongo;
    private final Clock clock;
    @Autowired public SocialLoginTickets(MongoTemplate mongo) { this(mongo, Clock.systemUTC()); }
    SocialLoginTickets(MongoTemplate mongo, Clock clock) { this.mongo = mongo; this.clock = clock; }

    public String create(Document user) {
        Instant now = clock.instant();
        if (mongo.count(Query.query(Criteria.where("expiresAt").gt(Date.from(now))), COLLECTION) >= 5000) {
            throw new ApiException(503, "로그인 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        String value = randomValue();
        mongo.insert(new Document("_id", hash(value)).append("userId", user.getString("_id"))
            .append("tokenVersion", user.getInteger("tokenVersion", 0))
            .append("signupPending", Boolean.TRUE.equals(user.getBoolean("registrationPending")))
            .append("expiresAt", Date.from(now.plusSeconds(60))), COLLECTION);
        return value;
    }
    public Ticket consume(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) throw expired();
        Document ticket = mongo.findAndRemove(Query.query(Criteria.where("_id").is(hash(value))
            .and("expiresAt").gt(Date.from(clock.instant()))), Document.class, COLLECTION);
        if (ticket == null) throw expired();
        return new Ticket(ticket.getString("userId"), ticket.getInteger("tokenVersion"),
            Boolean.TRUE.equals(ticket.getBoolean("signupPending")), ticket.getDate("expiresAt").toInstant());
    }
    private static ApiException expired() { return new ApiException(401, "로그인 시간이 만료되었습니다. 다시 로그인해 주세요."); }
    static String randomValue() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String hash(String value) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static class Ticket {
        private final String userId;
        private final int tokenVersion;
        private final Instant expiresAt;
        private final boolean signupPending;
        Ticket(String userId, int tokenVersion, boolean signupPending, Instant expiresAt) {
            this.userId = userId; this.tokenVersion = tokenVersion; this.signupPending = signupPending; this.expiresAt = expiresAt;
        }
        public String getUserId() { return userId; }
        public int getTokenVersion() { return tokenVersion; }
        public boolean isSignupPending() { return signupPending; }
    }
}
