package kr.dmu.hospital.member;

import com.mongodb.MongoException;
import kr.dmu.hospital.booking.BookingService;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class MemberService {
    private static final int RETRIES = 8;
    private final MongoTemplate mongo;
    private final BookingService booking;
    private final TransactionTemplate transaction;

    public MemberService(MongoTemplate mongo, BookingService booking, MongoTransactionManager transactionManager) {
        this.mongo = mongo;
        this.booking = booking;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setTimeout(20);
    }

    public List<Document> list(String adminId) {
        requireAdmin(adminId);
        Criteria completed = new Criteria().orOperator(
                Criteria.where("authProvider").is(null),
                Criteria.where("authProvider").is("LOCAL"),
                Criteria.where("authProvider").in("GOOGLE", "KAKAO").and("profileComplete").is(true));
        Query query = Query.query(completed).with(Sort.by(Sort.Direction.DESC, "createdAt", "_id"));
        query.fields().include("name").include("loginId").include("role").include("createdAt")
                .include("authProvider").include("email").include("phone").include("profileComplete");
        List<Document> users = mongo.find(query, Document.class, "users");
        List<Document> result = new ArrayList<>();
        for (Document user : users) {
            Date createdAt = user.getDate("createdAt");
            String provider = user.getString("authProvider");
            if (!"GOOGLE".equals(provider) && !"KAKAO".equals(provider)) provider = "LOCAL";
            boolean complete = "LOCAL".equals(provider) || Boolean.TRUE.equals(user.getBoolean("profileComplete"));
            String name = complete ? user.getString("name") : "";
            Document member = new Document("id", user.getString("_id")).append("name", name == null ? "" : name)
                    .append("loginId", user.getString("loginId")).append("role", user.getString("role"))
                    .append("authProvider", provider).append("profileComplete", complete)
                    .append("createdAt", createdAt == null ? null : createdAt.toInstant().toString());
            String email = user.getString("email");
            if (complete && email != null && !email.isBlank()) member.append("email", email);
            String phone = user.getString("phone");
            if (complete && phone != null && !phone.isBlank()) member.append("phone", phone);
            result.add(member);
        }
        return result;
    }

    public Document delete(String adminId, String userId) {
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            try {
                return transaction.execute(status -> {
                    requireAdmin(adminId);
                    if (adminId.equals(userId)) {
                        throw new ApiException(403, "현재 로그인한 관리자 계정은 삭제할 수 없습니다.");
                    }
                    Document user = mongo.findById(userId, Document.class, "users");
                    if (user == null) {
                        throw new ApiException(404, "회원을 찾을 수 없습니다.");
                    }
                    if (!"USER".equals(user.getString("role"))) {
                        throw new ApiException(403, "관리자 계정은 삭제할 수 없습니다.");
                    }
                    // Booking mutations also write this user document, so an in-flight
                    // reservation must finish before deletion or retry after it and fail.
                    long deleted = mongo.remove(Query.query(Criteria.where("_id").is(userId)
                            .and("role").is("USER")), "users").getDeletedCount();
                    if (deleted != 1) {
                        throw new ApiException(409, "회원 정보가 변경되었습니다. 목록을 다시 확인해 주세요.");
                    }
                    int canceled = booking.cancelMemberReservations(userId, adminId);
                    mongo.remove(Query.query(Criteria.where("userId").is(userId)), "requests");
                    return new Document("message", "회원을 삭제했습니다.").append("deletedUserId", userId)
                            .append("canceledReservations", canceled);
                });
            } catch (RuntimeException error) {
                if (error instanceof ApiException || !retryable(error)) {
                    throw error;
                }
                if (attempt == RETRIES - 1) {
                    throw new ApiException(409, "예약 요청이 처리 중입니다. 회원 목록을 확인한 뒤 다시 시도해 주세요.");
                }
                pause(attempt);
            }
        }
        throw new ApiException(409, "회원 목록을 확인한 뒤 다시 시도해 주세요.");
    }

    private void requireAdmin(String adminId) {
        Document admin = mongo.findById(adminId, Document.class, "users");
        if (admin == null) {
            throw new ApiException(401, "로그인이 필요합니다.");
        }
        if (!"ADMIN".equals(admin.getString("role"))) {
            throw new ApiException(403, "관리자만 이용할 수 있습니다.");
        }
    }

    private boolean retryable(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof MongoException mongoError
                    && (mongoError.hasErrorLabel("TransientTransactionError")
                    || mongoError.hasErrorLabel("UnknownTransactionCommitResult")
                    || mongoError.getCode() == 112 || mongoError.getCode() == 251)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private void pause(int attempt) {
        try {
            Thread.sleep(15L * (attempt + 1));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "요청이 중단되었습니다. 회원 목록을 확인해 주세요.");
        }
    }
}
