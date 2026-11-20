package kr.dmu.hospital.auth;

import java.util.Map;
import java.util.LinkedHashMap;
import org.bson.Document;

public class UserPrincipal {
    private final String id;
    private final String name;
    private final String loginId;
    private final String role;
    private final String authProvider;
    private final String email;
    private final String phone;
    private final boolean profileComplete;
    private final boolean signupPending;
    private final int tokenVersion;
    public UserPrincipal(Document user) {
        this(user, false);
    }
    public static UserPrincipal forSignup(Document signup) { return new UserPrincipal(signup, true); }
    private UserPrincipal(Document user, boolean signupPending) {
        this.signupPending = signupPending;
        tokenVersion = user.getInteger("tokenVersion", 0);
        id = user.getString("_id");
        loginId = signupPending ? "" : user.getString("loginId"); role = signupPending ? "USER" : user.getString("role");
        authProvider = user.getString("authProvider") == null ? "LOCAL" : user.getString("authProvider");
        profileComplete = !signupPending && ("LOCAL".equals(authProvider) || Boolean.TRUE.equals(user.getBoolean("profileComplete")));
        name = profileComplete && user.getString("name") != null ? user.getString("name") : "";
        email = profileComplete ? user.getString("email") : null;
        phone = profileComplete ? user.getString("phone") : null;
    }
    public String getId() { return id; }
    public String getName() { return name; }
    public String getLoginId() { return loginId; }
    public String getRole() { return role; }
    public String getAuthProvider() { return authProvider; }
    public boolean isProfileComplete() { return profileComplete; }
    public boolean isSignupPending() { return signupPending; }
    public int getTokenVersion() { return tokenVersion; }
    public Map<String, Object> toPublic() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id); result.put("name", name); result.put("loginId", loginId); result.put("role", role);
        result.put("authProvider", authProvider);
        result.put("profileComplete", profileComplete); result.put("emailVerified", false);
        if (email != null && !email.isBlank()) result.put("email", email);
        if (phone != null && !phone.isBlank()) result.put("phone", phone);
        return result;
    }
}
