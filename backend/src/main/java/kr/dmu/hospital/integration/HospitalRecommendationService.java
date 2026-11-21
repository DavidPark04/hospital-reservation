package kr.dmu.hospital.integration;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/** Public hospital facts only. Neither the conversation nor the user's coordinates are persisted. */
@Service
public class HospitalRecommendationService {
    private final MongoTemplate mongo;
    private final HospitalExpertiseSearchService expertise;

    public HospitalRecommendationService(MongoTemplate mongo, HospitalExpertiseSearchService expertise) {
        this.mongo = mongo;
        this.expertise = expertise;
    }

    public Map<String, Object> recommend(String departmentCode, String specialty, String region,
            String preference, boolean promptCare, Double latitude, Double longitude) {
        validateLocation(latitude, longitude);
        if ("SPECIALIST".equals(preference)) return specialists(departmentCode, specialty, region, latitude, longitude);
        return nearby(departmentCode, region, promptCare, latitude, longitude);
    }

    private Map<String, Object> nearby(String department, String region, boolean promptCare, Double lat, Double lon) {
        if (!Departments.contains(department)) return result(List.of(), "CACHE", "진료과를 먼저 확인해 주세요. 직접 병원 검색도 이용할 수 있어요.");
        if ((region == null || region.isBlank()) && lat == null) return result(List.of(), "CACHE", "병원을 찾을 지역이나 기준 위치가 필요해요.");
        Document match = new Document("provider", "HIRA").append("active", true).append("departmentCodes", department);
        List<Document> conditions = regionConditions(region);
        // Primary outpatient services. Unknown types, nursing facilities and tertiary hospitals are not called nearby first-care options.
        List<String> types = switch (department) {
            case "49" -> List.of("치과의원", "치과병원");
            case "80" -> List.of("한의원", "한방병원");
            case "03" -> List.of("의원", "병원", "정신병원", "종합병원");
            default -> List.of("의원", "병원", "종합병원");
        };
        match.append("apiFields.clCdNm", new Document("$in", types));
        List<Document> pipeline = new ArrayList<>();
        if (lat != null) {
            // Bound the distance calculation to approximately 30 km around the explicitly supplied point.
            conditions.add(new Document("latitude", new Document("$type", "number").append("$gte", lat - .3).append("$lte", lat + .3)));
            conditions.add(new Document("longitude", new Document("$type", "number").append("$gte", lon - .4).append("$lte", lon + .4)));
        }
        if (!conditions.isEmpty()) match.append("$and", conditions);
        pipeline.add(new Document("$match", match));
        if (lat != null) {
            double longitudeScale = Math.cos(Math.toRadians(lat));
            Document dy = new Document("$subtract", List.of("$latitude", lat));
            Document dx = new Document("$multiply", List.of(new Document("$subtract", List.of("$longitude", lon)), longitudeScale));
            pipeline.add(new Document("$set", new Document("_distanceScore", new Document("$add", List.of(
                    new Document("$multiply", List.of(dy, dy)), new Document("$multiply", List.of(dx, dx)))))));
            pipeline.add(new Document("$sort", new Document("_distanceScore", 1).append("name", 1).append("_id", 1)));
            pipeline.add(new Document("$limit", 20));
        } else {
            pipeline.add(new Document("$set", new Document("_typeOrder", new Document("$cond", List.of(
                    new Document("$in", List.of("$apiFields.clCdNm", List.of("의원", "치과의원", "한의원"))), 0, 1)))));
            pipeline.add(new Document("$sort", new Document("_typeOrder", 1).append("name", 1).append("_id", 1)));
            pipeline.add(new Document("$limit", 3));
        }
        List<Document> rows = mongo.getCollection(HospitalCatalog.COLLECTION).aggregate(pipeline)
                .maxTime(8, TimeUnit.SECONDS).into(new ArrayList<>());
        if (lat != null) rows.sort(Comparator.comparingDouble(row -> distance(lat, lon, number(row.get("latitude")), number(row.get("longitude")))));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Document row : rows) {
            if (items.size() == 3) break;
            if (lat != null && distance(lat, lon, number(row.get("latitude")), number(row.get("longitude"))) > 30) continue;
            Map<String, Object> item = publicCard(row, department, lat, lon);
            item.put("recommendationReason", Departments.name(department) + " 진료과가 등록된 " + hospitalType(row)
                    + (lat == null ? "으로, 말씀하신 지역에서 찾았어요." : "으로, 제공한 위치와의 직선거리를 고려했어요."));
            items.add(item);
        }
        String notice = lat == null ? "희망 지역과 진료과 기준으로 찾았어요. 위치를 제공하면 직선거리 순으로 비교할 수 있어요."
                : "제공한 위치 주변 약 30km 범위에서 직선거리를 비교했어요. 실제 이동 거리·시간과는 달라요.";
        notice += " 실제 진료시간과 접수 가능 여부는 병원에 확인해 주세요.";
        if (promptCare) notice = "증상이 지속되거나 악화됐다면 병원 규모보다 진료를 미루지 않는 것이 중요해요. " + notice;
        return result(items, "CACHE", items.isEmpty() ? "조건에 맞는 병·의원을 찾지 못했어요. 지역을 넓히거나 직접 검색해 주세요." : notice);
    }

    private Map<String, Object> specialists(String department, String specialty, String region, Double lat, Double lon) {
        String topic = specialty == null || specialty.isBlank() ? Departments.name(department) : specialty;
        Map<String, Object> search = expertise.search(topic, region == null ? "" : region);
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (search.get("candidates") instanceof List<?> candidates) for (Object value : candidates) {
            if (!(value instanceof Map<?, ?> candidate)) continue;
            String name = text(candidate.get("name"), 120);
            String address = text(candidate.get("address"), 200);
            if (name.isBlank() || address.isBlank()) continue;
            Document hospital = matchHospital(name, address);
            if (hospital == null || ids.contains(hospital.getString("_id")) || !regionMatches(region, hospital.getString("address"))) continue;
            List<Map<String, String>> sources = recommendationSources(candidate.get("sources"), hospital);
            String reason = text(candidate.get("reason"), 500);
            if (sources.isEmpty() || reason.isBlank()) continue;
            Map<String, Object> item = publicCard(hospital, department, lat, lon);
            item.put("specialty", topic);
            item.put("recommendationReason", reason);
            item.put("recommendationSources", sources);
            items.add(item);
            ids.add(hospital.getString("_id"));
            if (items.size() == 3) break;
        }
        if (items.isEmpty() && search.get("candidates") instanceof List<?> candidates && !candidates.isEmpty())
            expertise.invalidate(topic, region == null ? "" : region);
        String notice = items.isEmpty() ? "참고 자료와 병원명·주소가 연결되는 전문 진료 후보를 찾지 못했어요. 검색 범위를 넓히거나 주변 진료과 병원을 찾아볼 수 있어요."
                : "공식 안내·기사·공개 후기 등의 검색 결과를 AI가 요약한 참고 후보예요. 출처 유형을 함께 확인해 주세요. 후기나 언급은 치료 성적·병원 간 우열을 뜻하지 않으며, 실제 진료 가능 여부와 접수 절차는 병원에 확인해 주세요.";
        if (search.get("notice") instanceof String message && !message.isBlank()) notice = message + " " + notice;
        return result(items, String.valueOf(search.getOrDefault("source", "WEB")), notice);
    }

    private Document matchHospital(String name, String address) {
        List<Document> alternatives = new ArrayList<>();
        for (String alias : aliases(name)) alternatives.add(new Document("name", new Document("$regex", spacedPattern(alias)).append("$options", "i")));
        Document filter = new Document("provider", "HIRA").append("active", true).append("$or", alternatives);
        List<Document> found = mongo.getCollection(HospitalCatalog.COLLECTION).find(filter).limit(21)
                .maxTime(5, TimeUnit.SECONDS).into(new ArrayList<>());
        if (found.size() > 20) return null;
        List<Document> matching = found.stream().filter(row -> addressesMatch(address, row.getString("address"))).toList();
        // Never guess between a main hospital and a branch, even when they share a web domain.
        if (matching.size() != 1) return null;
        return matching.getFirst();
    }

    static List<String> aliases(String name) {
        Set<String> names = new java.util.LinkedHashSet<>();
        names.add(name.trim());
        if (name.contains("서울대병원")) names.add(name.replace("서울대병원", "서울대학교병원"));
        if (name.equals("연세세브란스병원") || name.equals("연세 세브란스병원")) names.add("세브란스병원");
        if (name.equals("가톨릭대 서울성모병원") || name.equals("가톨릭대학교 서울성모병원")) names.add("서울성모병원");
        return List.copyOf(names);
    }

    private static String spacedPattern(String name) {
        StringBuilder result = new StringBuilder();
        for (char ch : name.replaceAll("\\s+", "").toCharArray()) result.append(Pattern.quote(String.valueOf(ch))).append("\\s*");
        return result.toString();
    }

    static boolean addressesMatch(String candidate, String stored) {
        if (candidate == null || stored == null || candidate.isBlank() || stored.isBlank()) return false;
        String a = normalizeAddress(candidate), b = normalizeAddress(stored);
        // Require a road/lot number, not just the same city. This also disambiguates hospital branches.
        if (!a.matches(".*[0-9].*") || a.length() < 10) return false;
        return a.equals(b);
    }

    private static String normalizeAddress(String value) {
        // HIRA appends building/floor labels after a comma. Compare the complete street and building number,
        // then require a unique institution-name match; never compare a numeric address prefix.
        String street = value.replaceAll("\\([^)]*\\)", "").split(",", 2)[0];
        return street.replace("서울특별시", "서울").replace("서울시", "서울").replace("특별자치도", "").replace("특별자치시", "")
                .replace("광역시", "").replace("경기도", "경기").replaceAll("\\([^)]*\\)", "")
                .replaceAll("[\\s,]", "").trim();
    }
    static List<Map<String, String>> recommendationSources(Object raw, Document hospital) {
        List<Map<String, String>> sources = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return sources;
        String ownHost = host(hospital.get("apiFields", new Document()).getString("hospUrl"));
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> source)) continue;
            String url = text(source.get("url"), 1800), domain = host(url);
            if (domain.isBlank() || HospitalExpertiseSearchService.publicUrl(url) == null) continue;
            if (!ownHost.isBlank() && domain.contains(ownHost + ".") && !sameDomain(domain, ownHost)) continue;
            boolean official = sameDomain(domain, ownHost) || sameDomain(domain, "hira.or.kr")
                    || sameDomain(domain, "nhis.or.kr") || sameDomain(domain, "mohw.go.kr")
                    || sameDomain(domain, "ncc.re.kr") || sameDomain(domain, "cancer.go.kr");
            // A national cancer centre's own page must not be used as another hospital's institutional proof.
            if ((sameDomain(domain, "ncc.re.kr") || sameDomain(domain, "cancer.go.kr")) && !sameDomain(domain, ownHost))
                official = text(source.get("title"), 300).contains(hospital.getString("name"));
            String kind = official ? "OFFICIAL" : sourceKind(domain);
            String label = switch (kind) {
                case "OFFICIAL" -> "병원·공공자료";
                case "NEWS" -> "기사·보도";
                case "EXPERIENCE" -> "후기·SNS";
                default -> "웹 참고";
            };
            sources.add(Map.of("url", url, "title", text(source.get("title"), 300), "checkedAt", text(source.get("checkedAt"), 40),
                    "kind", kind, "label", label));
            if (sources.size() == 3) break;
        }
        return sources;
    }

    private static String sourceKind(String domain) {
        for (String value : List.of("blog.naver.com", "cafe.naver.com", "tistory.com", "blogspot.com", "wordpress.com",
                "instagram.com", "facebook.com", "youtube.com", "youtu.be", "x.com", "twitter.com", "threads.net", "reddit.com"))
            if (sameDomain(domain, value)) return "EXPERIENCE";
        for (String value : List.of("news.naver.com", "n.news.naver.com", "v.daum.net", "yna.co.kr", "yonhapnews.co.kr",
                "khan.co.kr", "hani.co.kr", "chosun.com", "donga.com", "joongang.co.kr", "hankookilbo.com", "sbs.co.kr", "kbs.co.kr",
                "imbc.com", "medicaltimes.com", "medigatenews.com", "dailymedi.com", "docdocdoc.co.kr", "newsis.com", "news1.kr"))
            if (sameDomain(domain, value)) return "NEWS";
        return "WEB";
    }

    static String host(String raw) {
        try {
            if (raw == null || raw.isBlank()) return "";
            URI uri = URI.create(raw.trim());
            if (!List.of("https", "http").contains(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getHost() == null
                    || (uri.getPort() != -1 && uri.getPort() != 443 && uri.getPort() != 80)) return "";
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT).replaceFirst("^www\\.", "");
            if (host.equals("localhost") || !host.contains(".") || host.matches("[0-9.]+") || host.contains(":")) return "";
            return host;
        } catch (IllegalArgumentException invalid) { return ""; }
    }
    private static boolean sameDomain(String source, String expected) {
        return !expected.isBlank() && (source.equals(expected) || source.endsWith("." + expected));
    }

    private Map<String, Object> publicCard(Document hospital, String department, Double lat, Double lon) {
        Map<String, Object> item = HospitalService.publicHospital(HospitalCatalog.combine(hospital, null), "CACHE");
        item.put("hospitalType", hospitalType(hospital));
        if (Departments.contains(department) && hospital.get("departmentCodes") instanceof List<?> codes && codes.contains(department)) {
            item.put("departmentCode", department);
            item.put("departmentName", Departments.name(department));
        } else {
            item.remove("departmentCode");
            item.remove("departmentName");
        }
        Double hospitalLat = number(hospital.get("latitude")), hospitalLon = number(hospital.get("longitude"));
        if (lat != null && hospitalLat != null && hospitalLon != null)
            item.put("distanceKm", Math.round(distance(lat, lon, hospitalLat, hospitalLon) * 10.0) / 10.0);
        return item;
    }

    private static String hospitalType(Document hospital) { return hospital.get("apiFields", new Document()).getString("clCdNm") == null ? "의료기관" : hospital.get("apiFields", new Document()).getString("clCdNm"); }
    private static Double number(Object raw) { return raw instanceof Number value && Double.isFinite(value.doubleValue()) ? value.doubleValue() : null; }
    static void validateLocation(Double lat, Double lon) {
        if (lat == null && lon == null) return;
        if (lat == null || lon == null || !Double.isFinite(lat) || !Double.isFinite(lon) || lat < 32 || lat > 40 || lon < 123 || lon > 133)
            throw new ApiException(400, "국내 병원을 찾을 수 있는 올바른 위치를 입력해 주세요.");
    }
    static double distance(Double lat, Double lon, Double targetLat, Double targetLon) {
        if (lat == null || lon == null || targetLat == null || targetLon == null) return Double.POSITIVE_INFINITY;
        double dy = Math.toRadians(targetLat - lat), dx = Math.toRadians(targetLon - lon);
        double a = Math.pow(Math.sin(dy / 2), 2) + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(targetLat)) * Math.pow(Math.sin(dx / 2), 2);
        return 6371.0088 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
    }
    private static List<Document> regionConditions(String region) {
        List<Document> result = new ArrayList<>();
        if (region == null || region.isBlank() || region.equals("전국")) return result;
        if (region.equals("수도권")) {
            result.add(new Document("address", new Document("$regex", "^(서울|경기|인천)")));
            return result;
        }
        for (String token : region.trim().split("\\s+")) {
            String shortName = switch (token) {
                case "서울특별시" -> "서울"; case "경기도" -> "경기"; case "부산광역시" -> "부산";
                case "대구광역시" -> "대구"; case "인천광역시" -> "인천"; case "광주광역시" -> "광주";
                case "대전광역시" -> "대전"; case "울산광역시" -> "울산"; default -> token;
            };
            boolean province = !shortName.equals(token) || List.of("서울", "부산", "대구", "인천", "대전", "울산", "세종",
                    "강원특별자치도", "전북특별자치도", "제주특별자치도", "충청북도", "충청남도", "전라남도", "경상북도", "경상남도").contains(token);
            String regex = (province ? "^" : "") + Pattern.quote(shortName);
            result.add(new Document("address", new Document("$regex", regex)));
        }
        return result;
    }
    static boolean regionMatches(String region, String address) {
        if (region == null || region.isBlank() || region.equals("전국")) return true;
        if (address == null) return false;
        if (region.equals("수도권")) return address.startsWith("서울") || address.startsWith("경기") || address.startsWith("인천");
        for (Document condition : regionConditions(region)) {
            String regex = condition.get("address", Document.class).getString("$regex");
            if (!Pattern.compile(regex).matcher(address).find()) return false;
        }
        return true;
    }
    private static String text(Object raw, int length) { return raw instanceof String s && s.length() <= length ? s.trim() : ""; }
    private static Map<String, Object> result(List<Map<String, Object>> items, String source, String notice) {
        return new LinkedHashMap<>(Map.of("items", items, "source", source, "notice", notice));
    }
}
