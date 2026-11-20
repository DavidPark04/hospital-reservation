package kr.dmu.hospital.auth;

import java.text.Normalizer;
import java.util.Locale;
import org.bson.Document;

final class MemberIdentity {
    private MemberIdentity() { }
    static String normalizedName(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC)
            .replaceAll("[\\p{javaWhitespace}\\p{Zs}]+", " ").trim().toLowerCase(Locale.ROOT);
    }
    static String normalizedPhone(String phone) { return phone.trim().replace(" ", "").replace("-", ""); }
    static boolean complete(Document user) {
        String provider = user.getString("authProvider");
        return provider == null || provider.equals("LOCAL")
            || ((provider.equals("GOOGLE") || provider.equals("KAKAO")) && Boolean.TRUE.equals(user.getBoolean("profileComplete")));
    }
}
