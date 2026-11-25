package kr.dmu.hospital.auth;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Writes only a disposable database on a loopback replica set. */
@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class HospitalAccountMongoIntegrationTest {
    private static final String NUMBER = "6911702710";
    private static final String PASSWORD = "HospitalTest2026!";
    private MongoClient client;
    private MongoTemplate mongo;
    private String database;
    private AuthService auth;
    private HospitalAccountService accounts;
    private ComwelClient comwel;
    @BeforeEach void setUp() {
        String uri = System.getenv("MONGODB_TEST_URI");
        assertTrue(uri.startsWith("mongodb://"));
        for (String host : new ConnectionString(uri).getHosts()) assertTrue(host.matches("(localhost|127\\.0\\.0\\.1|\\[::1\\])(:[0-9]+)?"));
        client = MongoClients.create(uri);
        database = "hospital_account_test_" + UUID.randomUUID().toString().replace("-", "");
        mongo = new MongoTemplate(client, database);
        new UserIndexes(mongo).run(new DefaultApplicationArguments(new String[0]));
        var passwords = new BCryptPasswordEncoder();
        auth = new AuthService(mongo, passwords, "hospital-account-local-test-secret-at-least-32-characters");
        comwel = mock(ComwelClient.class);
        when(comwel.lookup(NUMBER)).thenReturn(List.of(HospitalBusinessMatchTest.business(), HospitalBusinessMatchTest.business()));
        accounts = new HospitalAccountService(mongo, passwords, auth, comwel, new MongoTransactionManager(mongo.getMongoDatabaseFactory()));
        mongo.insert(HospitalBusinessMatchTest.hospital(), "hospitals");
    }
    @AfterEach void tearDown() {
        SecurityContextHolder.clearContext();
        if (mongo != null && database != null && database.startsWith("hospital_account_test_")) mongo.getDb().drop();
        if (client != null) client.close();
    }

    @Test void lookupThenSignupUsesOnlyServerBasicsWithoutEmailAndSupportsBusinessNumberLogin() {
        Map<String, Object> lookup = accounts.lookup(Map.of("businessNumber", "691-17-02710"));
        assertEquals(NUMBER, lookup.get("businessNumber"));
        assertEquals("", ((Document) lookup.get("hospital")).getString("tel"));
        Map<String, Object> body = registration(lookup);
        body.put("name", "변조 병원"); body.put("address", "변조 주소"); body.put("hospitalId", "other-hospital");
        body.put("email", "injected@example.com"); body.put("role", "ADMIN");
        Map<String, Object> session = accounts.register(body);
        UserPrincipal principal = auth.authenticate((String) session.get("token"));
        assertEquals("HOSPITAL", principal.getRole());
        assertEquals("test-hospital", principal.getHospitalId());
        assertEquals(NUMBER, principal.getBusinessNumber());
        assertEquals("풍동든든한365의원", principal.getName());
        Document stored = mongo.findById(principal.getId(), Document.class, "users");
        assertFalse(stored.containsKey("email")); assertFalse(stored.containsKey("memberIdentityClaimed"));
        assertEquals("", stored.getString("phone"));
        assertNotEquals(PASSWORD, stored.getString("passwordHash"));
        Document registered = mongo.findById("test-hospital", Document.class, HospitalAccountService.REGISTRATIONS);
        assertEquals(principal.getId(), registered.getString("userId"));
        assertEquals("ACTIVE", registered.getString("status"));
        assertEquals("10306", registered.get("basicInformation", Document.class).getString("postalCode"));
        assertEquals(0, mongo.getCollection(HospitalAccountService.VERIFICATIONS).countDocuments());
        var login = accounts.login(Map.of("businessNumber", "691-17-02710", "password", PASSWORD));
        assertEquals(principal.getId(), auth.authenticate((String) login.get("token")).getId());
        assertThrows(ApiException.class, () -> auth.login(Map.of("loginId", principal.getLoginId(), "password", PASSWORD)));
    }

    @Test void duplicateHospitalCannotBeClaimedWithSecondPreviouslyIssuedProof() {
        var first = accounts.lookup(Map.of("businessNumber", NUMBER));
        var second = accounts.lookup(Map.of("businessNumber", NUMBER));
        accounts.register(registration(first));
        assertEquals(409, assertThrows(ApiException.class, () -> accounts.register(registration(second))).getStatus());
        assertEquals(409, assertThrows(ApiException.class, () -> accounts.lookup(Map.of("businessNumber", NUMBER))).getStatus());
        assertEquals(1, mongo.getCollection("users").countDocuments());
        assertEquals(1, mongo.getCollection(HospitalAccountService.REGISTRATIONS).countDocuments());
    }

    @Test void expiredChangedAndForgedProofsCannotCreateAnAccount() {
        var lookup = accounts.lookup(Map.of("businessNumber", NUMBER));
        var forged = registration(lookup); forged.put("businessNumber", "0000000000");
        assertEquals(400, assertThrows(ApiException.class, () -> accounts.register(forged)).getStatus());
        mongo.updateFirst(new Query(), new Update().set("expiresAt", Date.from(Instant.now().minusSeconds(1))), HospitalAccountService.VERIFICATIONS);
        assertEquals(400, assertThrows(ApiException.class, () -> accounts.register(registration(lookup))).getStatus());
        var current = accounts.lookup(Map.of("businessNumber", NUMBER));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("test-hospital")), new Update().set("tel", "031-000-0000"), "hospitals");
        assertEquals(409, assertThrows(ApiException.class, () -> accounts.register(registration(current))).getStatus());
        assertEquals(0, mongo.getCollection("users").countDocuments());
        assertEquals(0, mongo.getCollection(HospitalAccountService.REGISTRATIONS).countDocuments());
    }

    @Test void noMatchAndAmbiguousCatalogNeverIssueVerification() {
        when(comwel.lookup(NUMBER)).thenReturn(List.of());
        assertEquals(404, assertThrows(ApiException.class, () -> accounts.lookup(Map.of("businessNumber", NUMBER))).getStatus());
        when(comwel.lookup(NUMBER)).thenReturn(List.of(HospitalBusinessMatchTest.business()));
        mongo.insert(HospitalBusinessMatchTest.hospital().append("_id", "duplicate-hospital"), "hospitals");
        assertEquals(409, assertThrows(ApiException.class, () -> accounts.lookup(Map.of("businessNumber", NUMBER))).getStatus());
        assertEquals(0, mongo.getCollection(HospitalAccountService.VERIFICATIONS).countDocuments());
    }

    @Test void buildingOnlyInsuranceAddressFindsTheNamedClinicAndReturnsItsCatalogAddress() {
        String number = "7849501821";
        String name = "풍동바른내과의원";
        String address = "경기도 고양시 일산동구 숲속마을1로 76, 힘찬프라자 6층 602-603호 (풍동)";
        var business = Map.of("saeopjaDrno", number, "saeopjangNm", name, "post", "10305",
                "addr", "경기도 고양시 일산동구 숲속마을1로 76 (풍동, 힘찬프라자)");
        when(comwel.lookup(number)).thenReturn(List.of(business, business));
        var catalog = new Document("_id", "bareun-clinic").append("active", true).append("name", name)
                .append("address", address).append("apiFields", new Document("postNo", "10305"));
        mongo.insert(catalog, "hospitals");
        mongo.insert(new Document(catalog).append("_id", "neighbor").append("name", "풍동연세이비인후과의원")
                .append("address", "경기도 고양시 일산동구 숲속마을1로 76, (풍동, 힘찬프라자 303호)"), "hospitals");

        var lookup = accounts.lookup(Map.of("businessNumber", "784-95-01821"));
        Document hospital = (Document) lookup.get("hospital");
        assertEquals("bareun-clinic", hospital.getString("id"));
        assertEquals(name, hospital.getString("name"));
        assertEquals(address, hospital.getString("address"));
        assertEquals("10305", hospital.getString("postalCode"));
    }

    @Test void missingSuiteCannotChooseBetweenTwoSameNameClinicsInTheBuilding() {
        var business = new HashMap<>(HospitalBusinessMatchTest.business());
        business.put("addr", "경기 고양시 일산동구 숲속마을로 48 4층");
        when(comwel.lookup(NUMBER)).thenReturn(List.of(business));
        mongo.insert(HospitalBusinessMatchTest.hospital().append("_id", "other-suite")
                .append("address", "경기 고양시 일산동구 숲속마을로 48 4층 402호"), "hospitals");

        var error = assertThrows(ApiException.class, () -> accounts.lookup(Map.of("businessNumber", NUMBER)));
        assertEquals(409, error.getStatus());
        assertEquals(0, mongo.getCollection(HospitalAccountService.VERIFICATIONS).countDocuments());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void buildingOnlyInsuranceRowCannotOverrideAConflictingSuite(boolean buildingOnlyFirst) {
        String address = "경기 고양시 일산동구 숲속마을로 48";
        Map<String, String> buildingOnly = new HashMap<>(HospitalBusinessMatchTest.business());
        buildingOnly.put("addr", address);
        Map<String, String> differentSuite = new HashMap<>(buildingOnly);
        differentSuite.put("addr", address + " 4층 402호");
        when(comwel.lookup(NUMBER)).thenReturn(buildingOnlyFirst
                ? List.of(buildingOnly, differentSuite) : List.of(differentSuite, buildingOnly));

        var error = assertThrows(ApiException.class, () -> accounts.lookup(Map.of("businessNumber", NUMBER)));
        assertTrue(error.getStatus() == 404 || error.getStatus() == 409);
        assertEquals(0, mongo.getCollection(HospitalAccountService.VERIFICATIONS).countDocuments());
    }

    @Test void lookupFindsCatalogHospitalWithItsFoundationNameInACrowdedPostcode() {
        String number = HospitalBusinessMatchTest.SEOUL_ST_MARY_NUMBER;
        var business = HospitalBusinessMatchTest.seoulStMaryBusiness();
        when(comwel.lookup(number)).thenReturn(List.of(business, business));
        for (int i = 0; i < 30; i++) {
            Document neighbor = HospitalBusinessMatchTest.seoulStMaryHospital()
                    .append("_id", "neighbor-" + i).append("name", "이웃의원" + i);
            mongo.insert(neighbor, "hospitals");
        }
        Document catalog = HospitalBusinessMatchTest.seoulStMaryHospital();
        mongo.insert(catalog, "hospitals");

        var result = accounts.lookup(Map.of("businessNumber", number));
        Document hospital = (Document) result.get("hospital");
        assertEquals(catalog.getString("_id"), hospital.getString("id"));
        assertEquals("가톨릭대학교서울성모병원", hospital.getString("name"));
        assertEquals("06591", hospital.getString("postalCode"));
    }

    @Test void lookupUsesCatalogSpacingAndBranchNameAndPreservesTheOriginalRecord() {
        String catalogName = "의료법인 샘물의료재단 새봄의원 동부분원";
        Map<String, String> business = new HashMap<>(HospitalBusinessMatchTest.business());
        business.put("saeopjangNm", "새봄의원동부분원");
        when(comwel.lookup(NUMBER)).thenReturn(List.of(business));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("test-hospital")),
                new Update().set("name", catalogName), "hospitals");

        var result = accounts.lookup(Map.of("businessNumber", NUMBER));
        Document hospital = (Document) result.get("hospital");
        assertEquals("새봄의원 동부분원", hospital.getString("name"));
        Document original = mongo.findById("test-hospital", Document.class, "hospitals");
        assertEquals(catalogName, original.getString("name"));
    }

    @Test void exactNameAndFoundationNameAtTheSameAddressAreAmbiguous() {
        String number = HospitalBusinessMatchTest.SEOUL_ST_MARY_NUMBER;
        var business = HospitalBusinessMatchTest.seoulStMaryBusiness();
        when(comwel.lookup(number)).thenReturn(List.of(business));
        mongo.insert(HospitalBusinessMatchTest.seoulStMaryHospital(), "hospitals");
        mongo.insert(HospitalBusinessMatchTest.seoulStMaryHospital().append("_id", "test-exact-name")
                .append("name", business.get("saeopjangNm")), "hospitals");

        ApiException error = assertThrows(ApiException.class, () -> accounts.lookup(Map.of("businessNumber", number)));
        assertEquals(409, error.getStatus());
        assertEquals(0, mongo.getCollection(HospitalAccountService.VERIFICATIONS).countDocuments());
    }

    @Test void hospitalPasswordChangesInvalidateOldTokensAndRevokedRegistrationInvalidatesNewToken() {
        var session = accounts.register(registration(accounts.lookup(Map.of("businessNumber", NUMBER))));
        String oldToken = (String) session.get("token");
        UserPrincipal principal = auth.authenticate(oldToken);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        String changedPassword = " DifferentHospital2026! ";
        var updated = auth.changePassword(Map.of("currentPassword", PASSWORD, "newPassword", changedPassword));
        assertThrows(JwtException.class, () -> auth.authenticate(oldToken));
        assertEquals(principal.getId(), auth.authenticate((String) updated.get("token")).getId());
        var signedIn = accounts.login(Map.of("businessNumber", NUMBER, "password", changedPassword));
        assertEquals(principal.getId(), auth.authenticate((String) signedIn.get("token")).getId());
        assertEquals(401, assertThrows(ApiException.class, () -> accounts.login(Map.of("businessNumber", NUMBER, "password", changedPassword.trim()))).getStatus());
        mongo.updateFirst(Query.query(Criteria.where("_id").is("test-hospital")), new Update().set("status", "INACTIVE"), HospitalAccountService.REGISTRATIONS);
        assertThrows(JwtException.class, () -> auth.authenticate((String) updated.get("token")));
        assertEquals(401, assertThrows(ApiException.class, () -> accounts.login(Map.of("businessNumber", NUMBER, "password", changedPassword))).getStatus());
    }

    @Test void hospitalPhoneDoesNotTakeOverPersonalDuplicateChecksOrStartupMigration() {
        mongo.updateFirst(Query.query(Criteria.where("_id").is("test-hospital")), new Update().set("tel", "0319030119"), "hospitals");
        accounts.register(registration(accounts.lookup(Map.of("businessNumber", NUMBER))));
        auth.register(Map.of("loginId", "hospital_" + NUMBER, "password", PASSWORD, "name", "개인", "email", "person@example.com", "phone", "0319030119"));
        new UserIndexes(mongo).run(new DefaultApplicationArguments(new String[0]));
        Document hospital = mongo.findOne(Query.query(Criteria.where("role").is("HOSPITAL")), Document.class, "users");
        assertFalse(hospital.containsKey("memberIdentityClaimed"));
        assertEquals("0319030119", hospital.getString("phone"));
        assertEquals(2, mongo.getCollection("users").countDocuments());
    }
    private Map<String, Object> registration(Map<String, Object> lookup) {
        return new HashMap<>(Map.of("businessNumber", NUMBER, "password", PASSWORD, "verificationToken", lookup.get("verificationToken")));
    }
}
