package kr.dmu.hospital.auth;

import java.util.Locale;
import java.util.Map;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class SocialMemberService {
    private final MongoTemplate mongo;
    public SocialMemberService(MongoTemplate mongo) { this.mongo = mongo; }

    /** OAuth proves a provider identity; it does not create a formal member. */
    public Document signIn(String providerId, Map<String, Object> attributes) {
        String identity = identity(providerId, attributes);
        String provider = providerId.toUpperCase(Locale.ROOT);
        String subject = identity.substring(provider.length() + 1);
        Document member = mongo.findOne(Query.query(Criteria.where("socialIdentity").is(identity)
            .and("profileComplete").is(true)), Document.class, "users");
        if (member != null) return member;
        Document signup = SocialSignups.begin(mongo, provider, subject);
        // Another callback may have completed signup between the first lookup and temporary storage.
        member = mongo.findOne(Query.query(Criteria.where("socialIdentity").is(identity)
            .and("profileComplete").is(true)), Document.class, "users");
        if (member != null) {
            mongo.remove(Query.query(Criteria.where("_id").is(signup.getString("_id")).and("tokenVersion").is(signup.getInteger("tokenVersion"))), SocialSignups.COLLECTION);
            return member;
        }
        return signup;
    }

    /** Read only the immutable identity; reauthentication must never create a signup. */
    static String identity(String providerId, Map<String, Object> attributes) {
        String provider = providerId.toUpperCase(Locale.ROOT);
        String subject;
        if (provider.equals("GOOGLE")) {
            subject = attributes.get("sub") instanceof String value && !value.isBlank() && value.length() <= 255 ? value : null;
        } else if (provider.equals("KAKAO")) {
            Object id = attributes.get("id");
            subject = id instanceof Number || id instanceof String ? id.toString() : null;
            if (subject != null && !subject.matches("[0-9]{1,30}")) subject = null;
        } else { throw new ApiException(400, "지원하지 않는 로그인 방식입니다."); }
        if (subject == null) throw new ApiException(401, "소셜 계정 정보를 확인할 수 없습니다.");
        return provider + ":" + subject;
    }
}
