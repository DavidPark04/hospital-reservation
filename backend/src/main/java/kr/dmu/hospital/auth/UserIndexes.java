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
        List<Document> contacts = memberContacts();
        for (String collection : List.of(ContactVerificationService.CHALLENGES, ContactVerificationService.PROOFS,
                ContactVerificationService.LIMITS, ContactVerificationService.PHONE_RECEIPTS)) {
            mongo.getCollection(collection).createIndex(new Document("expiresAt", 1),
                    new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("contact_verification_expiry_v1"));
        }
        mongo.getCollection(MongoAuthorizationRequests.COLLECTION).createIndex(new Document("expiresAt", 1),
            new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("oauth_authorization_expiry_v1"));
        mongo.getCollection(SocialLoginTickets.COLLECTION).createIndex(new Document("expiresAt", 1),
            new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("social_login_ticket_expiry_v1"));
        mongo.getCollection(SocialWithdrawalService.COLLECTION).createIndex(new Document("expiresAt", 1),
            new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("social_withdrawal_proof_expiry_v1"));
        mongo.getCollection(SocialSignups.COLLECTION).createIndex(new Document("socialIdentity", 1),
            new IndexOptions().unique(true).name("social_signup_identity_v1"));
        mongo.getCollection(SocialSignups.COLLECTION).createIndex(new Document("expiresAt", 1),
            new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("social_signup_expiry_v1"));
        mongo.getCollection(HospitalAccountService.VERIFICATIONS).createIndex(new Document("expiresAt", 1),
            new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("hospital_signup_verification_expiry_v1"));
        mongo.getCollection(HospitalAccountService.REGISTRATIONS).createIndex(new Document("businessNumber", 1),
            new IndexOptions().unique(true).name("hospital_registration_business_v1"));
        mongo.getCollection(HospitalAccountService.REGISTRATIONS).createIndex(new Document("userId", 1),
            new IndexOptions().unique(true).name("hospital_registration_owner_v1"));
        mongo.getCollection(HospitalAccountService.REGISTRATIONS).createIndex(new Document("status", 1).append("_id", 1),
            new IndexOptions().name("hospital_registration_status_v1"));
        mongo.getCollection("users").createIndex(new Document("loginId", 1),
                new IndexOptions().unique(true).name("user_login_id_v1"));
        mongo.getCollection("users").createIndex(new Document("socialIdentity", 1),
                new IndexOptions().unique(true).name("user_social_identity_v1")
                    .partialFilterExpression(new Document("socialIdentity", new Document("$type", "string"))));
        migrateLegacySocialSignups();
        backfillMemberContacts(contacts);
        try {
            mongo.getCollection("users").createIndex(new Document("phone", 1),
                new IndexOptions().unique(true).name("user_phone_v1").partialFilterExpression(
                    new Document("memberIdentityClaimed", true).append("phone", new Document("$type", "string").append("$gt", ""))));
            mongo.getCollection("users").createIndex(new Document("emailNormalized", 1),
                new IndexOptions().unique(true).name("user_email_v1").partialFilterExpression(
                    new Document("memberIdentityClaimed", true).append("emailNormalized", new Document("$type", "string").append("$gt", ""))));
        } catch (MongoException conflict) {
            if (conflict.getCode() == 11000) throw duplicateExistingMembers();
            throw conflict;
        }
        // Keep the old protection until both new unique indexes are ready.
        for (Document index : mongo.getCollection("users").listIndexes()) {
            if ("user_name_phone_v1".equals(index.getString("name"))) {
                try { mongo.getCollection("users").dropIndex("user_name_phone_v1"); }
                catch (MongoException error) { if (error.getCode() != 27) throw error; }
                break;
            }
        }
    }
    private void migrateLegacySocialSignups() {
        List<Document> unfinished = mongo.find(legacyPending(null), Document.class, "users");
        for (Document user : unfinished) requireNoSignupReferences(user.getString("_id"));
        for (Document original : unfinished) {
            for (int attempt = 0; attempt < 8; attempt++) {
                try {
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
                    break;
                } catch (RuntimeException error) {
                    if (!retryableMigration(error)) throw error;
                    if (attempt == 7) throw new IllegalStateException("Concurrent social signup migration did not finish. Retry startup; member data was preserved.");
                    try { Thread.sleep(15L * (attempt + 1)); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Social signup migration was interrupted. Retry startup; member data was preserved.");
                    }
                }
            }
        }
    }
    private boolean retryableMigration(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof DuplicateKeyException) return true;
            if (cause instanceof MongoException mongoError && (mongoError.hasErrorLabel("TransientTransactionError")
                    || mongoError.getCode() == 112 || mongoError.getCode() == 251)) return true;
        }
        return false;
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
    private List<Document> memberContacts() {
        List<Document> contacts = new ArrayList<>();
        Set<String> phones = new HashSet<>();
        Set<String> emails = new HashSet<>();
        for (Document user : mongo.find(new Query(), Document.class, "users")) {
            // Hospital phone numbers are official facility contact fields, not personal identity claims.
            if ("HOSPITAL".equals(user.getString("role"))) continue;
            Document contact = new Document("_id", user.getString("_id"));
            if (MemberIdentity.complete(user)) {
                Object rawPhone = user.get("phone");
                if (rawPhone != null && !(rawPhone instanceof String)) throw invalidExistingContact();
                if (rawPhone instanceof String value && value.isBlank()) contact.append("clearBlankPhone", true);
                if (rawPhone instanceof String value && !value.isBlank()) {
                    String phone = MemberIdentity.normalizedPhone(value);
                    if (!phone.matches("0[0-9]{8,10}")) throw invalidExistingContact();
                    if (!phones.add(phone)) throw duplicateExistingMembers();
                    contact.append("phone", phone);
                }
                Object rawEmail = user.get("email");
                if (rawEmail != null && !(rawEmail instanceof String)) throw invalidExistingContact();
                if (rawEmail instanceof String value && !value.isBlank()) {
                    String email = value.trim();
                    if (!MemberIdentity.validEmail(email)) throw invalidExistingContact();
                    String normalizedEmail = MemberIdentity.normalizedEmail(email);
                    if (!emails.add(normalizedEmail)) throw duplicateExistingMembers();
                    contact.append("emailNormalized", normalizedEmail);
                }
                if (user.get("name") instanceof String name && !name.isBlank()) {
                    contact.append("nameNormalized", MemberIdentity.normalizedName(name));
                }
            }
            contacts.add(contact);
        }
        return contacts;
    }
    private void backfillMemberContacts(List<Document> contacts) {
        // The read-only preflight runs before any migration or index changes.
        // Missing legacy contact details stay missing; they are never fabricated.
        try {
            for (Document contact : contacts) {
                Update update = new Update();
                if (contact.containsKey("phone")) update.set("phone", contact.getString("phone"));
                else if (Boolean.TRUE.equals(contact.getBoolean("clearBlankPhone"))) update.unset("phone");
                if (contact.containsKey("emailNormalized")) update.set("emailNormalized", contact.getString("emailNormalized"));
                else update.unset("emailNormalized");
                if (contact.containsKey("nameNormalized")) update.set("nameNormalized", contact.getString("nameNormalized"));
                else update.unset("nameNormalized");
                if (contact.containsKey("phone") || contact.containsKey("emailNormalized")) update.set("memberIdentityClaimed", true);
                else update.unset("memberIdentityClaimed");
                mongo.updateFirst(Query.query(Criteria.where("_id").is(contact.getString("_id"))), update, "users");
            }
        } catch (DuplicateKeyException conflict) { throw duplicateExistingMembers(); }
    }
    private static IllegalStateException invalidExistingContact() {
        return new IllegalStateException("A completed member has an invalid phone or email. Review existing member data before startup.");
    }
    private static IllegalStateException duplicateExistingMembers() {
        // Do not include phone numbers, emails, or the MongoDB duplicate-key error in logs.
        return new IllegalStateException("Existing completed members share a phone or email. Resolve duplicate records before startup; no accounts were deleted or merged.");
    }
}
