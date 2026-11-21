package kr.dmu.hospital.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.dmu.hospital.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatConsultationTest {
    private static final String SYMPTOM = "목이 아파요. 어제부터 비슷해요. 심하지 않아요. 기존 진단이나 의뢰는 없어요.";
    private final ObjectMapper mapper = new ObjectMapper();
    private OpenAiClient ai;
    private HospitalRecommendationService recommendations;
    private ChatUsageLimiter usage;
    private ChatService service;

    @BeforeEach void setUp() {
        ai = mock(OpenAiClient.class);
        recommendations = mock(HospitalRecommendationService.class);
        usage = mock(ChatUsageLimiter.class);
        service = new ChatService(ai, recommendations, mapper, usage);
        when(recommendations.recommend(anyString(), anyString(), anyString(), anyString(), anyBoolean(), nullable(Double.class), nullable(Double.class)))
                .thenReturn(Map.of("items", List.of(Map.of("id", "verified", "name", "확인된 병원")), "source", "CACHE", "notice", "저장된 정보"));
    }

    @Test void asksDurationAndCourseBeforeChoosingATypeOfHospital() {
        ObjectNode context = ordinary().put("durationKnown", false).put("durationEvidence", "").put("course", "UNKNOWN").put("courseEvidence", "");
        respond(context);
        Map<String, Object> result = chat("목이 아파요");
        assertEquals("CONTEXT", result.get("stage"));
        assertTrue(result.get("reply").toString().contains("언제"));
        assertTrue(result.get("reply").toString().contains("심해지는지"));
        verifyNoInteractions(recommendations);
    }

    @Test void multiTurnOrdinaryContextAsksPreferenceAndThenUsesUserChoice() {
        respond(ordinary());
        List<Map<String, String>> history = new ArrayList<>(List.of(user("목이 아파요"), assistant("언제 시작됐고 증상이 변했나요?"), user(SYMPTOM)));
        Map<String, Object> first = service.chat(Map.of("messages", history), "caller", false);
        assertEquals("PREFERENCE", first.get("stage"));
        assertEquals("UNDECIDED", first.get("carePreference"));
        assertFalse(first.get("reply").toString().contains("대학병원이 필요 없"));
        verifyNoInteractions(recommendations);
        history.add(assistant(first.get("reply").toString()));
        history.add(user("수원에서 가까운 병원으로 찾아주세요"));
        respond(ordinary().put("region", "수원"));
        Map<String, Object> result = service.chat(Map.of("messages", history), "caller", false);
        assertEquals("RESULTS", result.get("stage"));
        assertEquals("NEARBY", result.get("carePreference"));
        verify(recommendations).recommend("13", "이비인후과", "수원", "NEARBY", false, null, null);
    }

    @Test void selectingNearbyDoesNotErasePreviouslyReportedWorsening() {
        respond(ordinary().put("region", "서울"));
        Map<String, Object> result = chat(SYMPTOM, "증상이 더 심해졌어요", "서울에서 가까운 병원으로 찾아주세요");
        assertEquals("RESULTS", result.get("stage"));
        assertEquals(true, result.get("promptCare"));
        assertTrue(result.get("careGuidance").toString().contains("신속히"));
        verify(recommendations).recommend("13", "이비인후과", "서울", "NEARBY", true, null, null);
    }

    @Test void knownReferralSuggestsSpecialistDirectionButRespectsNearbyChoice() {
        ObjectNode context = empty().put("hasConcern", true).put("concernEvidence", "갑상선암 진단")
                .put("departmentCode", "01").put("specialty", "갑상선암").put("knownDiagnosis", true).put("diagnosisEvidence", "갑상선암 진단")
                .put("referral", true).put("referralEvidence", "의뢰받았어요").put("purpose", "DIAGNOSIS_CONSULT").put("purposeEvidence", "진단 상담").put("region", "서울");
        respond(context);
        String message = "갑상선암 진단 후 의뢰받았어요. 진단 상담을 받고 싶어요.";
        Map<String, Object> first = chat(message);
        assertEquals("PREFERENCE", first.get("stage"));
        assertTrue(first.get("reply").toString().contains("전문 진료"));
        Map<String, Object> result = chat(message, "서울에서 가까운 병원으로 찾아주세요");
        assertEquals("NEARBY", result.get("carePreference"));
        assertEquals(true, result.get("promptCare"));
        verify(recommendations).recommend("01", "갑상선암", "서울", "NEARBY", true, null, null);
    }

    @Test void severeSymptomsAloneRequireDangerContextWithoutRemoteNonEmergencyVerdict() {
        respond(ordinary().put("severity", "SEVERE").put("severityEvidence", "너무 심해요").put("region", "서울"));
        Map<String, Object> result = chat(SYMPTOM, "지금은 통증이 너무 심해요. 서울에서 가까운 병원으로 찾아주세요");
        assertEquals("CONTEXT", result.get("stage"));
        assertEquals(true, result.get("promptCare"));
        assertFalse(result.get("careGuidance").toString().contains("응급이 아니"));
        assertTrue(result.get("reply").toString().contains("119"));
        verifyNoInteractions(recommendations);
    }

    @Test void clearCurrentEmergencyInAnEarlierUserTurnStopsAllExternalCalls() {
        Map<String, Object> result = service.chat(Map.of("messages", List.of(user("숨을 못 쉬겠어요"), assistant("지역을 알려주세요"), user("수원이요"))), "caller", false);
        assertEquals("EMERGENCY", result.get("stage"));
        assertEquals(true, result.get("emergency"));
        assertTrue(result.get("reply").toString().contains("119"));
        verify(usage).checkLimit("caller", false);
        verifyNoInteractions(ai, recommendations);
    }

    @Test void quotaCannotHideEmergencyContactGuidance() {
        doThrow(new ApiException(429, "일일 한도")).when(usage).checkLimit("caller", false);
        assertEquals("EMERGENCY", chat("갑자기 의식을 잃었어요").get("stage"));
        verifyNoInteractions(ai, recommendations);
    }

    @Test void assistantWarningExamplesNeverBecomePatientFacts() {
        respond(ordinary().put("emergency", "PRESENT").put("emergencyEvidence", "숨을 못 쉬겠어요"));
        List<Map<String, String>> history = List.of(user("목이 아파요"), assistant("예시: 어제부터 비슷해요. 심하지 않아요. 기존 진단이나 의뢰는 없어요. 숨을 못 쉬겠어요."), user("네"));
        Map<String, Object> result = service.chat(Map.of("messages", history), "caller", false);
        assertEquals("CONTEXT", result.get("stage"));
        assertEquals(false, result.get("emergency"));
        assertTrue(result.get("reply").toString().contains("언제"));
        verifyNoInteractions(recommendations);
    }

    @Test void negatedHistoricalAndIllustrativeWarningsAreNotCurrentEmergencyFacts() {
        respond(empty());
        for (String message : List.of("호흡곤란은 없어요", "숨을 못 쉬는 건 아니에요", "작년에 호흡곤란이 있었고 지금은 없어요", "'숨을 못 쉬겠어요'는 예시입니다", "예시: 지금 호흡곤란이 있다고 하면요", "어제 호흡곤란이 있었어요. 현재 그런 증상은 없어요")) {
            assertEquals(false, chat(message).get("emergency"), message);
        }
        verify(ai, times(6)).structured(eq("hospital_search_conditions"), anyString(), anyList(), anyMap());
        verifyNoInteractions(recommendations);
    }

    @Test void bleedingThatDoesNotStopIsAnAffirmativeEmergencySign() {
        for (String message : List.of("피가 멈추지 않아요", "출혈이 안 멈춰요", "지금 지혈이 안 돼요")) {
            assertEquals("EMERGENCY", chat(message).get("stage"), message);
        }
        verifyNoInteractions(ai, recommendations);
    }

    @Test void coordinatedWarningsShareAnExplicitDenialButNotAnAffirmativeStatement() {
        respond(empty());
        for (String message : List.of("호흡곤란과 의식 변화는 없어요", "호흡곤란, 의식 변화 모두 없어요", "피가 멈추지 않는 건 아니에요")) {
            assertEquals(false, chat(message).get("emergency"), message);
        }
        assertEquals("EMERGENCY", chat("호흡곤란은 있고 의식 변화는 없어요").get("stage"));
        assertEquals("EMERGENCY", chat("호흡곤란은 있고 의식 변화가 없는 건 아니에요").get("stage"));
        verify(ai, times(3)).structured(anyString(), anyString(), anyList(), anyMap());
        verifyNoInteractions(recommendations);
    }

    @Test void modelCannotRemoveNegationByQuotingJustAWarningNoun() {
        respond(empty().put("emergency", "PRESENT").put("emergencyEvidence", "호흡곤란"));
        assertEquals(false, chat("현재 호흡곤란은 없어요").get("emergency"));
        verifyNoInteractions(recommendations);
    }

    @Test void anActualCurrentWarningAfterHistoricalContextStillStopsSearch() {
        assertEquals("EMERGENCY", chat("작년에 호흡곤란이 있었고 지금 다시 호흡곤란이 있어요").get("stage"));
        assertEquals("EMERGENCY", chat("예시가 아니라 지금 숨을 못 쉬겠어요").get("stage"));
        verifyNoInteractions(ai, recommendations);
    }

    @Test void doubleNegativeDenialRequestsClarificationInsteadOfClearingWarning() {
        assertEquals("CONTEXT", chat("호흡곤란이 없는 건 아니에요").get("stage"));
        assertEquals("CONTEXT", chat("호흡곤란인지 모르겠어요", "그런 증상이 없다는 뜻은 아니에요").get("stage"));
        verifyNoInteractions(ai, recommendations);
    }

    @Test void uncertainDangerGetsAClarifyingQuestionBeforeAnyExternalSearch() {
        Map<String, Object> result = chat("호흡곤란인지 모르겠어요");
        assertEquals("CONTEXT", result.get("stage"));
        assertEquals(false, result.get("emergency"));
        assertEquals(true, result.get("needsMoreInfo"));
        assertTrue(result.get("reply").toString().contains("지금 실제로"));
        verifyNoInteractions(ai, recommendations);
    }

    @Test void generalSpecialtyInformationSearchDoesNotRequirePersonalSymptomDisclosure() {
        ObjectNode context = empty().put("hasConcern", true).put("concernEvidence", "핵의학과")
                .put("departmentCode", "99").put("specialty", "핵의학과").put("purpose", "INFORMATION")
                .put("purposeEvidence", "핵의학과 전문 대학병원 찾아주세요");
        respond(context);
        Map<String, Object> result = chat("전국 핵의학과 전문 대학병원 찾아주세요");
        assertEquals("RESULTS", result.get("stage"));
        assertEquals("", result.get("departmentCode"));
        verify(recommendations).recommend("", "핵의학과", "", "SPECIALIST", false, null, null);
    }

    @Test void modelCannotInventADiseaseFromAReportedSymptom() {
        respond(ordinary().put("concernEvidence", "기침이 나요").put("departmentCode", "01").put("specialty", "폐암")
                .put("purposeEvidence", "기침이 나요").put("region", "서울"));
        Map<String, Object> result = chat("기침이 나요. 어제부터 비슷해요. 심하지 않아요. 기존 진단이나 의뢰는 없어요.", "서울 가까운 병원으로 찾아주세요");
        assertEquals("내과", result.get("specialty"));
        assertFalse(result.toString().contains("폐암"));
        verify(recommendations).recommend("01", "내과", "서울", "NEARBY", false, null, null);
    }

    @Test void directlyReportedDiagnosisCanRemainTheSearchSpecialty() {
        respond(empty().put("hasConcern", true).put("concernEvidence", "폐암 진단").put("departmentCode", "01").put("specialty", "폐암")
                .put("knownDiagnosis", true).put("diagnosisEvidence", "폐암 진단").put("purpose", "DIAGNOSIS_CONSULT").put("purposeEvidence", "진단 상담"));
        Map<String, Object> result = chat("폐암 진단 상담을 위한 전문병원을 전국에서 찾아주세요");
        assertEquals("폐암", result.get("specialty"));
        verify(recommendations).recommend("01", "폐암", "", "SPECIALIST", false, null, null);
    }

    @Test void latestExplicitPreferenceOverridesAnEarlierSpecialistChoice() {
        ObjectNode context = ordinary().put("region", "수원").put("carePreference", "SPECIALIST").put("preferenceEvidence", "대학병원으로 찾아주세요");
        respond(context);
        Map<String, Object> result = chat(SYMPTOM, "대학병원으로 찾아주세요", "대학병원 말고 수원에서 가까운 병원으로 찾아주세요");
        assertEquals("NEARBY", result.get("carePreference"));
        verify(recommendations).recommend("13", "이비인후과", "수원", "NEARBY", false, null, null);
    }

    @Test void askingWhetherUniversityCareIsNeededIsNotAChoiceOfSpecialistCare() {
        respond(ordinary());
        Map<String, Object> result = chat(SYMPTOM, "대학병원이 필요한가요? 가까운 병원과 차이가 뭐예요?");
        assertEquals("PREFERENCE", result.get("stage"));
        assertEquals("UNDECIDED", result.get("carePreference"));
        verifyNoInteractions(recommendations);
    }

    @Test void coordinatesCanSupplyNearbyLocationAndInvalidValuesDoNotSpendQuota() {
        respond(ordinary());
        Map<String, Object> body = Map.of("messages", List.of(user(SYMPTOM), user("가까운 병원으로 찾아주세요"), user("현재 위치를 기준으로 찾아주세요")),
                "location", Map.of("latitude", 37.27, "longitude", 127.02));
        assertEquals("RESULTS", service.chat(body, "caller", false).get("stage"));
        verify(recommendations).recommend("13", "이비인후과", "", "NEARBY", false, 37.27, 127.02);
        clearInvocations(ai, recommendations, usage);
        assertThrows(ApiException.class, () -> service.chat(Map.of("messages", List.of(user(SYMPTOM)), "location", Map.of("latitude", Double.NaN, "longitude", 127)), "caller", false));
        verifyNoInteractions(ai, recommendations, usage);
    }

    @Test void emptyVerifiedSpecialistResultsOfferAlternativesWithoutInventingHospitals() {
        respond(ordinary().put("region", "서울"));
        when(recommendations.recommend(anyString(), anyString(), anyString(), anyString(), anyBoolean(), nullable(Double.class), nullable(Double.class)))
                .thenReturn(Map.of("items", List.of(), "source", "WEB", "notice", "근거 부족"));
        Map<String, Object> result = chat(SYMPTOM, "서울에서 전문병원으로 찾아주세요");
        assertEquals(List.of(), result.get("hospitals"));
        assertTrue(result.get("reply").toString().contains("찾지 못"));
        assertTrue(result.get("followUpOptions").toString().contains("가까운"));
    }

    @Test void specialistCoordinatesDoNotSilentlyAuthorizeNationwideSearch() {
        respond(ordinary());
        Map<String, Object> result = service.chat(Map.of("messages", List.of(user(SYMPTOM), user("전문병원으로 찾아주세요"), user("현재 위치를 기준으로 찾아주세요")),
                "location", Map.of("latitude", 37.27, "longitude", 127.02)), "caller", false);
        assertEquals("LOCATION", result.get("stage"));
        assertTrue(result.get("reply").toString().contains("지역명"));
        assertEquals(List.of("전국에서 찾아주세요"), result.get("followUpOptions"));
        verifyNoInteractions(recommendations);
    }

    @Test void aiFailureNeverBypassesContextCollectionWithAKeywordMedicalJudgment() {
        when(ai.structured(anyString(), anyString(), anyList(), anyMap())).thenThrow(new OpenAiClient.Unavailable());
        Map<String, Object> result = chat("기침이 한 달째고 더 심해져요. 수원 가까운 병원으로 찾아주세요");
        assertEquals("FALLBACK", result.get("mode"));
        assertEquals("CONTEXT", result.get("stage"));
        assertEquals(true, result.get("needsMoreInfo"));
        assertEquals(List.of(), result.get("hospitals"));
        verifyNoInteractions(recommendations);
    }

    private void respond(ObjectNode context) { when(ai.structured(anyString(), anyString(), anyList(), anyMap())).thenReturn(context); }
    private Map<String, Object> chat(String... users) { return service.chat(Map.of("messages", java.util.Arrays.stream(users).map(ChatConsultationTest::user).toList()), "caller", false); }
    private static Map<String, String> user(String text) { return Map.of("role", "user", "content", text); }
    private static Map<String, String> assistant(String text) { return Map.of("role", "assistant", "content", text); }
    private ObjectNode ordinary() {
        return empty().put("hasConcern", true).put("concernEvidence", "목이 아파요").put("departmentCode", "13").put("specialty", "이비인후과")
                .put("durationKnown", true).put("durationEvidence", "어제부터").put("course", "STABLE").put("courseEvidence", "비슷해요")
                .put("severity", "NO_SEVERE_REPORTED").put("severityEvidence", "심하지 않아요").put("backgroundKnown", true)
                .put("backgroundEvidence", "기존 진단이나 의뢰는 없어요").put("purpose", "SYMPTOM").put("purposeEvidence", "목이 아파요");
    }
    private ObjectNode empty() {
        ObjectNode context = mapper.createObjectNode();
        context.put("hasConcern", false).put("departmentCode", "").put("specialty", "").put("region", "")
                .put("durationKnown", false).put("course", "UNKNOWN").put("severity", "UNKNOWN").put("backgroundKnown", false)
                .put("knownDiagnosis", false).put("referral", false).put("dangerContextKnown", false).put("purpose", "UNKNOWN")
                .put("carePreference", "UNDECIDED").put("emergency", "NONE");
        for (String field : List.of("concernEvidence", "durationEvidence", "courseEvidence", "severityEvidence", "backgroundEvidence", "diagnosisEvidence", "referralEvidence", "purposeEvidence", "preferenceEvidence", "emergencyEvidence", "dangerEvidence")) context.put(field, "");
        return context;
    }
}
