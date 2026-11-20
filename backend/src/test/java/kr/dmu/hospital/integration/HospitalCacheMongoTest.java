package kr.dmu.hospital.integration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No external API calls; only an explicitly configured local replica set is used. */
@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI",
        matches = "^mongodb://(?:127\\.0\\.0\\.1|localhost)(?::\\d+)?/.*$")
class HospitalCacheMongoTest {
    private MongoClient client;
    private MongoTemplate mongo;
    private HospitalService service;
    private HiraClient hira;

    @BeforeEach
    void setUp() {
        client = MongoClients.create(System.getenv("MONGODB_TEST_URI"));
        var factory = new SimpleMongoClientDatabaseFactory(client,
                "hospital_catalog_test_" + UUID.randomUUID().toString().replace("-", ""));
        mongo = new MongoTemplate(factory);
        new HospitalIndexes(mongo).run(new DefaultApplicationArguments());
        new HospitalCatalogIndexes(mongo).run(new DefaultApplicationArguments());
        hira = mock(HiraClient.class);
        service = new HospitalService(mongo, hira, new MongoTransactionManager(factory));
    }

    @AfterEach
    void tearDown() {
        if (mongo != null) mongo.getDb().drop();
        if (client != null) client.close();
    }

    @Test
    void catalogFiltersNameRegionAndDepartmentWithoutAnyExternalRequest() {
        insert(basic("match", "우리 병원", "경기도 수원시 영통구", "01"));
        insert(basic("region", "우리 병원", "서울특별시 강남구", "01"));
        insert(basic("department", "우리 병원", "경기도 수원시 영통구", "13"));
        insert(basic("name", "다른 병원", "경기도 수원시 영통구", "01"));
        insert(basic("inactive", "우리 병원", "경기도 수원시 영통구", "01").append("active", false));
        Document unverified = basic("unverified", "우리 병원", "경기도 수원시 영통구", "01");
        unverified.remove("active");
        insert(unverified);
        Map<String, Object> result = service.search("우리", "수원 영통", "01");
        List<?> items = (List<?>) result.get("items");
        assertEquals("CACHE", result.get("source"));
        assertTrue(result.get("message").toString().contains("매일 갱신"));
        assertEquals(1, items.size());
        Map<?, ?> hospital = (Map<?, ?>) items.getFirst();
        assertEquals(id("match"), hospital.get("id"));
        assertEquals("1970-01-01T00:00:00Z", hospital.get("fetchedAt"));
        assertFalse(hospital.containsKey("apiFields"));
        assertFalse(hospital.containsKey("externalId"));
        assertEquals(0, mongo.getCollection("hospitals").countDocuments());
        verifyNoInteractions(hira);
    }

    @Test
    void searchReturnsAtMostThirtyStableResultsWithoutMaterializingThem() {
        for (int i = 41; i >= 0; i--) insert(basic("row" + i, "병원" + String.format("%02d", i), "서울", "01"));
        List<?> items = (List<?>) service.search("", "", "").get("items");
        assertEquals(30, items.size());
        assertEquals("병원00", ((Map<?, ?>) items.getFirst()).get("name"));
        assertEquals("병원29", ((Map<?, ?>) items.getLast()).get("name"));
        assertEquals(0, mongo.getCollection("hospitals").countDocuments());
        verifyNoInteractions(hira);
    }

    @Test
    void missingSearchDoesNotCallApiOrFallBackToAnOldRealHospital() {
        mongo.insert(basic("old", "이전 병원", "서울", "01").append("source", "LIVE"), "hospitals");
        assertTrue(((List<?>) service.search("", "", "").get("items")).isEmpty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.detail(id("old"))).getStatus());
        verifyNoInteractions(hira);
    }

    @Test
    void nextSearchSeesCatalogChangesImmediatelyAndQuotesRegexInput() {
        insert(basic("edited", "이전 병원", "서울", "01"));
        service.search("이전", "서울", "01");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id("edited"))),
                new Update().set("name", "[새] 병원").set("address", "수원 영통"), HospitalCatalog.COLLECTION);
        List<?> items = (List<?>) service.search("[새]", "수원 영통", "01").get("items");
        assertEquals(1, items.size());
        assertTrue(((List<?>) service.search(".*", "", "").get("items")).isEmpty());
        verifyNoInteractions(hira);
    }

    @Test
    void provinceAbbreviationsAndRenamedProvincesMatchStoredAddresses() {
        insert(basic("chungbuk", "청주 병원", "충청북도 청주시 흥덕구", "01"));
        insert(basic("jeonbuk", "전주 병원", "전북특별자치도 전주시", "01"));
        insert(basic("gangwon", "춘천 병원", "강원특별자치도 춘천시", "01"));
        assertEquals(1, ((List<?>) service.search("", "충북 청주", "01").get("items")).size());
        assertEquals(1, ((List<?>) service.search("", "전라북도 전주", "01").get("items")).size());
        assertEquals(1, ((List<?>) service.search("", "강원도 춘천", "01").get("items")).size());
        verifyNoInteractions(hira);
    }

    @Test
    void firstDetailMaterializesOnlyThatHospitalAndRepeatedReadDoesNotRewriteIt() {
        insert(basic("opened", "선택 병원", "서울", "01"));
        insert(basic("other", "미선택 병원", "부산", "01"));
        assertEquals("선택 병원", service.detail(id("opened")).get("name"));
        Document before = cached("opened");
        assertEquals(1, mongo.getCollection("hospitals").countDocuments());
        assertEquals(1, mongo.getCollection("hospitalEvidence").countDocuments());
        service.detail(id("opened"));
        assertEquals(before, cached("opened"));
        verify(hira, never()).search(anyString(), anyString(), anyString());
        verify(hira, never()).detail(anyString(), anyString());
    }

    @Test
    void cachedDetailsUseLatestBasicFactsWithoutLosingDetailedFactsOrCallingApi() {
        insert(basic("cached", "새 병원명", "새 주소", "01"));
        Date detailTime = new Date();
        mongo.insert(basic("cached", "이전 병원명", "이전 주소", "13")
                .append("latitude", 37.0).append("longitude", 127.0)
                .append("location", new Document("latitude", 37.0).append("longitude", 127.0))
                .append("detailFetchedAt", detailTime).append("detailComplete", true)
                .append("evidence", List.of(fact(id("cached"), "SPECIAL", "투석", detailTime))), "hospitals");
        Map<String, Object> view = service.detail(id("cached"));
        assertEquals("새 병원명", view.get("name"));
        assertEquals("새 주소", view.get("address"));
        assertNull(view.get("location"));
        assertFalse(view.containsKey("latitude"));
        assertEquals("13", ((Map<?, ?>) ((List<?>) view.get("departments")).getFirst()).get("code"));
        assertTrue(view.get("evidence").toString().contains("투석"));
        assertEquals(detailTime.toInstant().toString(), view.get("fetchedAt"));
        verifyNoInteractions(hira);
    }

    @Test
    void threeDetailApisStillRefreshAndReuseTheirOneHourCache() {
        insert(basic("detail", "상세 병원", "서울", "01"));
        when(hira.detailsConfigured()).thenReturn(true);
        when(hira.detail("getDgsbjtInfo2.8", "detail")).thenReturn(List.of(Map.of("dgsbjtCd", "13", "dgsbjtCdNm", "이비인후과")));
        when(hira.detail("getSpclDiagInfo2.8", "detail")).thenReturn(List.of(Map.of("srchCdNm", "투석")));
        when(hira.detail("getSpclHospAsgFldList2.8", "detail")).thenReturn(List.of(Map.of("spclHospAsgFldNm", "관절")));
        Map<String, Object> first = service.detail(id("detail"));
        assertEquals("LIVE", first.get("source"));
        assertTrue(first.get("evidence").toString().contains("투석"));
        assertTrue(first.get("evidence").toString().contains("관절"));
        assertEquals("CACHE", service.detail(id("detail")).get("source"));
        for (String endpoint : List.of("getDgsbjtInfo2.8", "getSpclDiagInfo2.8", "getSpclHospAsgFldList2.8")) {
            verify(hira, times(1)).detail(endpoint, "detail");
        }
        assertEquals(List.of("01"), mongo.findById(id("detail"), Document.class, HospitalCatalog.COLLECTION).get("departmentCodes"));
        verify(hira, never()).search(anyString(), anyString(), anyString());
    }

    @Test
    void slowPartialDetailCannotOverwriteConcurrentCatalogUpdateOrDropExistingFacts() throws Exception {
        insert(basic("race", "이전 병원명", "이전 주소", "01"));
        mongo.insert(basic("race", "이전 병원명", "이전 주소", "01")
                .append("detailFetchedAt", new Date(0)).append("evidence", List.of(fact(id("race"), "SPECIAL", "이전 특수진료", new Date(0)))), "hospitals");
        when(hira.detailsConfigured()).thenReturn(true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(hira.detail("getDgsbjtInfo2.8", "race")).thenAnswer(invocation -> {
            started.countDown();
            assertTrue(finish.await(10, TimeUnit.SECONDS));
            return List.of(Map.of("dgsbjtCd", "01", "dgsbjtCdNm", "내과"));
        });
        when(hira.detail("getSpclDiagInfo2.8", "race")).thenThrow(new HiraClient.UpstreamUnavailable());
        when(hira.detail("getSpclHospAsgFldList2.8", "race")).thenReturn(List.of());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> service.detail(id("race")));
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS));
                mongo.updateFirst(Query.query(Criteria.where("_id").is(id("race"))),
                        new Update().set("name", "새 병원명").set("address", "새 주소"), HospitalCatalog.COLLECTION);
            } finally { finish.countDown(); }
            Map<String, Object> result = future.get(15, TimeUnit.SECONDS);
            assertEquals("새 병원명", result.get("name"));
            assertEquals("새 주소", result.get("address"));
            assertEquals("CACHE", result.get("source"));
            assertTrue(result.containsKey("notice"));
            assertTrue(result.get("evidence").toString().contains("이전 특수진료"));
            assertFalse(cached("race").getBoolean("detailComplete"));
            service.detail(id("race"));
            verify(hira, times(2)).detail("getDgsbjtInfo2.8", "race");
        }
    }

    @Test
    void failedDetailKeepsCatalogBasicInformationAvailable() {
        insert(basic("failure", "기본 병원", "서울", "01"));
        when(hira.detailsConfigured()).thenReturn(true);
        when(hira.detail(anyString(), eq("failure"))).thenThrow(new HiraClient.UpstreamUnavailable());
        Map<String, Object> result = service.detail(id("failure"));
        assertEquals("기본 병원", result.get("name"));
        assertEquals("CACHE", result.get("source"));
        assertTrue(result.containsKey("notice"));
        verify(hira, never()).search(anyString(), anyString(), anyString());
    }

    @Test
    void retiredHospitalCannotBeOpenedAndItsExistingCacheRemains() {
        insert(basic("retired", "종료 병원", "서울", "01").append("active", false));
        mongo.insert(basic("retired", "종료 병원", "서울", "01"), "hospitals");
        assertEquals(410, assertThrows(ApiException.class, () -> service.detail(id("retired"))).getStatus());
        assertTrue(((List<?>) service.search("", "", "").get("items")).isEmpty());
        assertNotNull(cached("retired"));
        verifyNoInteractions(hira);
    }

    @Test
    void samplesAppearOnlyWhenDemoIsEnabledAndCatalogIsEmpty() {
        mongo.insert(basic("sample", "체험 병원", "서울", "01").append("source", "SAMPLE"), "hospitals");
        assertTrue(((List<?>) service.search("체험", "", "").get("items")).isEmpty());
        ReflectionTestUtils.setField(service, "demoEnabled", true);
        assertEquals("SAMPLE", service.search("체험", "", "").get("source"));
        insert(basic("real", "실제 병원", "서울", "01"));
        assertTrue(((List<?>) service.search("체험", "", "").get("items")).isEmpty());
        verifyNoInteractions(hira);
    }

    @Test
    void unsupportedDepartmentIsRejectedWithoutApiCall() {
        assertEquals(400, assertThrows(ApiException.class, () -> service.search("", "", "99")).getStatus());
        verifyNoInteractions(hira);
    }

    private Document basic(String external, String name, String address, String department) {
        return new Document("_id", id(external)).append("provider", "HIRA").append("externalId", external)
                .append("name", name).append("address", address).append("tel", "").append("location", null)
                .append("departmentCodes", List.of(department)).append("departments", List.of(new Document("code", department).append("name", Departments.name(department))))
                .append("active", true).append("basicFetchedAt", new Date(0)).append("fetchedAt", new Date(0))
                .append("apiFields", new Document("ykiho", external).append("yadmNm", name));
    }

    private void insert(Document row) { mongo.insert(row, HospitalCatalog.COLLECTION); }
    private Document cached(String external) { return mongo.findById(id(external), Document.class, "hospitals"); }
    private static String id(String external) { return UUID.nameUUIDFromBytes(("HIRA:" + external).getBytes(StandardCharsets.UTF_8)).toString(); }
    private static Document fact(String id, String kind, String value, Date time) {
        return new Document("_id", id + kind).append("hospitalId", id).append("kind", kind)
                .append("label", kind).append("value", value).append("fetchedAt", time);
    }
}
