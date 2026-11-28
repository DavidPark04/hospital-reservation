package kr.dmu.hospital.auth;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Synthetic provider receipts for disposable local integration databases only. */
public final class TestContactProofs {
    private TestContactProofs() { }

    public static Map<String, Object> signup(MongoTemplate mongo, Map<String, Object> body) {
        return signup(mongo, body, "", 0);
    }

    public static Map<String, Object> socialSignup(MongoTemplate mongo, Map<String, Object> body) {
        UserPrincipal current = CurrentUser.optional();
        return signup(mongo, body, current == null ? "" : current.getId(), current == null ? 0 : current.getTokenVersion());
    }

    private static Map<String, Object> signup(MongoTemplate mongo, Map<String, Object> body, String userId, int version) {
        if (body == null) return null;
        Map<String, Object> result = new HashMap<>(body);
        result.put("emailVerificationToken", seed(mongo, "SIGNUP", "EMAIL", MemberIdentity.normalizedEmail(text(body, "email")), userId, version));
        result.put("phoneVerificationToken", seed(mongo, "SIGNUP", "PHONE", MemberIdentity.normalizedPhone(text(body, "phone")), userId, version));
        return result;
    }

    public static Map<String, Object> phone(MongoTemplate mongo, String purpose, Map<String, Object> body) {
        if (body == null) return null;
        UserPrincipal current = CurrentUser.optional();
        if (current == null || !"USER".equals(current.getRole())) return body;
        Map<String, Object> result = new HashMap<>(body);
        result.put("phoneVerificationToken", phoneToken(mongo, purpose, current.getId()));
        return result;
    }

    public static String phoneToken(MongoTemplate mongo, String purpose, String userId) {
        Document user = mongo.findById(userId, Document.class, "users");
        return seed(mongo, purpose, "PHONE", user == null ? "" : user.getString("phone"), userId,
                user == null ? 0 : user.getInteger("tokenVersion", 0));
    }

    public static String seed(MongoTemplate mongo, String purpose, String channel, String contact, String userId, int version) {
        String token = SocialLoginTickets.randomValue();
        mongo.insert(new Document("_id", SocialLoginTickets.hash(token)).append("purpose", purpose).append("channel", channel)
                .append("contact", contact).append("userId", userId).append("tokenVersion", version)
                .append("createdAt", Date.from(Instant.now())).append("expiresAt", Date.from(Instant.now().plusSeconds(600))),
                ContactVerificationService.PROOFS);
        return token;
    }

    private static String text(Map<String, Object> body, String field) {
        return body.get(field) instanceof String value ? value.trim() : "";
    }
}
