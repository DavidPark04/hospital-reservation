package kr.dmu.hospital.auth;

import java.text.Normalizer;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bson.Document;

final class HospitalBusinessMatch {
    private static final Pattern ROAD = Pattern.compile("^(.+?)\\s+([^\\s,()]+(?:대로|로|길))\\s+(\\d+(?:-\\d+)?)(?=[\\s,()]|$)");
    private HospitalBusinessMatch() { }

    static boolean matches(Map<String, String> business, Document hospital, String number) {
        if (!number.equals(business.get("saeopjaDrno")) || !Boolean.TRUE.equals(hospital.getBoolean("active"))) return false;
        if (!HospitalName.matches(business.get("saeopjangNm"), hospital.getString("name"))) return false;
        Document api = hospital.get("apiFields", Document.class);
        String post = business.getOrDefault("post", "").trim();
        if (api == null || post.isBlank() || !post.equals(api.getString("postNo"))) return false;
        String first = normalizedAddress(business.get("addr"));
        String second = normalizedAddress(hospital.getString("address"));
        Matcher a = ROAD.matcher(first);
        Matcher b = ROAD.matcher(second);
        if (!a.find() || !b.find()) return false;
        for (int i = 1; i <= 3; i++) if (!a.group(i).equals(b.group(i))) return false;
        return unit(first, "층").equals(unit(second, "층")) && unit(first, "호").equals(unit(second, "호"));
    }

    private static String normalizedAddress(String address) {
        String value = Normalizer.normalize(address == null ? "" : address, Normalizer.Form.NFKC)
                .replaceAll("[\\p{javaWhitespace}\\p{Zs}]+", " ").trim();
        String[][] provinces = {{"서울특별시", "서울"}, {"부산광역시", "부산"}, {"대구광역시", "대구"},
                {"인천광역시", "인천"}, {"광주광역시", "광주"}, {"대전광역시", "대전"}, {"울산광역시", "울산"},
                {"세종특별자치시", "세종"}, {"경기도", "경기"}, {"강원특별자치도", "강원"}, {"강원도", "강원"},
                {"충청북도", "충북"}, {"충청남도", "충남"}, {"전북특별자치도", "전북"}, {"전라북도", "전북"},
                {"전라남도", "전남"}, {"경상북도", "경북"}, {"경상남도", "경남"}, {"제주특별자치도", "제주"}};
        for (String[] province : provinces) if (value.startsWith(province[0] + " ")) {
            return province[1] + value.substring(province[0].length());
        }
        return value;
    }

    private static String unit(String address, String suffix) {
        Matcher matcher = Pattern.compile("(지하\\s*)?(\\d+)\\s*" + suffix).matcher(address);
        return matcher.find() ? (matcher.group(1) == null ? "" : "지하") + matcher.group(2) : "";
    }
}
