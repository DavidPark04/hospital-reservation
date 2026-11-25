package kr.dmu.hospital.auth;

import java.util.HashMap;
import java.util.Map;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class HospitalBusinessMatchTest {
    private static final String NUMBER = "6911702710";
    static final String SEOUL_ST_MARY_NUMBER = "1148200213";
    private static final String SEOUL_ST_MARY_NAME = "가톨릭대학교서울성모병원";
    static Document hospital() {
        return new Document("_id", "test-hospital").append("name", "풍동든든한365의원").append("active", true)
                .append("address", "경기도 고양시 일산동구 숲속마을로 48, 신성프라자 4층 401호 (풍동)")
                .append("tel", "").append("apiFields", new Document("postNo", "10306"));
    }
    static Map<String, String> business() {
        return Map.of("saeopjaDrno", NUMBER, "saeopjangNm", "풍동든든한 365의원", "post", "10306",
                "addr", "경기 고양시 일산동구 숲속마을로 48 4 층 401호(풍동, 신성프라자)");
    }
    static Document seoulStMaryHospital() {
        return new Document("_id", "test-seoul-st-mary").append("name", "학교법인가톨릭학원" + SEOUL_ST_MARY_NAME)
                .append("active", true).append("address", "서울특별시 서초구 반포대로 222, (반포동)")
                .append("apiFields", new Document("postNo", "06591"));
    }
    static Map<String, String> seoulStMaryBusiness() {
        return Map.of("saeopjaDrno", SEOUL_ST_MARY_NUMBER, "saeopjangNm", SEOUL_ST_MARY_NAME,
                "post", "06591", "addr", "서울 서초구 반포대로 222 (반포동)");
    }

    @Test void acceptsVerifiedExampleDespiteProvinceWhitespaceAndBuildingLabelOrder() {
        assertTrue(HospitalBusinessMatch.matches(business(), hospital(), NUMBER));
    }
    @Test void matchesVerifiedFoundationNameOnlyAtTheSameAddressAndPostcode() {
        var business = seoulStMaryBusiness();
        assertTrue(HospitalBusinessMatch.matches(business, seoulStMaryHospital(), SEOUL_ST_MARY_NUMBER));
        assertFalse(HospitalBusinessMatch.matches(business,
                seoulStMaryHospital().append("address", "서울 서초구 반포대로 223"), SEOUL_ST_MARY_NUMBER));
        assertFalse(HospitalBusinessMatch.matches(business,
                seoulStMaryHospital().append("apiFields", new Document("postNo", "06590")), SEOUL_ST_MARY_NUMBER));
    }
    @Test void partialBranchAndConflictingFoundationNamesNeverMatchAtTheSameAddress() {
        for (String name : new String[] {"서울성모병원", SEOUL_ST_MARY_NAME + "분원", "학교법인다른학원" + SEOUL_ST_MARY_NAME}) {
            Map<String, String> business = new HashMap<>(seoulStMaryBusiness());
            business.put("saeopjangNm", name);
            assertFalse(HospitalBusinessMatch.matches(business, seoulStMaryHospital(), SEOUL_ST_MARY_NUMBER), name);
        }
        assertFalse(HospitalBusinessMatch.matches(seoulStMaryBusiness(),
                seoulStMaryHospital().append("name", "새" + SEOUL_ST_MARY_NAME), SEOUL_ST_MARY_NUMBER));
    }
    @ParameterizedTest
    @CsvSource({
            "가톨릭대학교대전성모병원, 학교법인 가톨릭학원 가톨릭대학교대전성모병원",
            "(의) 샘물의료재단 솔빛의원, 솔빛의원",
            "샘물아동병원, 재단법인 새봄재단 샘물아동병원",
            "솔빛의원, (의료법인 샘물) 솔빛의원",
            "솔빛의원, 솔빛의원(의료법인 샘물)",
            "의료법인 강릉동인병원, 강릉동인병원",
            "동의병원, 학교법인)동의병원",
            "누가한의원, 사단법인 )누가 선교회 누가한의원",
            "(의) 샘물의료재단 솔빛의원, 의료법인 샘물의료재단 솔빛의원"
    })
    void recognizesDocumentedCorporateFormsAndKeepsTheFullFacilityName(String businessName, String catalogName) {
        assertTrue(matchesNames(businessName, catalogName));
        assertTrue(matchesNames(catalogName, businessName));
    }
    @ParameterizedTest
    @CsvSource({
            "의료법인 샘물재단 솔빛병원, 재단법인 샘물재단 솔빛병원",
            "(의) 샘물의료재단 솔빛병원, 의료법인 솔빛의료재단 솔빛병원",
            "병원, 의료법인 샘물의료재단 병원",
            "성모병원, 학교법인 가톨릭학원 가톨릭대학교대전성모병원",
            "솔빛병원, 의료법인 샘물의료재단 솔빛병원제2분원"
    })
    void rejectsConflictingOperatorsGenericFacilityTypesAndPartialNames(String businessName, String catalogName) {
        assertFalse(matchesNames(businessName, catalogName));
        assertFalse(matchesNames(catalogName, businessName));
    }
    private static boolean matchesNames(String businessName, String catalogName) {
        Map<String, String> row = new HashMap<>(business());
        row.put("saeopjangNm", businessName);
        return HospitalBusinessMatch.matches(row, hospital().append("name", catalogName), NUMBER);
    }
    @Test void displaysCatalogSpellingEvenWhenUnicodeIsNormalizedForComparison() {
        String catalog = "（의）샘물의료재단 솔빛Ⅲ의원 (제2분원)";
        assertTrue(matchesNames("솔빛III의원(제2분원)", catalog));
        assertEquals("솔빛Ⅲ의원 (제2분원)", HospitalName.parse(catalog).facility());
    }
    @Test void rejectsSameNameAtDifferentAddressFloorUnitOrPostalCode() {
        for (String address : new String[] {
                "경기 고양시 일산서구 숲속마을로 48 4층 401호", "경기 고양시 일산동구 숲속마을로 480 4층 401호",
                "경기 고양시 일산동구 숲속마을로 48 3층 401호", "경기 고양시 일산동구 숲속마을로 48 4층 402호",
                "경기 고양시 일산동구 숲속마을로 48", "경기 고양시 일산동구 숲속마을로 48 지하4층 401호" }) {
            Map<String, String> row = new HashMap<>(business()); row.put("addr", address);
            assertFalse(HospitalBusinessMatch.matches(row, hospital(), NUMBER), address);
        }
        Map<String, String> row = new HashMap<>(business()); row.put("post", "10307");
        assertFalse(HospitalBusinessMatch.matches(row, hospital(), NUMBER));
    }
    @Test void rejectsWrongBusinessNumberInactiveOrDifferentlyNamedHospital() {
        assertFalse(HospitalBusinessMatch.matches(business(), hospital(), "0000000000"));
        assertFalse(HospitalBusinessMatch.matches(business(), hospital().append("active", false), NUMBER));
        assertFalse(HospitalBusinessMatch.matches(business(), hospital().append("name", "다른의원"), NUMBER));
    }
    @Test void parserRequiresSuccessAndRejectsExternalEntities() {
        assertEquals(0, ComwelClient.parseXml("<response><header><resultCode>00</resultCode></header><body><totalCount>0</totalCount><items/></body></response>").size());
        assertThrows(ApiException.class, () -> ComwelClient.parseXml("<response><resultCode>30</resultCode></response>"));
        assertThrows(ApiException.class, () -> ComwelClient.parseXml("<html>gateway unavailable</html>"));
        assertThrows(ApiException.class, () -> ComwelClient.parseXml("<!DOCTYPE data [<!ENTITY secret SYSTEM 'file:///C:/Windows/win.ini'>]><response><resultCode>00</resultCode><item><addr>&secret;</addr></item></response>"));
    }
    @Test void parserReadsOnlyFieldsAndRejectsTruncatedPagination() {
        var rows = ComwelClient.parseXml("<response><resultCode>00</resultCode><totalCount>1</totalCount><items><item><saeopjaDrno>6911702710</saeopjaDrno><saeopjangNm>풍동든든한365의원</saeopjangNm><addr>경기 고양시</addr></item></items></response>");
        assertEquals(NUMBER, rows.get(0).get("saeopjaDrno"));
        assertThrows(ApiException.class, () -> ComwelClient.parseXml("<response><resultCode>00</resultCode><totalCount>101</totalCount></response>"));
    }
}
