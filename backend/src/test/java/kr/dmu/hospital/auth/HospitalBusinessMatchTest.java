package kr.dmu.hospital.auth;

import java.util.HashMap;
import java.util.Map;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HospitalBusinessMatchTest {
    private static final String NUMBER = "6911702710";
    static Document hospital() {
        return new Document("_id", "test-hospital").append("name", "풍동든든한365의원").append("active", true)
                .append("address", "경기도 고양시 일산동구 숲속마을로 48, 신성프라자 4층 401호 (풍동)")
                .append("tel", "").append("apiFields", new Document("postNo", "10306"));
    }
    static Map<String, String> business() {
        return Map.of("saeopjaDrno", NUMBER, "saeopjangNm", "풍동든든한 365의원", "post", "10306",
                "addr", "경기 고양시 일산동구 숲속마을로 48 4 층 401호(풍동, 신성프라자)");
    }

    @Test void acceptsVerifiedExampleDespiteProvinceWhitespaceAndBuildingLabelOrder() {
        assertTrue(HospitalBusinessMatch.matches(business(), hospital(), NUMBER));
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
