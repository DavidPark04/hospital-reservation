package kr.dmu.hospital.integration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Departments {
    private static final Map<String, String> NAMES = new LinkedHashMap<>();
    static {
        NAMES.put("01", "내과");
        NAMES.put("02", "신경과");
        NAMES.put("03", "정신건강의학과");
        NAMES.put("04", "외과");
        NAMES.put("05", "정형외과");
        NAMES.put("06", "신경외과");
        NAMES.put("10", "산부인과");
        NAMES.put("11", "소아청소년과");
        NAMES.put("12", "안과");
        NAMES.put("13", "이비인후과");
        NAMES.put("14", "피부과");
        NAMES.put("15", "비뇨의학과");
        NAMES.put("21", "재활의학과");
        NAMES.put("23", "가정의학과");
        NAMES.put("49", "치과");
        NAMES.put("80", "한방내과");
    }
    private Departments() { }
    public static boolean contains(String code) { return NAMES.containsKey(code); }
    public static String name(String code) { return NAMES.getOrDefault(code, "진료과 확인 필요"); }
    public static List<Map<String, String>> items() {
        List<Map<String, String>> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : NAMES.entrySet()) {
            result.add(Map.of("code", entry.getKey(), "name", entry.getValue()));
        }
        return result;
    }
}
