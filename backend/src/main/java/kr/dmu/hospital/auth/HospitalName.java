package kr.dmu.hospital.auth;

import java.text.Normalizer;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps the facility's spelling for display and compares its full name, including any branch. */
record HospitalName(String legalForm, String operator, String facility) {
    private static final String FORM = "학교법인|의료법인|재단법인|사회복지법인|사단법인";
    private static final Pattern LEADING_FORM = Pattern.compile(
            "^(?:\\((" + FORM + ")([^()]*)\\)|\\(([학의재복사])\\)|(" + FORM + ")(?:\\s*\\))?)\\s*", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern TRAILING_OPERATOR = Pattern.compile("\\((" + FORM + ")([^()]*)\\)$");
    private static final Pattern SCHOOL_OPERATOR = Pattern.compile("^[\\p{L}\\p{N}·\\s]+?(?:학원|교육재단)");
    private static final Pattern OTHER_OPERATOR = Pattern.compile("^[\\p{L}\\p{N}·\\s]+?(?:재단|선교회|협회)");
    private static final Pattern OPERATOR_SUFFIX = Pattern.compile("(?:사회복지재단|의료재단|교육재단|복지재단|장학재단|재단|학원|선교회|협회|의료원)$");
    private static final Pattern UNIVERSITY = Pattern.compile("[\\p{L}\\p{N}·]+대학교");
    private static final Pattern UNIVERSITY_FACILITY = Pattern.compile(
            "(?:의과대학|치과대학|한의과대학|의학전문대학원|치의학전문대학원|한의학전문대학원|직장부속|부속|부설).*?(?:병원|의원)(?:\\([^()]+\\))?");
    private static final Pattern SPACES = Pattern.compile("[\\p{javaWhitespace}\\p{Zs}]+");
    private static final Set<String> FACILITY_TYPES = Set.of("병원", "의원", "치과", "치과병원", "치과의원", "한의원",
            "한방병원", "종합병원", "상급종합병원", "요양병원", "정신병원", "보건소", "보건지소", "보건진료소");

    static HospitalName parse(String value) {
        String name = value == null ? "" : value.strip();
        String markers = name.replace('（', '(').replace('）', ')');
        Matcher prefix = LEADING_FORM.matcher(markers);
        if (!prefix.find()) {
            Matcher suffix = TRAILING_OPERATOR.matcher(markers);
            if (suffix.find() && suffix.start() > 0) {
                return new HospitalName(suffix.group(1), key(suffix.group(2)), name.substring(0, suffix.start()).strip());
            }
            return new HospitalName("", "", name);
        }

        String form = prefix.group(1);
        String owner = prefix.group(2);
        if (form == null) form = prefix.group(4) == null ? fullForm(prefix.group(3)) : prefix.group(4);
        String remainder = name.substring(prefix.end()).strip();
        if (remainder.isEmpty()) return new HospitalName("", "", name);
        if (owner != null && !owner.isBlank()) return new HospitalName(form, key(owner), remainder);

        Matcher boundary = (form.equals("학교법인") ? SCHOOL_OPERATOR : OTHER_OPERATOR).matcher(remainder);
        if (boundary.find()) {
            String facility = remainder.substring(boundary.end()).strip();
            if (distinctive(key(facility))) return new HospitalName(form, key(boundary.group()), facility);
        }
        return new HospitalName(form, "", remainder);
    }

    static boolean matches(String first, String second) {
        String rawName = key(first);
        if (rawName.isEmpty()) return false;
        if (rawName.equals(key(second))) return true;
        return parse(first).matches(parse(second));
    }

    static boolean identifiesOperator(String businessName, String catalogName) {
        if (businessName == null || catalogName == null) return false;
        // A standalone parenthesized legal name has no facility suffix for the normal display parser.
        String normalized = Normalizer.normalize(businessName.strip(), Normalizer.Form.NFKC);
        Matcher enclosed = TRAILING_OPERATOR.matcher(normalized);
        HospitalName source = parse(enclosed.matches() ? enclosed.group(1) + enclosed.group(2) : normalized);
        HospitalName catalog = parse(catalogName);
        if (!source.legalForm.isEmpty() && !catalog.legalForm.isEmpty() && !source.legalForm.equals(catalog.legalForm)) return false;
        if (!source.operator.isEmpty() && !catalog.operator.isEmpty() && !source.operator.equals(catalog.operator)) return false;

        String owner = key(source.facility);
        if (source.operator.isEmpty() && owner.equals(catalog.operator)) {
            Matcher suffix = OPERATOR_SUFFIX.matcher(owner);
            if (suffix.find() && suffix.start() > 0) return true;
        }

        if ((!source.legalForm.isEmpty() && !source.legalForm.equals("학교법인"))
                || (!catalog.legalForm.isEmpty() && !catalog.legalForm.equals("학교법인"))) return false;
        String university = owner;
        if (owner.endsWith("대학교의료원")) university = owner.substring(0, owner.length() - "의료원".length());
        else if (!source.legalForm.equals("학교법인")) return false;
        String clinic = key(catalog.facility);
        if (!UNIVERSITY.matcher(university).matches() || !clinic.startsWith(university)) return false;
        String affiliation = clinic.substring(university.length());
        return FACILITY_TYPES.contains(affiliation) || UNIVERSITY_FACILITY.matcher(affiliation).matches();
    }

    private boolean matches(HospitalName other) {
        if (!legalForm.isEmpty() && !other.legalForm.isEmpty() && !legalForm.equals(other.legalForm)) return false;
        if (!operator.isEmpty() && !other.operator.isEmpty() && !operator.equals(other.operator)) return false;
        String name = key(facility);
        String otherName = key(other.facility);
        if (name.equals(otherName) && !name.isEmpty()) {
            return distinctive(name) || (legalForm.equals(other.legalForm) && operator.equals(other.operator));
        }
        // One source may retain the owner's name while omitting its legal form.
        return (other.legalForm.isEmpty() && !operator.isEmpty() && (operator + name).equals(otherName))
                || (legalForm.isEmpty() && !other.operator.isEmpty() && (other.operator + otherName).equals(name));
    }

    private static String key(String text) {
        return SPACES.matcher(Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)).replaceAll("");
    }
    private static boolean distinctive(String name) { return !name.isEmpty() && !FACILITY_TYPES.contains(name); }
    private static String fullForm(String abbreviation) {
        return switch (abbreviation) {
            case "학" -> "학교법인";
            case "의" -> "의료법인";
            case "재" -> "재단법인";
            case "복" -> "사회복지법인";
            case "사" -> "사단법인";
            default -> throw new IllegalArgumentException(abbreviation);
        };
    }
}
