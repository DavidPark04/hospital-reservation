package kr.dmu.hospital.integration;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.List;
import java.util.Set;

/** Request-local state extracted from user evidence; never persisted as a consultation record. */
final class ChatContext {
    private static final Set<String> GENERAL_FIELDS = Set.of("내분비", "내분비내과", "소화기", "소화기내과", "호흡기", "호흡기내과", "순환기", "순환기내과", "심장", "심장내과", "갑상선", "종양", "종양내과", "혈액", "혈액내과", "혈액종양내과", "류마티스", "류마티스내과", "척추", "관절", "뇌", "폐", "유방", "대장", "간담췌", "신장", "신장내과", "혈관", "재활", "정신건강", "건강검진", "통증", "감염", "감염내과", "수면", "알레르기", "알레르기내과", "비뇨", "신경", "이비인후", "구강", "피부", "외상", "내시경", "시력", "청력", "소아", "성형", "장기이식");
    String department = "", specialty = "", region = "", course = "UNKNOWN", severity = "UNKNOWN";
    String purpose = "UNKNOWN", preference = "UNDECIDED";
    boolean hasConcern, durationKnown, backgroundKnown, knownDiagnosis, referral, dangerContextKnown;
    EmergencyScreen.Status emergency = EmergencyScreen.Status.NONE;

    static ChatContext from(JsonNode input, List<String> users) {
        if (input == null || !input.isObject() || !input.path("hasConcern").isBoolean()) throw new OpenAiClient.Unavailable();
        ChatContext context = new ChatContext();
        context.hasConcern = input.path("hasConcern").asBoolean() && evidence(input, "concernEvidence", users);
        String code = input.path("departmentCode").asText("");
        if (context.hasConcern && Departments.contains(code)) context.department = code;
        if (context.hasConcern) context.specialty = supportedSpecialty(input.path("specialty").asText(""), context.department, users);
        String region = input.path("region").asText("").trim();
        if (region.length() <= 80 && !region.contains("@") && !region.contains("://") && !region.matches(".*[0-9]{3,}.*")) context.region = region;
        context.durationKnown = input.path("durationKnown").asBoolean(false) && evidence(input, "durationEvidence", users);
        if (evidence(input, "courseEvidence", users)) context.course = choice(input, "course", "UNKNOWN", "STABLE", "IMPROVING", "WORSENING", "PERSISTENT");
        if (evidence(input, "severityEvidence", users)) context.severity = choice(input, "severity", "UNKNOWN", "NO_SEVERE_REPORTED", "SEVERE");
        context.knownDiagnosis = input.path("knownDiagnosis").asBoolean(false) && evidence(input, "diagnosisEvidence", users);
        context.referral = input.path("referral").asBoolean(false) && evidence(input, "referralEvidence", users);
        context.backgroundKnown = context.knownDiagnosis || context.referral
                || input.path("backgroundKnown").asBoolean(false) && evidence(input, "backgroundEvidence", users);
        context.dangerContextKnown = input.path("dangerContextKnown").asBoolean(false) && evidence(input, "dangerEvidence", users);
        if (evidence(input, "purposeEvidence", users)) context.purpose = choice(input, "purpose", "UNKNOWN", "SYMPTOM", "ROUTINE", "TEST", "SURGERY", "DIAGNOSIS_CONSULT", "INFORMATION");
        if (evidence(input, "preferenceEvidence", users)) context.preference = choice(input, "carePreference", "UNDECIDED", "NEARBY", "SPECIALIST");
        if (evidence(input, "emergencyEvidence", users)) {
            String value = input.path("emergency").asText("NONE");
            String quote = input.path("emergencyEvidence").asText("");
            if ("PRESENT".equals(value) && EmergencyScreen.supportsCurrentWarning(quote, users)) context.emergency = EmergencyScreen.Status.PRESENT;
            else if ("UNCERTAIN".equals(value)) context.emergency = EmergencyScreen.Status.UNCERTAIN;
        }
        // A new location or preference never erases previously reported worsening.
        Boolean explicitWorsening = null;
        for (String user : users) {
            String compact = compact(user);
            if (EmergencyScreen.nonCurrent(compact)) continue;
            if (compact.matches(".*(더심해졌|더심해져|더심해요|더아프고|더아파요|더아파졌|심해졌|점점심해|악화됐|악화되고|악화되어).*")) explicitWorsening = true;
            else if (compact.matches(".*(지금은나아졌|이제나아졌|증상이호전됐|지금은좋아졌).*")) explicitWorsening = false;
        }
        if (Boolean.TRUE.equals(explicitWorsening)) context.course = "WORSENING";
        return context;
    }

    boolean promptCare() { return Set.of("WORSENING", "PERSISTENT").contains(course) || "SEVERE".equals(severity) || referral; }
    boolean specialistPurpose() { return referral || knownDiagnosis || Set.of("TEST", "SURGERY", "DIAGNOSIS_CONSULT").contains(purpose); }
    String guidance() {
        if (Set.of("WORSENING", "PERSISTENT").contains(course) || "SEVERE".equals(severity)) return "증상이 지속되거나 심해지는 경우에는 진료를 미루지 말고 의료진에게 신속히 평가받으세요. 갑작스러운 호흡·의식 변화 등이 있으면 119 또는 응급실의 도움을 받으세요.";
        if (referral) return "의뢰받은 진료과와 권장 시기에 맞춰 전문 진료를 확인하세요. 가까운 곳을 선택하더라도 의뢰받은 내용을 의료진에게 알려주세요.";
        if (specialistPurpose()) return "전문 검사·수술·기존 진단 상담은 해당 분야의 진료 가능 여부와 의뢰서 필요 여부를 확인하세요.";
        if ("INFORMATION".equals(purpose)) return "병원 규모나 평판만으로 진료 적합성을 확정하지 않으며, 검색된 진료 분야 자료와 출처를 참고해 안내합니다.";
        return "온라인 증상 설명만으로 응급 여부나 필요한 병원 규모를 확정할 수 없어요. 원하시는 방식에 맞춰 확인된 병원 정보를 살펴볼게요.";
    }

    static String explicitPreference(List<String> users) {
        for (int index = users.size() - 1; index >= 0; index--) {
            String text = compact(users.get(index));
            int nearby = positivePreference(text, List.of("가까운", "동네병원", "근처병원", "동네의원", "근처의원", "주변병원", "주변의원"));
            int specialist = positivePreference(text, List.of("전문병원", "대학병원", "전문진료"));
            if (nearby < 0 && specialist < 0) continue;
            boolean request = text.matches(".*(찾아|보여|추천해|원해|원합니다|가고싶|선택할|선택해|갈게|좋아요|있나요).*" );
            boolean answer = text.matches("(?:가까운(?:곳|병원)|동네(?:병원|의원)|근처(?:병원|의원)|전문병원|대학병원|전문진료)(?:으로|이|으로요|이요|요|쪽이요|쪽으로요)?[.!]?");
            if (!request && !answer) continue;
            return nearby > specialist ? "NEARBY" : "SPECIALIST";
        }
        return "";
    }

    private static int positivePreference(String text, List<String> words) {
        int result = -1;
        for (String word : words) {
            int position = text.lastIndexOf(word);
            if (position < 0) continue;
            String tail = text.substring(position + word.length());
            if (tail.matches("^(?:은|는|이|가|을|를|으로|에|쪽은|곳은|병원은|곳|병원)*(?:말고|싫|원하지않|아니|필요없|안가).*")) continue;
            result = Math.max(result, position);
        }
        return result;
    }

    static boolean nationwide(List<String> users) {
        for (int index = users.size() - 1; index >= 0; index--) {
            String text = compact(users.get(index));
            if (text.contains("전국")) return !text.matches(".*전국.{0,5}(말고|아니|싫|제외).*");
            if (text.contains("지역상관없") || text.contains("지역제한없")) return true;
        }
        return false;
    }

    private static String supportedSpecialty(String value, String department, List<String> users) {
        String specialty = safeSpecialty(value);
        if (specialty.isBlank()) return "";
        String normalized = compact(specialty).toLowerCase(java.util.Locale.ROOT);
        boolean directlyMentioned = users.stream().anyMatch(user -> compact(user).toLowerCase(java.util.Locale.ROOT).contains(normalized));
        boolean generalField = GENERAL_FIELDS.contains(specialty)
                || Departments.items().stream().anyMatch(item -> specialty.equals(item.get("name")));
        // Symptoms can suggest a broad department, but never establish a named disease for the search query.
        if (directlyMentioned || generalField) return specialty;
        return department.isBlank() ? "" : Departments.name(department);
    }

    private static String safeSpecialty(String value) {
        String text = value.trim();
        if (!text.matches("[가-힣A-Za-z ·-]{1,40}")) return "";
        String compact = compact(text);
        if (compact.matches(".*(병|암|염|증|질환|증후군|내과|외과|치과|의학과|검사|수술)$")) return text;
        for (String term : List.of("내분비", "소화기", "호흡기", "순환기", "갑상선", "당뇨", "종양", "척추", "관절", "심장", "뇌", "폐", "유방", "대장", "간담췌", "신장", "혈액", "혈관", "재활", "정신건강", "산부인과", "건강검진", "통증", "감염", "난임", "불임", "비만", "수면", "알레르기", "비뇨", "안과", "신경", "이비인후", "구강", "피부", "외상", "류마티스", "내시경", "시력", "청력", "소아", "성형", "장기이식", "루푸스", "ALS", "ADHD", "PTSD", "COPD", "IBD", "IBS", "PCOS", "HIV", "SLE")) {
            if (compact.endsWith(term) || compact.endsWith(term + "과")) return text;
        }
        return "";
    }

    private static boolean evidence(JsonNode input, String key, List<String> users) {
        String quote = input.path(key).asText("").trim();
        if (quote.isBlank() || quote.length() > 300) return false;
        String normalized = normalized(quote);
        return users.stream().anyMatch(user -> normalized(user).contains(normalized));
    }
    private static String choice(JsonNode input, String key, String fallback, String... allowed) {
        String value = input.path(key).asText(fallback);
        return Set.of(allowed).contains(value) ? value : fallback;
    }
    private static String normalized(String text) { return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("\\s+", " ").trim(); }
    private static String compact(String text) { return normalized(text).replace(" ", ""); }
}
