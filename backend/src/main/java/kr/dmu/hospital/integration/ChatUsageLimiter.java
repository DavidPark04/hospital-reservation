package kr.dmu.hospital.integration;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.UpdateOptions;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Daily quotas shared by all application instances without storing caller details. */
@Component
public class ChatUsageLimiter implements ApplicationRunner {
    static final String COLLECTION = "chat_usage";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final MongoCollection<Document> counts;
    private final byte[] secret;
    private final Clock clock;

    @Autowired
    public ChatUsageLimiter(MongoTemplate mongo, @Value("${app.jwt-secret}") String secret) {
        this(mongo, secret, Clock.systemUTC());
    }

    ChatUsageLimiter(MongoTemplate mongo, String secret, Clock clock) {
        this.counts = mongo.getCollection(COLLECTION);
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        counts.createIndex(new Document("expiresAt", 1),
                new IndexOptions().name("chat_usage_expiry_v1").expireAfter(0L, TimeUnit.SECONDS));
    }

    public void checkLimit(String callerKey, boolean authenticated) {
        LocalDate day = LocalDate.now(clock.withZone(SEOUL));
        String id = counterId(day, callerKey);
        int limit = authenticated ? 60 : 20;
        Document filter = new Document("_id", id).append("count", new Document("$lt", limit));
        Document increment = new Document("$inc", new Document("count", 1));
        Document upsert = new Document(increment).append("$setOnInsert", new Document("expiresAt",
                Date.from(day.plusDays(1).atStartOfDay(SEOUL).toInstant())));
        try {
            counts.updateOne(filter, upsert, new UpdateOptions().upsert(true));
            return;
        } catch (MongoWriteException exception) {
            if (exception.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw exception;
        }
        // Another instance inserted the day's counter, or the quota is already full.
        if (counts.updateOne(filter, increment).getModifiedCount() == 1) return;
        throw new ApiException(429, "오늘의 상담 이용 횟수를 모두 사용했습니다. 병원 직접 검색은 계속 이용할 수 있어요.");
    }

    private String counterId(LocalDate day, String callerKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(("chat-usage\n" + day + "\n" + callerKey).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("상담 이용 횟수 식별값을 생성하지 못했습니다.", exception);
        }
    }
}
