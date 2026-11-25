package kr.dmu.hospital.auth;

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
import kr.dmu.hospital.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@Component
public class ComwelClient {
    private static final String ENDPOINT = "https://apis.data.go.kr/B490001/gySjbPstateInfoService/getGySjBoheomBsshItem";
    private final String key;
    private final WebClient client;

    public ComwelClient(@Value("${app.comwel.api-key:}") String key) {
        this.key = key == null ? "" : (key.contains("%") ? URLDecoder.decode(key.trim(), StandardCharsets.UTF_8) : key.trim());
        client = WebClient.builder().codecs(config -> config.defaultCodecs().maxInMemorySize(512 * 1024)).build();
    }

    public List<Map<String, String>> lookup(String businessNumber) {
        if (key.isBlank()) throw new ApiException(503, "사업자번호 조회 설정을 확인해 주세요.");
        List<Map<String, String>> result = new ArrayList<>();
        for (String insurance : List.of("1", "2")) {
            try {
                String url = ENDPOINT + "?serviceKey=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                        + "&v_saeopjaDrno=" + businessNumber + "&opaBoheomFg=" + insurance + "&pageNo=1&numOfRows=100";
                String xml = client.get().uri(URI.create(url)).retrieve().bodyToMono(String.class)
                        .timeout(Duration.ofSeconds(15)).block();
                result.addAll(parseXml(xml));
            } catch (Exception error) {
                // Authenticated upstream URLs must never appear in logs or responses.
                throw unavailable();
            }
        }
        return result;
    }

    static List<Map<String, String>> parseXml(String xml) {
        try {
            if (xml == null || xml.isBlank()) throw unavailable();
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            var document = builder.parse(new InputSource(new StringReader(xml)));
            NodeList codes = document.getElementsByTagName("resultCode");
            if (codes.getLength() != 1 || !List.of("00", "0").contains(codes.item(0).getTextContent().trim())
                    || document.getElementsByTagName("cmmMsgHeader").getLength() > 0) throw unavailable();
            NodeList totals = document.getElementsByTagName("totalCount");
            if (totals.getLength() > 0 && Integer.parseInt(totals.item(0).getTextContent().trim()) > 100) throw unavailable();
            List<Map<String, String>> result = new ArrayList<>();
            NodeList items = document.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Map<String, String> item = new LinkedHashMap<>();
                NodeList fields = items.item(i).getChildNodes();
                for (int j = 0; j < fields.getLength(); j++) {
                    if (fields.item(j) instanceof Element field) item.put(field.getTagName(), field.getTextContent().trim());
                }
                result.add(item);
            }
            return result;
        } catch (Exception error) { throw unavailable(); }
    }

    private static ApiException unavailable() { return new ApiException(503, "사업자 정보를 조회하지 못했어요. 잠시 후 다시 시도해 주세요."); }
}
