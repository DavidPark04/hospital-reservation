package kr.dmu.hospital.auth;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.MongoException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Order(5)
public class UserIndexes implements ApplicationRunner {
    private final MongoTemplate mongo;
    private final TransactionTemplate transaction;

    public UserIndexes(MongoTemplate mongo) {
        this.mongo = mongo;
        transaction = new TransactionTemplate(new MongoTransactionManager(mongo.getMongoDatabaseFactory()));
        transaction.setTimeout(20);
    }

    @Override public void run(ApplicationArguments args) {
        mongo.getCollection(MongoAuthorizationRequests.COLLECTION).createIndex(new Document("expiresAt", 1),
            new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("oauth_authorization_expiry_v1"));
        mongo.getCollection(SocialLoginTickets.COLLECTION).createIndex(new Document("expiresAt", 1),
            new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("social_login_ticket_expiry_v1"));
        mongo.getCollection(SocialSignups.COLLECTION).createIndex(new Document("socialIdentity", 1),
            new IndexOptions().unique(true).name("social_signup_identity_v1"));
        mongo.getCollection(SocialSignups.COLLECTION).createIndex(new Document("expiresAt", 1),
            new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("social_signup_expiry_v1"));
        mongo.getCollection("users").createIndex(new Document("loginId", 1),
                new IndexOptions().unique(true).name("user_login_id_v1"));
        mongo.getCollection("users").createIndex(new Document("socialIdentity", 1),
                new IndexOptions().unique(true).name("user_social_identity_v1")
                    .partialFilterExpression(new Document("socialIdentity", new Document("$type", "string"))));
        migrateLegacySocialSignups();
        backfillMemberIdentities();
        try {
            mongo.getCollection("users").createIndex(new Document("nameNormalized", 1).append("phone", 1),
                new IndexOptions().unique(true).name("user_name_phone_v1").partialFilterExpression(
                    new Document("memberIdentityClaimed", true).append("nameNormalized", new Document("$type", "string"))
                        .append("phone", new Document("$type", "string"))));
        } catch (MongoException conflict) {
            if (conflict.getCode() == 11000) throw duplicateExistingMembers();
            throw conflict;
        }
    }
    private void migrateLegacySocialSignups() {
        List<Document> unfinished = mongo.find(legacyPending(null), Document.class, "users");
        for (Document user : unfinished) requireNoSignupReferences(user.getString("_id"));
        for (Document original : unfinished) {
            transaction.executeWithoutResult(status -> {
                Query selection = legacyPending(original.getString("_id"));
                Document user = mongo.findOne(selection, Document.class, "users");
                if (user == null) return;
                requireNoSignupReferences(user.getString("_id"));
                String provider = user.getString("authProvider"); String subject = user.getString("socialSubject");
                if (subject == null || subject.isBlank() || !(provider + ":" + subject).equals(user.getString("socialIdentity"))) {
                    throw new IllegalStateException("An unfinished social signup has an invalid provider identity; migration stopped without removing it.");
                }
                SocialSignups.begin(mongo, provider, subject);
                if (mongo.remove(selection, "users").getDeletedCount() != 1) {
                    throw new IllegalStateException("Unfinished social signup changed during migration; no account was removed.");
                }
            });
        }
    }
    private Query legacyPending(String id) {
        Criteria criteria = Criteria.where("role").is("USER").and("authProvider").in("GOOGLE", "KAKAO").and("profileComplete").ne(true);
        if (id != null) criteria.and("_id").is(id);
        return Query.query(criteria);
    }
    private void requireNoSignupReferences(String id) {
        boolean linked = mongo.exists(Query.query(Criteria.where("userId").is(id)), "reservations")
            || mongo.exists(Query.query(Criteria.where("userId").is(id)), "requests")
            || mongo.exists(Query.query(new Criteria().orOperator(Criteria.where("userId").is(id), Criteria.where("actorId").is(id),
                Criteria.where("before.userId").is(id), Criteria.where("after.userId").is(id))), "reservationEvents");
        if (linked) throw new IllegalStateException("An unfinished social signup has reservation or audit references; migration stopped without removing it.");
    }
    private void backfillMemberIdentities() {
        List<Document> identities = new ArrayList<>();
        List<String> unclaimed = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Document user : mongo.find(new Query(), Document.class, "users")) {
            String id = user.getString("_id");
            if (!MemberIdentity.complete(user) || !(user.get("phone") instanceof String rawPhone) || rawPhone.isBlank()) {
                unclaimed.add(id); continue;
            }
            String phone = MemberIdentity.normalizedPhone(rawPhone);
            String name = user.get("name") instanceof String value ? MemberIdentity.normalizedName(value) : "";
            if (!phone.matches("0[0-9]{8,10}") || name.isBlank()) {
                throw new IllegalStateException("A completed member has invalid identity fields. Review existing member data before startup.");
            }
            if (!used.add(name + "\u0000" + phone)) throw duplicateExistingMembers();
            identities.add(new Document("_id", id).append("nameNormalized", name).append("phone", phone));
        }
        // Check every existing member first. A conflict never triggers automatic deletion or account merging.
        try {
            for (String id : unclaimed) mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().unset("nameNormalized").unset("memberIdentityClaimed"), "users");
            for (Document identity : identities) mongo.updateFirst(Query.query(Criteria.where("_id").is(identity.getString("_id"))),
                new Update().set("nameNormalized", identity.getString("nameNormalized")).set("phone", identity.getString("phone"))
                    .set("memberIdentityClaimed", true), "users");
        } catch (DuplicateKeyException conflict) { throw duplicateExistingMembers(); }
    }
    private static IllegalStateException duplicateExistingMembers() {
        // Do not include names, phone numbers, or the MongoDB duplicate-key error in logs.
        return new IllegalStateException("Existing completed members share a normalized name and phone. Resolve duplicate records before startup; no accounts were deleted or merged.");
    }
}
