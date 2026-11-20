package kr.dmu.hospital.auth;

import kr.dmu.hospital.common.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {
    private CurrentUser() { }
    public static UserPrincipal optional() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal user) return user;
        return null;
    }
    public static UserPrincipal require() {
        UserPrincipal user = optional();
        if (user == null) throw new ApiException(401, "로그인이 필요합니다.");
        return user;
    }
    public static UserPrincipal requireAdmin() {
        UserPrincipal user = require();
        if (!user.isProfileComplete() || !"ADMIN".equals(user.getRole())) throw new ApiException(403, "관리자만 이용할 수 있습니다.");
        return user;
    }
}
