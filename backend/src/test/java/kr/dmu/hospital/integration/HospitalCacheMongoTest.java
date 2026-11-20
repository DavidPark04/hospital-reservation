package kr.dmu.hospital.integration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
    private String database;

    @BeforeEach
    void setUp() {
        client = MongoClients.create(System.getenv("MONGODB_TEST_URI"));
        database = "hospital_cache_test_" + UUID.randomUUID().toString().replace("-", "");
        SimpleMongoClientDatabaseFactory factory = new SimpleMongoClientDatabaseFactory(client, database);
        mongo = new MongoTemplate(factory);
        new HospitalIndexes(mongo).run(new DefaultApplicationArguments(new String[0]));
        hira = mock(HiraClient.class);
        service = new HospitalService(mongo, hira, new MongoTransactionManager(factory));
    }

    @AfterEach
    void tearDown() {
        if (mongo != null && database != null && database.matches("hospital_cache_test_[a-f0-9]{32}")) mongo.getDb().drop();
        if (client != null) client.close();
    }

    @Test
    void freshDatabaseCacheFiltersNameRegionAndDepartmentBeforeCallingApi() {
        Date now = new Date();
        insert(hospital("match", "영통 테스트의원", "경기도 수원시 영통구", "13", now));
        insert(hospital("wrong-name", "다른의원", "경기도 수원시 영통구", "13", now));
        insert(hospital("wrong-region", "테스트의원", "서울특별시 강남구", "13", now));
        insert(hospital("wrong-department", "테스트의원", "경기도 수원시 영통구", "01", now));
        insert(hospital("stale", "테스트의원", "경기도 수원시 영통구", "13", new Date(0)));
        insert(hospital("sample", "테스트의원", "경기도 수원시 영통구", "13", now)
                .append("provider", "SAMPLE").append("source", "SAMPLE"));

        Map<String, Object> result = service.search("테스트", "수원 영통", "13");
        assertEquals("CACHE", result.get("source"));
        assertTrue(result.get("message").toString().contains("전체 검색 결과와 다를 수"));
        List<?> items = (List<?>) result.get("items");
        assertEquals(1, items.size());
        assertEquals(id("match"), ((Map<?, ?>) items.getFirst()).get("id"));
        verifyNoInteractions(hira);
    }

    @Test
    void detailRefreshDoesNotMakeOldBasicSearchDataFresh() {
        insert(hospital("old", "오래된의원", "수원 영통", "13", new Date(0))
                .append("detailFetchedAt", new Date()).append("fetchedAt", new Date()));
        when(hira.search("오래된", "영통", "13")).thenReturn(List.of());
        Map<String, Object> result = service.search("오래된", "영통", "13");
        assertEquals("LIVE", result.get("source"));
        assertTrue(((List<?>) result.get("items")).isEmpty());
        verify(hira).search("오래된", "영통", "13");
    }

    @Test
    void basicRefreshKeepsDetailedFactsAndUpdatesBothEvidenceCopies() {
        Document department = fact("department", id("existing"), "DEPARTMENT", "이비인후과");
        insert(hospital("existing", "이전 이름", "수원 영통", "13", new Date(0))
                .append("evidence", List.of(department)));
        mongo.insert(department, "hospitalEvidence");
        when(hira.search("새로운", "영통", "13")).thenReturn(List.of(
                Map.of("ykiho", "existing", "yadmNm", "새로운의원", "addr", "수원 영통", "XPos", "127.05", "YPos", "37.25"),
                Map.of("ykiho", "no-coordinates", "yadmNm", "새로운 두번째의원", "addr", "수원 영통")));

        assertEquals("LIVE", service.search("새로운", "영통", "13").get("source"));
        Document saved = get("existing");
        assertEquals(new Document("latitude", 37.25).append("longitude", 127.05), saved.get("location"));
        assertEquals(37.25, saved.getDouble("latitude"));
        assertEquals(127.05, saved.getDouble("longitude"));
        assertTrue(saved.getList("evidence", Document.class).contains(department));
        Document basic = kind(saved, "BASIC");
        assertTrue(basic.containsKey("basePeriod"));
        assertNull(basic.get("basePeriod"));
        assertEquals(basic, mongo.findById(basic.get("_id"), Document.class, "hospitalEvidence"));
        assertEquals(saved.getDate("basicFetchedAt"), basic.getDate("fetchedAt"));
        assertTrue(get("no-coordinates").containsKey("location"));
        assertNull(get("no-coordinates").get("location"));
    }

    @Test
    void slowDetailResponseCannotOverwriteNewBasicInformationOrDropOtherFacts() throws Exception {
        Document special = fact("special", id("concurrent"), "SPECIAL", "이전 특수진료 근거");
        insert(hospital("concurrent", "이전 병원명", "수원 영통", "13", new Date(0))
                .append("evidence", List.of(special)));
        mongo.insert(special, "hospitalEvidence");
        when(hira.detailsConfigured()).thenReturn(true);
        CountDownLatch detailStarted = new CountDownLatch(1);
        CountDownLatch finishDetail = new CountDownLatch(1);
        when(hira.detail("getDgsbjtInfo2.8", "concurrent")).thenAnswer(invocation -> {
            detailStarted.countDown();
            assertTrue(finishDetail.await(10, TimeUnit.SECONDS));
            return List.of(Map.of("dgsbjtCd", "13", "dgsbjtCdNm", "이비인후과"));
        });
        when(hira.detail("getSpclDiagInfo2.8", "concurrent")).thenThrow(new HiraClient.UpstreamUnavailable());
        when(hira.detail("getSpclHospAsgFldList2.8", "concurrent")).thenReturn(List.of());
        when(hira.search("새 병원", "영통", "13")).thenReturn(List.of(
                Map.of("ykiho", "concurrent", "yadmNm", "새 병원명", "addr", "수원 영통 새 주소")));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var detail = executor.submit(() -> service.detail(id("concurrent")));
            assertTrue(detailStarted.await(5, TimeUnit.SECONDS));
            service.search("새 병원", "영통", "13");
            finishDetail.countDown();
            Map<String, Object> response = detail.get(10, TimeUnit.SECONDS);
            assertEquals("CACHE", response.get("source"));
            assertTrue(response.get("notice").toString().contains("이전 근거"));
            Document saved = get("concurrent");
            assertEquals("새 병원명", saved.getString("name"));
            assertEquals("수원 영통 새 주소", saved.getString("address"));
            assertNotNull(kind(saved, "BASIC"));
            assertNotNull(kind(saved, "DEPARTMENT"));
            assertTrue(saved.getList("evidence", Document.class).contains(special));
            assertEquals(new Date(0), kind(saved, "SPECIAL").getDate("fetchedAt"));
            assertFalse(saved.getBoolean("detailComplete"));
            for (Document embedded : saved.getList("evidence", Document.class)) {
                assertEquals(embedded, mongo.findById(embedded.get("_id"), Document.class, "hospitalEvidence"));
            }
            service.detail(id("concurrent"));
            verify(hira, times(2)).detail("getDgsbjtInfo2.8", "concurrent");
        } finally {
            finishDetail.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void successfulEmptyDetailsRemoveOldDesignationsAndStaleDepartmentMatches() {
        Document designation = fact("old-designation", id("changed"), "DESIGNATION", "이전 지정 분야");
        Document department = fact("old-department", id("changed"), "DEPARTMENT", "이비인후과");
        insert(hospital("changed", "변경의원", "수원 영통", "13", new Date())
                .append("departmentName", "이비인후과").append("evidence", List.of(designation, department)));
        mongo.insert(designation, "hospitalEvidence");
        mongo.insert(department, "hospitalEvidence");
        when(hira.detailsConfigured()).thenReturn(true);
        when(hira.detail("getDgsbjtInfo2.8", "changed")).thenReturn(List.of(Map.of("dgsbjtCd", "01", "dgsbjtCdNm", "내과")));
        when(hira.detail("getSpclDiagInfo2.8", "changed")).thenReturn(List.of());
        when(hira.detail("getSpclHospAsgFldList2.8", "changed")).thenReturn(List.of());
        when(hira.search("변경", "영통", "13")).thenReturn(List.of());

        assertEquals("LIVE", service.detail(id("changed")).get("source"));
        Document saved = get("changed");
        assertTrue(saved.getBoolean("detailComplete"));
        assertFalse(saved.containsKey("departmentCode"));
        assertFalse(saved.containsKey("departmentName"));
        assertEquals(List.of(new Document("code", "01").append("name", "내과")), saved.get("departments"));
        assertTrue(saved.getList("evidence", Document.class).stream().noneMatch(fact -> "DESIGNATION".equals(fact.getString("kind"))));
        assertNull(mongo.findById("old-designation", Document.class, "hospitalEvidence"));
        assertNull(mongo.findById("old-department", Document.class, "hospitalEvidence"));
        assertTrue(((List<?>) service.search("변경", "영통", "13").get("items")).isEmpty());
        verify(hira).search("변경", "영통", "13");
        assertEquals("CACHE", service.search("변경", "영통", "01").get("source"));
    }

    @Test
    void freshnessCutoffNeverCrossesTheKoreanCalendarDate() {
        assertEquals(Instant.parse("2026-11-20T15:00:00Z"),
                HospitalService.recentCacheCutoff(Instant.parse("2026-11-20T15:01:00Z")).toInstant());
        assertEquals(Instant.parse("2026-11-20T14:54:00Z"),
                HospitalService.recentCacheCutoff(Instant.parse("2026-11-20T14:59:00Z")).toInstant());
    }

    private Document hospital(String externalId, String name, String address, String department, Date basicTime) {
        return new Document("_id", id(externalId)).append("provider", "HIRA").append("externalId", externalId)
                .append("name", name).append("address", address).append("departmentCode", department)
                .append("source", "LIVE").append("basicFetchedAt", basicTime).append("fetchedAt", basicTime);
    }

    private Document fact(String key, String hospitalId, String kind, String value) {
        return new Document("_id", key).append("hospitalId", hospitalId).append("kind", kind).append("value", value)
                .append("sourceUrl", "https://example.test/source").append("basePeriod", null).append("fetchedAt", new Date(0));
    }

    private String id(String externalId) { return UUID.nameUUIDFromBytes(("HIRA:" + externalId).getBytes(StandardCharsets.UTF_8)).toString(); }
    private void insert(Document hospital) { mongo.insert(hospital, "hospitals"); }
    private Document get(String externalId) { return mongo.findById(id(externalId), Document.class, "hospitals"); }
    private Document kind(Document hospital, String kind) {
        return hospital.getList("evidence", Document.class).stream().filter(fact -> kind.equals(fact.getString("kind"))).findFirst().orElseThrow();
    }
}
