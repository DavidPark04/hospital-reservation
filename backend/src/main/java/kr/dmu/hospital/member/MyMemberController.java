package kr.dmu.hospital.member;

import kr.dmu.hospital.auth.CurrentUser;
import kr.dmu.hospital.auth.UserPrincipal;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/members/me")
public class MyMemberController {
    private final MemberService members;

    public MyMemberController(MemberService members) {
        this.members = members;
    }

    @DeleteMapping
    public Document withdraw(@RequestBody(required = false) Map<String, Object> body) {
        UserPrincipal user = CurrentUser.require();
        if (!user.isProfileComplete() || user.isSignupPending()) {
            throw new ApiException(403, "가입을 완료한 회원만 탈퇴할 수 있습니다.");
        }
        String password = body != null && body.get("password") instanceof String value ? value : null;
        String proof = body != null && body.get("phoneVerificationToken") instanceof String value ? value : null;
        return members.withdrawVerified(user.getId(), password, proof);
    }
}
