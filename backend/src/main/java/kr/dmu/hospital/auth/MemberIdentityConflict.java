package kr.dmu.hospital.auth;

import java.util.Map;
import kr.dmu.hospital.common.ApiException;

public class MemberIdentityConflict extends ApiException {
    private final String existingAuthProvider;
    public MemberIdentityConflict(String provider) {
        super(409, message(provider));
        existingAuthProvider = "GOOGLE".equals(provider) || "KAKAO".equals(provider) ? provider : "LOCAL";
    }
    public Map<String, Object> toPublic() {
        return Map.of("message", getMessage(), "code", "MEMBER_ALREADY_EXISTS", "existingAuthProvider", existingAuthProvider);
    }
    private static String message(String provider) {
        if ("KAKAO".equals(provider)) return "이미 카카오로 가입된 계정이 있어요. 카카오로 로그인해 주세요.";
        if ("GOOGLE".equals(provider)) return "이미 구글로 가입된 계정이 있어요. 구글로 로그인해 주세요.";
        return "이미 일반 가입으로 등록된 계정이 있어요. 아이디로 로그인해 주세요.";
    }
}
