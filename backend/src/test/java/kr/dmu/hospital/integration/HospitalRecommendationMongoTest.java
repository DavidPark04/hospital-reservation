package kr.dmu.hospital.integration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = "^mongodb://(?:127\\.0\\.0\\.1|localhost)(?::\\d+)?/.*$")
class HospitalRecommendationMongoTest {
    private MongoClient client;
    private MongoTemplate mongo;
    private HospitalExpertiseSearchService web;
    private HospitalRecommendationService service;
    @BeforeEach void setUp() {
        client = MongoClients.create(System.getenv("MONGODB_TEST_URI"));
        mongo = new MongoTemplate(client, "recommendation_test_" + UUID.randomUUID().toString().replace("-", ""));
        web = mock(HospitalExpertiseSearchService.class);
        service = new HospitalRecommendationService(mongo, web);
    }
    @AfterEach void clean() { if (mongo != null) mongo.getDb().drop(); if (client != null) client.close(); }

    @Test void nearestClinicBeatsAlphabeticOrderAndExcludesInactiveAndTertiaryHospitals() {
        save(hospital("far", "가나다의원", "경기도 수원시 영통구 영통로 10", "의원", 37.28, 127.08));
        save(hospital("near", "하늘의원", "경기도 수원시 영통구 영통로 11", "의원", 37.25, 127.05));
        save(hospital("tertiary", "큰대학병원", "경기도 수원시 영통구 영통로 12", "상급종합", 37.251, 127.051));
        save(hospital("closed", "폐원의원", "경기도 수원시 영통구 영통로 13", "의원", 37.25, 127.05).append("active", false));
        Map<String, Object> result = service.recommend("01", "내과", "수원", "NEARBY", false, 37.25, 127.05);
        List<?> items = (List<?>) result.get("items");
        assertEquals(2, items.size());
        Map<?, ?> first = (Map<?, ?>) items.getFirst();
        assertEquals("near", first.get("id"));
        assertEquals(0.0, first.get("distanceKm"));
        assertFalse(first.containsKey("apiFields"));
        assertFalse(first.containsKey("externalId"));
        assertTrue(result.get("notice").toString().contains("직선거리"));
        verifyNoInteractions(web);
        assertEquals(0, mongo.getCollection("hospitalDetails").countDocuments());
    }

    @Test void coordinateOnlySearchWorksWithoutInventingLocationOrOpeningHours() {
        save(hospital("near", "하늘의원", "경기도 수원시 영통구 영통로 11", "의원", 37.25, 127.05));
        assertEquals(1, ((List<?>)service.recommend("01", "내과", "", "NEARBY", true, 37.25, 127.05).get("items")).size());
        Map<String, Object> noLocation = service.recommend("01", "내과", "", "NEARBY", false, null, null);
        assertTrue(((List<?>)noLocation.get("items")).isEmpty());
        assertThrows(kr.dmu.hospital.common.ApiException.class, () -> service.recommend("01", "", "서울", "NEARBY", false, Double.NaN, 127.0));
    }

    @Test void matchesOfficialBranchAndPreservesItsDatabaseId() {
        save(hospital("main", "한빛대학교병원", "서울특별시 종로구 대학로 101", "상급종합", 37.58, 127.0));
        save(hospital("branch", "한빛대학교분당병원", "경기도 성남시 분당구 구미로 173", "상급종합", 37.35, 127.12));
        when(web.search("소화기내과", "전국")).thenReturn(webResult(candidate("한빛대학교병원", "서울 종로구 대학로 101", "https://hospital.example.org/gastro")));
        List<?> items = (List<?>) service.recommend("01", "소화기내과", "전국", "SPECIALIST", false, null, null).get("items");
        assertEquals(1, items.size());
        assertEquals("main", ((Map<?, ?>)items.getFirst()).get("id"));
        assertEquals(1, ((List<?>)((Map<?, ?>)items.getFirst()).get("recommendationSources")).size());
    }

    @Test void labelsPublicBlogEvidenceButExcludesInventedHospital() {
        save(hospital("real", "한빛대학교병원", "서울특별시 종로구 대학로 101", "상급종합", 37.58, 127.0));
        when(web.search("내분비내과", "서울")).thenReturn(webResult(
            candidate("한빛대학교병원", "서울 종로구 대학로 101", "https://blog.naver.com/test"),
            candidate("존재하지않는병원", "서울 종로구 대학로 101", "https://hospital.example.org/info")));
        List<?> items = (List<?>) service.recommend("01", "내분비내과", "서울", "SPECIALIST", false, null, null).get("items");
        assertEquals(1, items.size());
        List<?> sources = (List<?>) ((Map<?, ?>) items.getFirst()).get("recommendationSources");
        assertEquals("EXPERIENCE", ((Map<?, ?>) sources.getFirst()).get("kind"));
    }

    @Test void ambiguousAddressAndDifferentRegionDoNotSilentlyMatch() {
        save(hospital("a", "한빛병원", "서울특별시 종로구 대학로 101", "종합병원", 37.58, 127.0));
        save(hospital("b", "한빛병원", "서울특별시 종로구 대학로 101", "종합병원", 37.58, 127.0));
        when(web.search("관절", "서울")).thenReturn(webResult(candidate("한빛병원", "서울 종로구 대학로 101", "https://hospital.example.org/info")));
        assertTrue(((List<?>) service.recommend("05", "관절", "서울", "SPECIALIST", false, null, null).get("items")).isEmpty());
        assertFalse(HospitalRecommendationService.addressesMatch("서울 종로구", "서울특별시 종로구 대학로 101"));
        assertFalse(HospitalRecommendationService.addressesMatch("서울 종로구 대학로 101", "서울특별시 종로구 대학로 1010"));
        assertTrue(HospitalRecommendationService.addressesMatch("서울 송파구 올림픽로43길 88", "서울특별시 송파구 올림픽로43길 88, 서울아산병원 (풍납동)"));
        assertFalse(HospitalRecommendationService.regionMatches("서울", "경기도 고양시 일산동구 일산로 323"));
        assertTrue(HospitalRecommendationService.regionMatches("수도권", "경기도 고양시 일산동구 일산로 323"));
    }

    @Test void unsafeOrLookalikeSourceDomainsAreRejected() {
        Document hospital = hospital("real", "한빛병원", "서울특별시 종로구 대학로 101", "종합병원", 37.58, 127.0);
        for (String url : List.of("javascript:alert(1)", "https://hospital.example.org.evil.com/a", "https://hospital.example.org@evil.com/a", "http://127.0.0.1/info"))
            assertTrue(HospitalRecommendationService.recommendationSources(candidate("", "", url).get("sources"), hospital).isEmpty());
        assertEquals("OFFICIAL", HospitalRecommendationService.recommendationSources(candidate("", "", "https://www.hira.or.kr/assessment").get("sources"), hospital).getFirst().get("kind"));
        assertEquals("EXPERIENCE", HospitalRecommendationService.recommendationSources(candidate("", "", "https://www.instagram.com/p/hospital").get("sources"), hospital).getFirst().get("kind"));
        assertEquals("NEWS", HospitalRecommendationService.recommendationSources(candidate("", "", "https://www.dailymedi.com/news/item").get("sources"), hospital).getFirst().get("kind"));
        assertEquals("WEB", HospitalRecommendationService.recommendationSources(candidate("", "", "https://health-reference.org/item").get("sources"), hospital).getFirst().get("kind"));
    }

    @Test void explicitProvinceDoesNotMatchAnotherCityOrRoadName() {
        assertTrue(HospitalRecommendationService.regionMatches("광주광역시", "광주광역시 동구 제봉로 42"));
        assertFalse(HospitalRecommendationService.regionMatches("광주광역시", "경기도 광주시 중앙로 10"));
        assertFalse(HospitalRecommendationService.regionMatches("서울", "경기도 양주시 서울로 101"));
        save(hospital("gwangju", "가나다의원", "광주광역시 동구 제봉로 42", "의원", 35.14, 126.92));
        save(hospital("gyeonggi", "가나다의원", "경기도 광주시 중앙로 10", "의원", 37.4, 127.2));
        List<?> items = (List<?>) service.recommend("01", "내과", "광주광역시", "NEARBY", false, null, null).get("items");
        assertEquals(1, items.size());
        assertEquals("gwangju", ((Map<?, ?>)items.getFirst()).get("id"));
    }

    @Test void unregisteredHospitalsAreExcludedFromNearbyAndWebRecommendations() {
        save(hospital("registered", "등록의원", "서울특별시 종로구 대학로 101", "의원", 37.58, 127.0));
        mongo.insert(hospital("hidden", "미등록병원", "서울특별시 종로구 대학로 102", "의원", 37.58, 127.0), HospitalCatalog.COLLECTION);
        List<?> nearby = (List<?>) service.recommend("01", "내과", "서울", "NEARBY", false, null, null).get("items");
        assertEquals(1, nearby.size());
        assertEquals("registered", ((Map<?, ?>) nearby.getFirst()).get("id"));
        when(web.search("내과", "서울")).thenReturn(webResult(candidate("미등록병원", "서울 종로구 대학로 102", "https://hospital.example.org/info")));
        assertTrue(((List<?>) service.recommend("01", "내과", "서울", "SPECIALIST", false, null, null).get("items")).isEmpty());
        assertEquals(2, mongo.getCollection(HospitalCatalog.COLLECTION).countDocuments());
    }

    private void save(Document hospital) {
        mongo.save(hospital, HospitalCatalog.COLLECTION);
        mongo.save(new Document("_id", hospital.getString("_id")).append("status", "ACTIVE")
                .append("departmentCodes", hospital.get("departmentCodes")).append("departments", hospital.get("departments")), RegisteredHospitals.COLLECTION);
    }
    private Document hospital(String id, String name, String address, String type, double lat, double lon) {
        return new Document("_id", id).append("provider", "HIRA").append("active", true).append("externalId", "not-public-" + id)
            .append("name", name).append("address", address).append("tel", "02-123-4567")
            .append("latitude", lat).append("longitude", lon).append("basicFetchedAt", new Date())
            .append("apiFields", new Document("clCdNm", type).append("hospUrl", "http://hospital.example.org"))
            .append("departmentCodes", List.of("01", "05")).append("departments", List.of(new Document("code", "01").append("name", "내과")));
    }
    private Map<String, Object> candidate(String name, String address, String url) {
        return Map.of("name", name, "address", address, "reason", "공식 홈페이지에 해당 분야 전문센터 운영이 안내되어 있어요.",
                "sources", List.of(Map.of("url", url, "title", "전문센터 안내", "checkedAt", "2026-11-21T00:00:00Z")));
    }
    @SafeVarargs private final Map<String, Object> webResult(Map<String, Object>... candidates) { return Map.of("candidates", List.of(candidates), "source", "WEB"); }
}
