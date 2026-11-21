package kr.dmu.hospital.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HospitalExpertiseSearchTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String URL = "https://www.hospital.or.kr/center/thyroid";
    private static final String NAME = "검증 병원 본원";
    private static final String ADDRESS = "서울특별시 종로구 검증로 101";
    private static final String REASON = "갑상선센터에서 갑상선암 전문진료 프로그램을 안내합니다.";
    private static final Date NOW = Date.from(Instant.parse("2030-11-21T00:00:00Z"));
    private OpenAiClient ai;
    private MongoCollection<Document> cache;
    private FindIterable<Document> found;
    private HospitalExpertiseSearchService service;

    @SuppressWarnings("unchecked")
    @BeforeEach void setUp() {
        ai = mock(OpenAiClient.class);
        when(ai.configured()).thenReturn(true);
        when(ai.model()).thenReturn("gpt-6.2-sol");
        MongoTemplate mongo = mock(MongoTemplate.class);
        cache = mock(MongoCollection.class);
        found = mock(FindIterable.class);
        when(mongo.getCollection(HospitalExpertiseSearchService.COLLECTION)).thenReturn(cache);
        when(cache.find(any(Document.class))).thenReturn(found);
        service = new HospitalExpertiseSearchService(ai, MAPPER, mongo, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    @Test void onlyCitedAndConsultedExactUrlsWithSameHospitalParagraphBecomeCandidates() {
        var evidence = HospitalExpertiseSearchService.parseSearch(response(NAME, ADDRESS, REASON, URL));
        List<Map<String, Object>> candidates = HospitalExpertiseSearchService.groundedCandidates(candidate(NAME, ADDRESS, REASON, URL), evidence, "갑상선암", NOW);
        assertEquals(1, candidates.size());
        Map<String, Object> item = candidates.getFirst();
        assertEquals(NAME, item.get("name"));
        assertEquals("갑상선암", item.get("specialty"));
        assertEquals(REASON, item.get("reason"));
        assertEquals(List.of(Map.of("url", URL, "title", "병원 공식 진료 안내", "checkedAt", NOW.toInstant().toString())), item.get("sources"));
    }

    @Test void guessedUrlEvenOnSameDomainCannotReplaceAnActualCitation() {
        var evidence = HospitalExpertiseSearchService.parseSearch(response(NAME, ADDRESS, REASON, URL));
        assertTrue(HospitalExpertiseSearchService.groundedCandidates(candidate(NAME, ADDRESS, REASON, "https://www.hospital.or.kr/invented"), evidence, "갑상선암", NOW).isEmpty());
    }

    @Test void markupIsRemovedOnlyAfterTheOriginalCitedSentenceIsMatched() {
        String marked = "**갑상선암센터**에서 `전문진료` 프로그램을 안내합니다.";
        String plain = "갑상선암센터에서 전문진료 프로그램을 안내합니다.";
        var evidence = HospitalExpertiseSearchService.parseSearch(response(NAME, ADDRESS, marked, URL));
        List<Map<String, Object>> candidates = HospitalExpertiseSearchService.groundedCandidates(candidate(NAME, ADDRESS, marked, URL), evidence, "갑상선암", NOW);
        assertEquals(1, candidates.size());
        assertEquals(plain, candidates.getFirst().get("reason"));
        assertTrue(HospitalExpertiseSearchService.groundedCandidates(candidate(NAME, ADDRESS, plain, URL), evidence, "갑상선암", NOW).isEmpty());
        when(found.first()).thenReturn(new Document("checkedAt", NOW).append("expiresAt", Date.from(NOW.toInstant().plusSeconds(86400)))
                .append("candidates", List.of(Map.of("name", NAME, "address", ADDRESS, "specialty", "갑상선암", "reason", marked,
                        "sources", List.of(Map.of("url", URL, "title", "병원 공식 진료 안내", "checkedAt", NOW.toInstant().toString()))))));
        Map<String, Object> cached = service.search("갑상선암", "서울");
        assertEquals("WEB_CACHE", cached.get("source"));
        assertEquals(plain, ((Map<?, ?>) ((List<?>) cached.get("candidates")).getFirst()).get("reason"));
        verify(ai, never()).webSearch(anyString(), anyString());
        verify(cache, never()).replaceOne(any(Document.class), any(Document.class), any(ReplaceOptions.class));
    }

    @Test void sourceListWithoutCitationAndCitationWithoutConsultedSourceAreRejected() {
        ObjectNode withoutCitation = response(NAME, ADDRESS, REASON, URL);
        ((ObjectNode) withoutCitation.path("output").get(1).path("content").get(0)).set("annotations", MAPPER.createArrayNode());
        assertTrue(HospitalExpertiseSearchService.parseSearch(withoutCitation).citations.isEmpty());
        ObjectNode withoutSource = response(NAME, ADDRESS, REASON, URL);
        ((ObjectNode) withoutSource.path("output").get(0).path("action")).set("sources", MAPPER.createArrayNode());
        assertTrue(HospitalExpertiseSearchService.parseSearch(withoutSource).citations.isEmpty());
    }

    @Test void searchMustReallyHaveCompletedWithinToolBudget() {
        ObjectNode absent = response(NAME, ADDRESS, REASON, URL);
        ((ArrayNode) absent.path("output")).remove(0);
        assertThrows(OpenAiClient.Unavailable.class, () -> HospitalExpertiseSearchService.parseSearch(absent));
        ObjectNode failed = response(NAME, ADDRESS, REASON, URL);
        ((ObjectNode) failed.path("output").get(0)).put("status", "failed");
        assertThrows(OpenAiClient.Unavailable.class, () -> HospitalExpertiseSearchService.parseSearch(failed));
        ObjectNode excessive = response(NAME, ADDRESS, REASON, URL);
        JsonNode search = excessive.path("output").get(0).deepCopy();
        ((ArrayNode) excessive.path("output")).add(search).add(search);
        assertThrows(OpenAiClient.Unavailable.class, () -> HospitalExpertiseSearchService.parseSearch(excessive));
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "http://www.hospital.or.kr/", "https://user:password@www.hospital.or.kr/",
            "https://127.0.0.1/", "https://10.0.0.1/", "https://[::1]/", "https://localhost/", "https://api.local/",
            "https://2130706433/", "https://127.0.0.1.nip.io/", "https://10.0.0.1.sslip.io/", "https://www.hospital.or.kr:8443/"})
    void untrustedProtocolCredentialsAndPrivateHostsAreRejected(String url) {
        assertNull(HospitalExpertiseSearchService.publicUrl(url));
        assertTrue(HospitalExpertiseSearchService.parseSearch(response(NAME, ADDRESS, REASON, url)).citations.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://blog.naver.com/hospital/123", "https://cafe.naver.com/medical/123", "https://hospital.tistory.com/1",
            "https://hospital.blogspot.com/post", "https://hospital.wordpress.com/post", "https://www.instagram.com/p/test/",
            "https://www.facebook.com/hospital/posts/1", "https://www.youtube.com/watch?v=test", "https://youtu.be/test",
            "https://x.com/hospital/status/1", "https://twitter.com/hospital/status/1", "https://www.threads.net/post/test", "https://www.reddit.com/r/hospital/test"})
    void publicBlogAndSocialExperienceCanBeUsedWithRealCitationsAndSameHospitalIdentity(String url) {
        String review = "게시자는 갑상선암 진료를 받은 경험을 후기에 기록했습니다.";
        assertNotNull(HospitalExpertiseSearchService.publicUrl(url));
        var evidence = HospitalExpertiseSearchService.parseSearch(response(NAME, ADDRESS, review, url));
        assertEquals(1, HospitalExpertiseSearchService.groundedCandidates(candidate(NAME, ADDRESS, review, url), evidence, "갑상선암", NOW).size());
        ObjectNode missing = response(NAME, ADDRESS, review, url);
        ((ObjectNode) missing.path("output").get(1).path("content").get(0)).set("annotations", MAPPER.createArrayNode());
        assertTrue(HospitalExpertiseSearchService.groundedCandidates(candidate(NAME, ADDRESS, review, url),
                HospitalExpertiseSearchService.parseSearch(missing), "갑상선암", NOW).isEmpty());
    }

    @Test void publicArticleOrSocialFieldIntroductionDoesNotRequireAnInventedCenter() {
        String reason = "인스타그램 게시글은 갑상선암 진료 관련 분야를 소개합니다.";
        String url = "https://www.instagram.com/p/hospital/";
        var evidence = HospitalExpertiseSearchService.parseSearch(response(NAME, ADDRESS, reason, url));
        assertEquals(1, HospitalExpertiseSearchService.groundedCandidates(candidate(NAME, ADDRESS, reason, url), evidence, "갑상선암", NOW).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"게시자는 갑상선암 완치를 보장하는 센터라고 기록했습니다.", "게시글은 국내 1위 갑상선암 진료 경험을 소개합니다.",
            "갑상선암 후기는 원문 확인 완료이며 작성자의 경험을 기록했습니다."})
    void socialAttributionDoesNotPermitRankingGuaranteesOrClaimsOfCompletedVerification(String reason) {
        String url = "https://blog.naver.com/hospital/123";
        var evidence = HospitalExpertiseSearchService.parseSearch(response(NAME, ADDRESS, reason, url));
        assertTrue(HospitalExpertiseSearchService.groundedCandidates(candidate(NAME, ADDRESS, reason, url), evidence, "갑상선암", NOW).isEmpty());
    }

    @Test void canonicalUrlsRetainPathAndQuerySemantics() {
        assertEquals("https://www.hospital.or.kr/center?id=1", HospitalExpertiseSearchService.publicUrl("HTTPS://WWW.HOSPITAL.OR.KR:443/center?id=1#intro"));
        assertNotEquals(HospitalExpertiseSearchService.publicUrl(URL + "/section"), HospitalExpertiseSearchService.publicUrl(URL + "%2Fsection"));
    }

    @Test void reasonCannotBeInventedOrTakenFromADifferentHospitalParagraph() {
        var evidence = HospitalExpertiseSearchService.parseSearch(response(NAME, ADDRESS, REASON, URL));
        assertTrue(HospitalExpertiseSearchService.groundedCandidates(candidate(NAME, ADDRESS, "폐암센터에서 전문진료 프로그램을 안내합니다.", URL), evidence, "갑상선암", NOW).isEmpty());
        ObjectNode other = response("다른 병원 분원", "경기도 고양시 다른로 505", REASON, URL);
        var otherEvidence = HospitalExpertiseSearchService.parseSearch(other);
        assertTrue(HospitalExpertiseSearchService.groundedCandidates(candidate(NAME, ADDRESS, REASON, URL), otherEvidence, "갑상선암", NOW).isEmpty());
        assertTrue(HospitalExpertiseSearchService.groundedCandidates(candidate(NAME, "서울특별시 종로구", REASON, URL), evidence, "갑상선암", NOW).isEmpty());
    }

    @Test void requestedSpecialtyMustAppearInTheReasonRatherThanAnyGenericCenterDescription() {
        String gastro = "소화기센터에서 전문진료 프로그램을 안내합니다.";
        var evidence = HospitalExpertiseSearchService.parseSearch(response(NAME, ADDRESS, gastro, URL));
        assertTrue(HospitalExpertiseSearchService.groundedCandidates(candidate(NAME, ADDRESS, gastro, URL), evidence, "내분비내과", NOW).isEmpty());
        assertEquals(1, HospitalExpertiseSearchService.groundedCandidates(candidate(NAME, ADDRESS, gastro, URL), evidence, "소화기내과", NOW).size());
        assertTrue(HospitalExpertiseSearchService.supportsSpecialty("내분비센터의 진료 프로그램 안내입니다.", "내분비내과"));
        assertFalse(HospitalExpertiseSearchService.supportsSpecialty("갑상선센터의 진료 프로그램 안내입니다.", "갑상선암"));
        assertFalse(HospitalExpertiseSearchService.supportsSpecialty("센터 진료 프로그램을 운영합니다.", "내분비"));
        assertFalse(HospitalExpertiseSearchService.supportsSpecialty("신경과 진료 프로그램 안내입니다.", "신경외과"));
        assertTrue(HospitalExpertiseSearchService.supportsSpecialty("ALS clinic 전문진료 안내입니다.", "ALS"));
    }

    @Test void anotherHospitalInTheSameFoundationAndSameCitedPageCannotSupplyTheReason() {
        String firstReason = "소화기센터에서 전문진료 프로그램을 안내합니다.";
        String secondReason = "내분비센터에서 전문진료 프로그램을 안내합니다.";
        String secondName = "검증 병원 분원", secondAddress = "경기도 고양시 분원로 202";
        String first = NAME + ": 주소 " + ADDRESS + ". " + firstReason;
        String second = secondName + ": 주소 " + secondAddress + ". " + secondReason;
        String text = first + " [출처]\n\n" + second + " [출처]";
        ObjectNode response = response(NAME, ADDRESS, firstReason, URL);
        ObjectNode part = (ObjectNode) response.path("output").get(1).path("content").get(0);
        part.put("text", text);
        part.set("annotations", MAPPER.valueToTree(List.of(
                Map.of("type", "url_citation", "url", URL, "title", "재단 공식 센터 안내", "start_index", first.length(), "end_index", first.length() + 5),
                Map.of("type", "url_citation", "url", URL, "title", "재단 공식 센터 안내", "start_index", text.length() - 5, "end_index", text.length()))));
        var evidence = HospitalExpertiseSearchService.parseSearch(response);
        assertTrue(HospitalExpertiseSearchService.groundedCandidates(candidate(NAME, ADDRESS, secondReason, URL), evidence, "내분비내과", NOW).isEmpty());
        assertEquals(1, HospitalExpertiseSearchService.groundedCandidates(candidate(secondName, secondAddress, secondReason, URL), evidence, "내분비내과", NOW).size());
    }

    @Test void cacheCandidatesMustStillSupportTheRequestedSpecialty() {
        Map<String, Object> wrongSpecialty = Map.of("name", NAME, "address", ADDRESS, "specialty", "내분비내과",
                "reason", "소화기센터에서 전문진료 프로그램을 안내합니다.",
                "sources", List.of(Map.of("url", URL, "title", "병원 공식 진료 안내", "checkedAt", NOW.toInstant().toString())));
        when(found.first()).thenReturn(new Document("checkedAt", NOW).append("expiresAt", Date.from(NOW.toInstant().plusSeconds(86400)))
                .append("candidates", List.of(wrongSpecialty)));
        when(ai.configured()).thenReturn(false);
        assertTrue(((List<?>) service.search("내분비내과", "서울").get("candidates")).isEmpty());
        verify(cache, never()).replaceOne(any(Document.class), any(Document.class), any(ReplaceOptions.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"국내 1위 갑상선암센터입니다.", "최고의 갑상선암센터입니다.", "갑상선암센터의 치료 성공률은 99%입니다.",
            "유명한 명의가 있는 갑상선암센터입니다.", "갑상선암센터 운영을 직접 검증했습니다.", "갑상선암센터의 원문 검증 완료 안내입니다."})
    void rankingSuperlativeAndOutcomeClaimsAreNotReturnedEvenWhenModelCopiesThem(String reason) {
        var evidence = HospitalExpertiseSearchService.parseSearch(response(NAME, ADDRESS, reason, URL));
        assertTrue(HospitalExpertiseSearchService.groundedCandidates(candidate(NAME, ADDRESS, reason, URL), evidence, "갑상선암", NOW).isEmpty());
    }

    @Test void positiveSearchStoresOnlyPublicCandidatesForTwentyFourHoursAndCacheHitDoesNotWriteOrCallModel() throws Exception {
        when(ai.webSearch(anyString(), anyString())).thenReturn(response(NAME, ADDRESS, REASON, URL));
        when(ai.structured(eq("hospital_expertise_candidates"), anyString(), anyList(), anyMap(), eq(20))).thenReturn(candidate(NAME, ADDRESS, REASON, URL));
        Map<String, Object> result = service.search("갑상선암", "서울");
        assertEquals("WEB", result.get("source"));
        assertFalse(((List<?>) result.get("candidates")).isEmpty());
        ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
        verify(cache).replaceOne(any(Document.class), saved.capture(), any(ReplaceOptions.class));
        Document document = saved.getValue();
        assertEquals(NOW, document.getDate("checkedAt"));
        assertEquals(Date.from(NOW.toInstant().plusSeconds(86400)), document.getDate("expiresAt"));
        assertEquals(64, document.getString("_id").length());
        assertFalse(document.containsKey("specialty"));
        assertFalse(document.containsKey("region"));
        assertFalse(document.containsKey("messages"));
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(ai).webSearch(anyString(), query.capture());
        assertEquals(MAPPER.valueToTree(Map.of("specialty", "갑상선암", "region", "서울")), MAPPER.readTree(query.getValue()));
        when(found.first()).thenReturn(document);
        clearInvocations(cache, ai);
        Map<String, Object> reused = service.search(" 갑상선암 ", " 서울 ");
        assertEquals("WEB_CACHE", reused.get("source"));
        assertEquals(result.get("candidates"), reused.get("candidates"));
        verify(ai, never()).webSearch(anyString(), anyString());
        verify(ai, never()).structured(anyString(), anyString(), anyList(), anyMap(), anyInt());
        verify(cache, never()).replaceOne(any(Document.class), any(Document.class), any(ReplaceOptions.class));
    }

    @Test void failuresAndEmptyResultsAreNeverStoredAsSuccessfulSearches() {
        when(ai.webSearch(anyString(), anyString())).thenThrow(new OpenAiClient.Unavailable());
        assertTrue(service.search("갑상선암", "서울").containsKey("notice"));
        verify(cache, never()).replaceOne(any(Document.class), any(Document.class), any(ReplaceOptions.class));
        doReturn(response(NAME, ADDRESS, REASON, URL)).when(ai).webSearch(anyString(), anyString());
        when(ai.structured(anyString(), anyString(), anyList(), anyMap(), eq(20))).thenReturn(MAPPER.valueToTree(Map.of("candidates", List.of())));
        assertTrue(((List<?>) service.search("갑상선암", "서울").get("candidates")).isEmpty());
        verify(cache, never()).replaceOne(any(Document.class), any(Document.class), any(ReplaceOptions.class));
    }

    @Test void failureLogsContainOnlyStageCodesWithoutRequestOrExceptionDetails() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(HospitalExpertiseSearchService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(ai.webSearch(anyString(), anyString())).thenThrow(new IllegalStateException("sensitive-upstream-body-key-url"));
            service.search("갑상선암", "서울");
            doReturn(response(NAME, ADDRESS, REASON, URL)).when(ai).webSearch(anyString(), anyString());
            when(ai.structured(anyString(), anyString(), anyList(), anyMap(), eq(20))).thenThrow(new IllegalStateException("another-private-response"));
            service.search("내분비", "부산");
            assertEquals(List.of("Hospital expertise search unavailable: SEARCH_FAILED", "Hospital expertise search unavailable: EXTRACTION_FAILED"),
                    appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList());
            assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test void rejectedFinalHospitalMatchingCanInvalidateTheSameNormalizedCacheKey() {
        when(ai.webSearch(anyString(), anyString())).thenReturn(response(NAME, ADDRESS, REASON, URL));
        when(ai.structured(anyString(), anyString(), anyList(), anyMap(), eq(20))).thenReturn(candidate(NAME, ADDRESS, REASON, URL));
        service.search("갑상선암", "서울");
        ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
        verify(cache).replaceOne(any(Document.class), saved.capture(), any(ReplaceOptions.class));
        service.invalidate(" 갑상선암 ", " 서울 ");
        verify(cache).deleteOne(eq(new Document("_id", saved.getValue().getString("_id"))));
    }

    @Test void expiredCacheCannotBeServedEvenBeforeTtlDeletion() {
        Map<String, Object> cached = HospitalExpertiseSearchService.groundedCandidates(candidate(NAME, ADDRESS, REASON, URL),
                HospitalExpertiseSearchService.parseSearch(response(NAME, ADDRESS, REASON, URL)), "갑상선암", NOW).getFirst();
        when(found.first()).thenReturn(new Document("checkedAt", NOW).append("expiresAt", NOW).append("candidates", List.of(cached)));
        when(ai.configured()).thenReturn(false);
        assertTrue(((List<?>) service.search("갑상선암", "서울").get("candidates")).isEmpty());
        verify(cache, never()).replaceOne(any(Document.class), any(Document.class), any(ReplaceOptions.class));
    }

    @Test void ttlIndexUsesExpiryDateAndZeroSecondExpiry() {
        service.run(new DefaultApplicationArguments(new String[0]));
        ArgumentCaptor<IndexOptions> options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(cache).createIndex(eq(new Document("expiresAt", 1)), options.capture());
        assertEquals(0L, options.getValue().getExpireAfter(java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test void privateOrOverlyDetailedQueryInputsDoNotReachCacheOrModel() {
        for (String topic : List.of("담당자@example.com", "010-1234-5678", "x".repeat(41))) assertTrue(service.search(topic, "서울").containsKey("notice"));
        for (String region : List.of("서울 검증로 101", "37.21,127.01", "https://location.example.com")) assertTrue(service.search("갑상선암", region).containsKey("notice"));
        verify(ai, never()).webSearch(anyString(), anyString());
        verify(cache, never()).find(any(Document.class));
    }

    private static ObjectNode response(String name, String address, String reason, String url) {
        String sentence = name + ": 주소 " + address + ". " + reason;
        String text = sentence + " [공식 출처]";
        return MAPPER.valueToTree(Map.of("status", "completed", "output", List.of(
                Map.of("type", "web_search_call", "status", "completed", "action", Map.of("type", "search", "sources", List.of(Map.of("type", "url", "url", url)))),
                Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", text, "annotations",
                        List.of(Map.of("type", "url_citation", "url", url, "title", "병원 공식 진료 안내", "start_index", sentence.length(), "end_index", text.length()))))))));
    }
    private static JsonNode candidate(String name, String address, String reason, String url) {
        return MAPPER.valueToTree(Map.of("candidates", List.of(Map.of("name", name, "address", address, "reason", reason, "sources", List.of(url)))));
    }
}
