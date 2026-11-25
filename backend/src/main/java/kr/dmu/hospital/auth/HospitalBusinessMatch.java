package kr.dmu.hospital.auth;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bson.Document;

final class HospitalBusinessMatch {
    private static final Pattern ROAD = Pattern.compile("^(.+?)\\s+([^\\s,()]+(?:대로|로|길))\\s+(\\d+(?:-\\d+)?)(?=[\\s,()]|$)");
    private static final String UNIT_NUMBER = "(?:지하\\s*|[A-Za-z])?\\d+";
    private static final Pattern FLOORS = unitPattern("층");
    private static final Pattern SUITES = unitPattern("호");
    private enum Evidence { UNRELATED, CONFLICT, PARTIAL, EXACT }

    private HospitalBusinessMatch() { }

    static boolean matches(Map<String, String> business, Document hospital, String number) {
        return matches(List.of(business), hospital, number);
    }

    static boolean matches(List<Map<String, String>> businesses, Document hospital, String number) {
        if (!Boolean.TRUE.equals(hospital.getBoolean("active"))) return false;
        boolean partial = false;
        boolean conflict = false;
        for (var business : businesses) {
            Evidence evidence = compare(business, hospital, number);
            if (evidence == Evidence.EXACT) return true;
            if (evidence == Evidence.PARTIAL) partial = true;
            if (evidence == Evidence.CONFLICT) conflict = true;
        }
        // An insurance record with missing details cannot override a conflicting unit at the same location.
        return partial && !conflict;
    }

    private static Evidence compare(Map<String, String> business, Document hospital, String number) {
        if (!number.equals(business.get("saeopjaDrno"))
                || !HospitalName.matches(business.get("saeopjangNm"), hospital.getString("name"))) return Evidence.UNRELATED;
        Document api = hospital.get("apiFields", Document.class);
        String post = business.getOrDefault("post", "").trim();
        if (api == null || post.isBlank() || !post.equals(api.getString("postNo"))) return Evidence.UNRELATED;
        String first = normalizedAddress(business.get("addr"));
        String second = normalizedAddress(hospital.getString("address"));
        Matcher a = ROAD.matcher(first);
        Matcher b = ROAD.matcher(second);
        if (!a.find() || !b.find()) return Evidence.UNRELATED;
        for (int i = 1; i <= 3; i++) if (!a.group(i).equals(b.group(i))) return Evidence.UNRELATED;
        String businessDetail = first.substring(a.end());
        String catalogDetail = second.substring(b.end());
        boolean partial = false;
        for (Pattern pattern : List.of(FLOORS, SUITES)) {
            Set<String> left = units(businessDetail, pattern);
            Set<String> right = units(catalogDetail, pattern);
            if (left.equals(right)) continue;
            if (!left.isEmpty() && !right.isEmpty()) return Evidence.CONFLICT;
            partial = true;
        }
        return partial ? Evidence.PARTIAL : Evidence.EXACT;
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

    private static Pattern unitPattern(String suffix) {
        return Pattern.compile("(" + UNIT_NUMBER + "(?:\\s*[-~∼,/·ᆞ]\\s*" + UNIT_NUMBER + ")*)\\s*" + suffix);
    }

    private static Set<String> units(String detail, Pattern pattern) {
        Set<String> values = new HashSet<>();
        Matcher matcher = pattern.matcher(detail);
        while (matcher.find()) {
            String group = matcher.group(1).replaceAll("\\s+", "").toUpperCase(Locale.ROOT)
                    .replace('~', '-').replace('∼', '-');
            values.addAll(List.of(group.split("[,/·ᆞ]")));
        }
        return values;
    }
}
