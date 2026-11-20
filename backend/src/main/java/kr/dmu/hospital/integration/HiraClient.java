package kr.dmu.hospital.integration;

import java.io.StringReader;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@Component
public class HiraClient {
    private static final String BASE = "https://apis.data.go.kr/B551182/";
    private final WebClient client;
    private final String hospitalKey;
    private final String detailKey;

    public HiraClient(@Value("${app.hira.hospital-key:}") String hospitalKey,
                      @Value("${app.hira.detail-key:}") String detailKey) {
        this.hospitalKey = decodeKey(hospitalKey);
        this.detailKey = decodeKey(detailKey);
        this.client = WebClient.builder().codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024)).build();
    }

    public boolean configured() { return !hospitalKey.isBlank(); }
    public boolean detailsConfigured() { return !detailKey.isBlank(); }

    public List<Map<String, String>> search(String name, String region, String department) {
        if (!configured()) throw new UpstreamUnavailable();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("serviceKey", hospitalKey);
        params.put("numOfRows", "30");
        params.put("pageNo", "1");
        if (!name.isBlank()) params.put("yadmNm", name);
        if (!department.isBlank()) params.put("dgsbjtCd", department);
        addRegion(params, region);
        return get("hospInfoServicev2/getHospBasisList", params);
    }

    public List<Map<String, String>> detail(String endpoint, String ykiho) {
        if (!detailsConfigured()) throw new UpstreamUnavailable();
        return get("MadmDtlInfoService2.8/" + endpoint,
                Map.of("serviceKey", detailKey, "ykiho", ykiho, "numOfRows", "100", "pageNo", "1"));
    }

    private List<Map<String, String>> get(String endpoint, Map<String, String> params) {
        try {
            StringBuilder url = new StringBuilder(BASE).append(endpoint).append('?');
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (url.charAt(url.length() - 1) != '?') url.append('&');
                url.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            }
            String response = client.get().uri(URI.create(url.toString())).retrieve()
                    .bodyToMono(String.class).timeout(Duration.ofSeconds(45)).block();
            return parseXml(response);
        } catch (Exception exception) {
            // Upstream exceptions often contain the authenticated URL. Never return or log them.
            throw new UpstreamUnavailable();
        }
    }

    static List<Map<String, String>> parseXml(String xml) {
        try {
            if (xml == null || xml.isBlank()) throw new UpstreamUnavailable();
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            org.w3c.dom.Document document = builder.parse(new InputSource(new StringReader(xml)));
            NodeList codes = document.getElementsByTagName("resultCode");
            if (codes.getLength() > 0) {
                String code = codes.item(0).getTextContent().trim();
                if (!code.equals("00") && !code.equals("0") && !code.equals("INFO-000")) throw new UpstreamUnavailable();
            }
            if (document.getElementsByTagName("cmmMsgHeader").getLength() > 0) throw new UpstreamUnavailable();
            List<Map<String, String>> result = new ArrayList<>();
            NodeList items = document.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Map<String, String> row = new LinkedHashMap<>();
                NodeList fields = items.item(i).getChildNodes();
                for (int j = 0; j < fields.getLength(); j++) {
                    if (fields.item(j) instanceof Element field) row.put(field.getTagName(), field.getTextContent().trim());
                }
                result.add(row);
            }
            return result;
        } catch (Exception exception) {
            throw new UpstreamUnavailable();
        }
    }

    static void addRegion(Map<String, String> params, String raw) {
        String region = raw.trim();
        String[][] provinces = {{"서울", "110000"}, {"부산", "210000"}, {"대구", "230000"},
                {"인천", "220000"}, {"광주", "240000"}, {"대전", "250000"}, {"울산", "260000"},
                {"경기", "310000"}, {"강원", "320000"}, {"충북", "330000"}, {"충남", "340000"},
                {"전북", "350000"}, {"전남", "360000"}, {"경북", "370000"}, {"경남", "380000"},
                {"제주", "390000"}, {"세종", "410000"}};
        for (String[] province : provinces) {
            if (region.startsWith(province[0])) {
                params.put("sidoCd", province[1]);
                region = region.replaceFirst("^" + province[0] + "(?:특별자치도|특별자치시|특별시|광역시|도)?\\s*", "");
                break;
            }
        }
        if (!region.isBlank()) {
            String[] tokens = region.split("\\s+");
            params.put("emdongNm", tokens[tokens.length - 1]);
        }
    }

    private static String decodeKey(String key) {
        if (key == null) return "";
        return key.contains("%") ? URLDecoder.decode(key.trim(), StandardCharsets.UTF_8) : key.trim();
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    public static class UpstreamUnavailable extends RuntimeException {
        public UpstreamUnavailable() { super("외부 병원 정보를 일시적으로 조회할 수 없습니다."); }
    }
}
