package kr.dmu.hospital.auth;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

final class SocialSignups {
    static final String COLLECTION = "social_signups";
    private SocialSignups() { }
    static Document begin(MongoTemplate mongo, String provider, String subject) {
        Query identity = Query.query(Criteria.where("socialIdentity").is(provider + ":" + subject));
        for (int attempt = 0; attempt < 3; attempt++) {
            Update update = new Update().setOnInsert("_id", "signup_" + UUID.randomUUID())
                .setOnInsert("createdAt", new Date()).set("authProvider", provider).set("socialSubject", subject)
                .set("socialIdentity", provider + ":" + subject).set("expiresAt", Date.from(Instant.now().plusSeconds(30 * 60)))
                .inc("tokenVersion", 1);
            try {
                Document signup = mongo.findAndModify(identity, update, FindAndModifyOptions.options().upsert(true).returnNew(true), Document.class, COLLECTION);
                // This transient marker is returned to the handoff, not persisted in the temporary collection.
                return signup.append("registrationPending", true);
            } catch (DuplicateKeyException race) { if (attempt == 2) throw race; }
        }
        throw new IllegalStateException("Could not start social signup.");
    }
    static boolean live(Document signup) {
        return signup != null && signup.getDate("expiresAt") != null && signup.getDate("expiresAt").after(new Date());
    }
}
