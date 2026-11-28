package kr.dmu.hospital.auth;

import java.util.Map;
import kr.dmu.hospital.common.ApiException;

public class MemberIdentityConflict extends ApiException {
    public MemberIdentityConflict() {
        super(409, "이미 가입된 정보가 있어요. 기존에 가입한 방법으로 로그인해 주세요.");
    }
    public Map<String, Object> toPublic() {
        return Map.of("message", getMessage(), "code", "MEMBER_ALREADY_EXISTS");
    }
}
