package kr.dmu.hospital.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import kr.dmu.hospital.common.ApiException;

@Service
public class ChatService {
    private static final String NOTICE = "AI 안내는 진단이나 응급 여부 판정이 아닌 진료 탐색을 돕는 참고 정보입니다. 의료진·시간표는 모의 예약용입니다.";
    private static final List<String> PREFERENCES = List.of("가까운 병원으로 찾아주세요", "전문병원·대학병원으로 찾아주세요");
    private final OpenAiClient ai;
    private final HospitalRecommendationService recommendations;
    private final ChatUsageLimiter usage;

    public ChatService(OpenAiClient ai, HospitalRecommendationService recommendations, ObjectMapper mapper, ChatUsageLimiter usage) {
        this.ai = ai;
        this.recommendations = recommendations;
        this.usage = usage;
    }

    public Map<String, Object> chat(Map<String, Object> body, String callerKey, boolean authenticated) {
        List<Map<String, String>> messages = validateMessages(body.get("messages"));
        List<String> users = messages.stream().filter(message -> "user".equals(message.get("role")))
                .map(message -> message.get("content")).toList();
        EmergencyScreen.Status urgent = EmergencyScreen.assess(users);
        if (urgent == EmergencyScreen.Status.PRESENT) {
            try { usage.checkLimit(callerKey, authenticated); }
            catch (ApiException error) { if (error.getStatus() != 429) throw error; }
            return emergencyResponse("FALLBACK");
        }
        String selectedRegion = inputRegion(body.get("region"));
        Double[] location = location(body.get("location"));
        usage.checkLimit(callerKey, authenticated);
        if (urgent == EmergencyScreen.Status.UNCERTAIN) return emergencyQuestion("FALLBACK", new ChatContext());

        ChatContext context;
        try {
            context = ChatContext.from(ai.structured("hospital_search_conditions", instructions(selectedRegion), messages, schema()), users);
        } catch (OpenAiClient.Unavailable unavailable) {
            Map<String, Object> response = base("FALLBACK", "AI에 연결하지 못해 증상과 진료 목적을 확인하지 못했습니다. 병원 직접 검색도 이용할 수 있어요.");
            String department = keywordDepartment(String.join(" ", users));
            response.put("departmentCode", department);
            response.put("departmentName", department.isBlank() ? "" : Departments.name(department));
            return question(response, "CONTEXT", "어디가 불편한지와 언제 시작됐는지, 처음보다 심해지고 있는지 알려주세요. 증상이 심하거나 갑작스러운 호흡·의식 변화가 있으면 온라인 상담을 기다리지 말고 의료진 또는 119의 도움을 받으세요.", List.of());
        }
        String explicitPreference = ChatContext.explicitPreference(users);
        if (!explicitPreference.isBlank()) context.preference = explicitPreference;
        if (!selectedRegion.isBlank()) context.region = selectedRegion;
        boolean nationwide = ChatContext.nationwide(users) || "전국".equals(context.region);
        if ("SPECIALIST".equals(context.preference) && users.getLast().contains("전국") && nationwide) context.region = "";
        if ("전국".equals(context.region)) context.region = "";
        if (context.emergency == EmergencyScreen.Status.PRESENT) return emergencyResponse("AI");
        if (context.emergency == EmergencyScreen.Status.UNCERTAIN) return emergencyQuestion("AI", context);

        Map<String, Object> response = response(context);
        if (!context.hasConcern) return question(response, "CONTEXT", "불편한 부위·증상이나 찾으시는 진료 분야를 알려주세요. 검사·수술·기존 진단 상담이 목적이면 함께 알려주세요.", List.of());
        boolean symptomVisit = Set.of("UNKNOWN", "SYMPTOM").contains(context.purpose);
        if ("SEVERE".equals(context.severity) && !context.dangerContextKnown) return emergencyQuestion("AI", context);
        if (symptomVisit && (!context.durationKnown || "UNKNOWN".equals(context.course))) {
            String question = !context.durationKnown && "UNKNOWN".equals(context.course)
                    ? "증상은 언제 시작됐나요? 처음보다 심해지는지, 비슷한지 또는 나아지는지도 알려주세요."
                    : !context.durationKnown ? "증상은 언제부터 이어지고 있나요?" : "처음보다 증상이 심해지고 있나요, 비슷한가요, 아니면 나아지고 있나요?";
            return question(response, "CONTEXT", question, List.of());
        }
        if (symptomVisit && ("UNKNOWN".equals(context.severity) || !context.backgroundKnown)) {
            String question = "UNKNOWN".equals(context.severity) && !context.backgroundKnown
                    ? "일상생활이 어려울 만큼 증상이 심한가요? 이미 진단받았거나 의뢰받은 내용이 있으면 알려주시고, 없으면 없다고 말씀해 주세요."
                    : "UNKNOWN".equals(context.severity) ? "지금 증상이 일상생활이 어려울 만큼 심한가요?" : "이미 진단받거나 의뢰받은 내용이 있나요? 없다면 없다고 알려주세요.";
            return question(response, "CONTEXT", question, List.of());
        }
        if ("UNKNOWN".equals(context.purpose)) return question(response, "CONTEXT", "이번 방문은 증상 진료, 정기 진료, 전문 검사·수술 또는 기존 진단 상담 중 어떤 목적에 가까운가요?",
                List.of("증상 진료가 목적이에요", "정기 진료가 목적이에요", "전문 검사나 기존 진단 상담이 목적이에요"));
        if (context.department.isBlank() && context.specialty.isBlank()) return question(response, "CONTEXT", "검색할 진료과나 세부 분야를 조금 더 알려주세요. 의뢰서나 진단서에 적힌 진료 분야가 있으면 그 분야만 입력해 주세요.", List.of());
        if ("UNDECIDED".equals(context.preference)) {
            String question = context.specialistPurpose()
                    ? "말씀하신 진료 목적은 해당 분야의 전문 진료 여부를 확인하는 것이 도움이 돼요. 가까운 병원부터 살펴볼까요, 전문병원·대학병원을 찾아볼까요?"
                    : "가까운 병원에서 진료받는 쪽과 전문병원·대학병원을 찾아보는 쪽 중 어떤 방식을 원하시나요?";
            return question(response, "PREFERENCE", question, PREFERENCES);
        }
        boolean missingLocation = "SPECIALIST".equals(context.preference)
                ? context.region.isBlank() && !nationwide : context.region.isBlank() && location[0] == null;
        if (missingLocation) {
            return question(response, "LOCATION", "SPECIALIST".equals(context.preference)
                    ? "전문 진료기관 검색에는 시·구·동 지역명을 알려주시거나 전국 검색을 선택해 주세요. 현재 위치는 확인된 병원까지의 거리 표시에 사용해요."
                    : "가까운 병원을 찾을 시·구·동을 알려주시거나 현재 위치 사용을 선택해 주세요.",
                    "SPECIALIST".equals(context.preference) ? List.of("전국에서 찾아주세요") : List.of());
        }

        Map<String, Object> search = recommendations.recommend(context.department, context.specialty, context.region,
                context.preference, context.promptCare(), location[0], location[1]);
        @SuppressWarnings("unchecked") List<Map<String, Object>> found = search.get("items") instanceof List<?> items ? (List<Map<String, Object>>) items : List.of();
        response.put("hospitals", found);
        response.put("source", search.getOrDefault("source", "CACHE"));
        response.put("stage", "RESULTS");
        if (search.get("notice") instanceof String notice && !notice.isBlank()) response.put("notice", NOTICE + " " + notice);
        if (found.isEmpty()) {
            response.put("reply", "SPECIALIST".equals(context.preference)
                    ? "조건에 맞는 전문 진료 참고 자료를 찾지 못했어요. 지역을 넓히거나 가까운 진료과 병원을 살펴볼 수 있어요."
                    : "조건에 맞는 저장된 병원 정보를 찾지 못했어요. 지역을 넓히거나 진료 분야를 다시 알려주세요.");
            response.put("followUpOptions", "SPECIALIST".equals(context.preference)
                    ? List.of("전국에서 다시 찾아주세요", "가까운 병원으로 찾아주세요") : List.of("전문병원·대학병원으로 찾아주세요"));
        } else {
            response.put("reply", "SPECIALIST".equals(context.preference)
                    ? "입력하신 분야와 관련된 병원과 참고 자료를 찾았어요. 공식 안내·기사·후기 등 출처 유형과 실제 진료 가능 여부를 함께 확인해 주세요."
                    : "선택한 지역 또는 위치를 기준으로 병원 정보를 찾았어요. 주소·진료과와 실제 진료 가능 여부를 확인해 주세요.");
            response.put("followUpOptions", "SPECIALIST".equals(context.preference)
                    ? List.of("가까운 병원으로 찾아주세요") : List.of("전문병원·대학병원으로 찾아주세요"));
        }
        return response;
    }

    private static String instructions(String region) {
        return "한국어 진료 탐색 조건 추출기입니다. 진단, 중증도 확정, 치료 지시를 하지 않습니다. 경증이라거나 대학병원이 불필요하다고 단정하지 마세요. "
                + "user 역할의 실제 발화만 증상·기간·선호·기존 진단의 사실 근거입니다. assistant 발화는 질문 해석에만 쓰고 제시한 증상·예시·선택지를 사용자 사실로 사용하지 마세요. "
                + "최신 사용자 수정 조건을 우선하되 이전 사용자의 유효한 조건은 유지하세요. 가까운 병원으로 선호를 바꿔도 앞서 말한 악화·심함이 사라진 것은 아닙니다. "
                + "모든 Evidence는 해당 판단을 뒷받침하는 user 발화의 정확한 짧은 인용입니다. 근거가 없으면 빈 문자열과 UNKNOWN/false입니다. "
                + "기간, 경과(악화/지속/호전/변화없음), 사용자가 말한 심함, 기존 진단·의뢰, 방문 목적을 구분하세요. 증상이 심하다는 것만으로 응급이 아니라고 판단하지 마세요. "
                + "purpose는 SYMPTOM/ROUTINE/TEST/SURGERY/DIAGNOSIS_CONSULT/INFORMATION/UNKNOWN입니다. INFORMATION은 개인 증상 평가 요청 없이 특정 분야 병원 정보를 찾는 목적입니다. "
                + "예: '전국 내분비내과 대학병원 찾아줘'는 INFORMATION이며 증상 고지가 필요하지 않습니다. 증상을 평가해 병원 유형을 골라달라는 요청은 SYMPTOM입니다. "
                + "durationKnown은 시작 시점·지속 기간을 말한 경우입니다. severity의 NO_SEVERE_REPORTED는 사용자가 심하지 않다고 직접 말한 경우에만 쓰며 의학적 경증 판정이 아닙니다. "
                + "backgroundKnown은 기존 진단·의뢰 유무를 답한 경우입니다. dangerContextKnown은 호흡·의식 변화 등 추가 위험 징후에 대해 현재 상황을 답한 경우입니다. 없다는 답도 응급 여부 확정이 아닙니다. "
                + "emergency는 현재 실제 응급 징후가 명확한 PRESENT, 확인 질문이 필요한 UNCERTAIN, 그 외 NONE입니다. 부정·예시·가정·해결된 과거를 현재 응급으로 바꾸지 마세요. "
                + "departmentCode는 목록 중 하나 또는 빈 문자열입니다. 목록 밖 세부 분야도 specialty로 검색할 수 있습니다. specialty는 내분비/소화기/폐암처럼 짧은 의료 분야만 쓰세요. "
                + "증상에서 진료과·일반 진료 분야를 추정할 수 있지만 질환명을 진단하거나 추정해 specialty에 넣지 마세요. 질환명은 사용자가 직접 언급한 진단·검색 대상일 때만 유지하고, 그 외에는 일반 진료과·분야로 표현하세요. "
                + "specialty와 region에 이름·이메일·전화번호·개인식별정보·자유 증상 원문·상세 집주소를 넣지 마세요. region은 시·군·구·동 수준의 지명입니다. "
                + "carePreference는 직접 선택한 NEARBY/SPECIALIST 또는 아직 선택하지 않은 UNDECIDED입니다. 전문 진료 권고와 사용자 선택을 구분하세요. "
                + "증상 또는 원하는 진료 분야가 있을 때 hasConcern=true입니다. 진료과 목록=" + Departments.items() + ". 화면의 희망 지역=" + region;
    }

    private static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> codes = new ArrayList<>(List.of(""));
        for (Map<String, String> department : Departments.items()) codes.add(department.get("code"));
        properties.put("departmentCode", Map.of("type", "string", "enum", codes));
        for (String field : List.of("specialty", "region", "concernEvidence", "durationEvidence", "courseEvidence", "severityEvidence", "backgroundEvidence", "diagnosisEvidence", "referralEvidence", "purposeEvidence", "preferenceEvidence", "emergencyEvidence", "dangerEvidence")) properties.put(field, Map.of("type", "string"));
        for (String field : List.of("hasConcern", "durationKnown", "backgroundKnown", "knownDiagnosis", "referral", "dangerContextKnown")) properties.put(field, Map.of("type", "boolean"));
        properties.put("course", enumSchema("UNKNOWN", "STABLE", "IMPROVING", "WORSENING", "PERSISTENT"));
        properties.put("severity", enumSchema("UNKNOWN", "NO_SEVERE_REPORTED", "SEVERE"));
        properties.put("purpose", enumSchema("UNKNOWN", "SYMPTOM", "ROUTINE", "TEST", "SURGERY", "DIAGNOSIS_CONSULT", "INFORMATION"));
        properties.put("carePreference", enumSchema("UNDECIDED", "NEARBY", "SPECIALIST"));
        properties.put("emergency", enumSchema("NONE", "PRESENT", "UNCERTAIN"));
        return Map.of("type", "object", "properties", properties, "required", new ArrayList<>(properties.keySet()), "additionalProperties", false);
    }

    private static Map<String, Object> enumSchema(String... values) { return Map.of("type", "string", "enum", List.of(values)); }

    static List<Map<String, String>> validateMessages(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 20) throw new ApiException(400, "대화는 1~20개 메시지로 보내 주세요.");
        List<Map<String, String>> messages = new ArrayList<>();
        int total = 0;
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> message) || !(message.get("role") instanceof String role) || !(message.get("content") instanceof String original)) throw new ApiException(400, "대화 형식을 확인해 주세요.");
            String content = original.trim();
            if (!Set.of("user", "assistant").contains(role) || content.isBlank() || content.length() > 1500) throw new ApiException(400, "메시지는 1~1500자로 입력해 주세요.");
            total += content.length();
            messages.add(Map.of("role", role, "content", content));
        }
        if (total > 9000 || !"user".equals(messages.getLast().get("role"))) throw new ApiException(400, "대화가 길어졌습니다. 새 상담을 시작해 주세요.");
        return messages;
    }

    private static String inputRegion(Object value) {
        if (value == null) return "";
        if (!(value instanceof String region) || region.length() > 80) throw new ApiException(400, "지역 입력은 80자 이하로 작성해 주세요.");
        return region.trim();
    }

    private static Double[] location(Object value) {
        if (value == null) return new Double[]{null, null};
        if (!(value instanceof Map<?, ?> map) || !(map.get("latitude") instanceof Number latitude) || !(map.get("longitude") instanceof Number longitude)) throw new ApiException(400, "현재 위치 정보를 확인해 주세요.");
        double lat = latitude.doubleValue(), lon = longitude.doubleValue();
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < 32 || lat > 40 || lon < 123 || lon > 133) throw new ApiException(400, "국내 병원을 찾을 수 있는 현재 위치를 확인해 주세요.");
        return new Double[]{lat, lon};
    }

    static boolean emergency(String text) { return EmergencyScreen.assess(List.of(text)) == EmergencyScreen.Status.PRESENT; }
    static String keywordDepartment(String text) {
        for (Map<String, String> department : Departments.items()) if (text.contains(department.get("name"))) return department.get("code");
        if (containsAny(text, "목이", "목 아", "코가", "콧물", "귀가", "기침")) return "13";
        if (containsAny(text, "피부", "가려", "두드러기", "여드름")) return "14";
        if (containsAny(text, "무릎", "허리", "발목", "어깨", "관절")) return "05";
        if (containsAny(text, "배가", "속이", "소화", "복통", "위가")) return "01";
        if (containsAny(text, "눈이", "시력")) return "12";
        if (containsAny(text, "치아", "이빨", "잇몸")) return "49";
        return "";
    }
    private static boolean containsAny(String text, String... words) { for (String word : words) if (text.contains(word)) return true; return false; }
    static String keywordRegion(String text) {
        for (String region : List.of("영통", "수원", "강남", "마포", "송파", "서울", "인천", "부산", "대전", "대구", "광주", "울산", "성남", "용인", "제주", "세종")) if (text.contains(region)) return region;
        return "";
    }

    private static Map<String, Object> response(ChatContext context) {
        Map<String, Object> response = base("AI", NOTICE);
        response.put("departmentCode", context.department);
        response.put("departmentName", context.department.isBlank() ? "" : Departments.name(context.department));
        response.put("specialty", context.specialty);
        response.put("carePreference", context.preference);
        response.put("promptCare", context.promptCare());
        response.put("careGuidance", context.guidance());
        return response;
    }
    private static Map<String, Object> emergencyQuestion(String mode, ChatContext context) {
        Map<String, Object> response = response(context);
        response.put("mode", mode);
        response.put("promptCare", true);
        response.put("careGuidance", "온라인으로 응급 여부를 판정할 수 없어요. 증상이 심하면 진료를 미루지 말고 의료진에게 신속히 평가받으세요.");
        return question(response, "CONTEXT", "지금 실제로 숨쉬기 어렵거나 의식 변화, 갑작스러운 마비·심한 가슴 통증 등이 있나요? 현재 그런 증상이 있으면 병원 검색을 기다리지 말고 119 또는 응급실 도움을 받으세요.",
                List.of("지금 숨쉬기 어렵거나 의식 변화가 있어요", "현재 그런 증상은 없어요"));
    }
    private static Map<String, Object> emergencyResponse(String mode) {
        Map<String, Object> response = base(mode, "이 서비스는 응급 진료나 진단을 제공하지 않습니다.");
        response.put("emergency", true);
        response.put("promptCare", true);
        response.put("stage", "EMERGENCY");
        response.put("careGuidance", "예약이나 온라인 상담을 기다리지 말고 119 또는 가까운 응급실의 도움을 받으세요.");
        response.put("reply", "말씀하신 내용에 현재 응급 상황을 의심할 수 있는 표현이 있어요. 예약을 기다리지 말고 119에 연락하거나 가까운 응급실의 도움을 받으세요.");
        return response;
    }
    private static Map<String, Object> question(Map<String, Object> response, String stage, String reply, List<String> options) {
        response.put("needsMoreInfo", true);
        response.put("stage", stage);
        response.put("reply", reply);
        response.put("followUpOptions", options);
        return response;
    }
    private static Map<String, Object> base(String mode, String notice) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mode", mode); response.put("notice", notice); response.put("emergency", false);
        response.put("needsMoreInfo", false); response.put("hospitals", List.of());
        response.put("departmentCode", ""); response.put("departmentName", ""); response.put("specialty", "");
        response.put("carePreference", "UNDECIDED"); response.put("careGuidance", "");
        response.put("followUpOptions", List.of()); response.put("stage", "CONTEXT"); response.put("promptCare", false);
        return response;
    }
}
