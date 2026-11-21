package kr.dmu.hospital.integration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative routing guard, not a diagnostic or comprehensive triage classifier. */
final class EmergencyScreen {
    enum Status { NONE, PRESENT, UNCERTAIN }
    private static final Map<String, Pattern> SIGNS = new LinkedHashMap<>();
    private static final Pattern NEGATED = Pattern.compile("^(?:은|는|이|가|도|을|를|증상은|증상이|증상도|건|것은|는건|는것은|지는|지|던건|던것은|겠는건|전혀|아예|아직|현재|지금|모두|둘다|다|있지는|있지|있는건|있는것은)*(?:없|아니|않)");
    static {
        SIGNS.put("breathing", Pattern.compile("숨을못쉬|숨이안쉬|숨이막혀|호흡곤란|호흡이어려|숨쉬기어려|숨쉬기가어려"));
        SIGNS.put("consciousness", Pattern.compile("의식이없|의식을잃|의식잃|의식변화"));
        SIGNS.put("neurologic", Pattern.compile("갑자기말이어눌|갑자기말이안나|갑자기말을못|갑자기발음이어눌|한쪽마비|한쪽팔다리마비|한쪽팔다리에힘이없|한쪽팔에힘이없"));
        SIGNS.put("chest", Pattern.compile("심한가슴통증|극심한흉통|가슴이쥐어짜듯"));
        SIGNS.put("bleeding", Pattern.compile("피가멈추지않|출혈이멈추지않|피가안멈|출혈이안멈|지혈이안[되돼]|출혈이계속되"));
    }
    private EmergencyScreen() { }

    static Status assess(List<String> users) {
        Map<String, Status> current = new LinkedHashMap<>();
        for (String user : users) {
            String full = user.replaceAll("\\s+", "");
            if (full.matches(".*(?:현재|지금|그런|해당).{0,12}(?:증상은없|증상이없|증상없|모두없).*")) {
                if (uncertainNegation(full.replaceFirst("^.*?(?:증상은|증상이|증상|모두)", ""))) current.put("unclear-denial", Status.UNCERTAIN);
                else current.replaceAll((key, value) -> Status.NONE);
            }
            if (full.matches("(?:지금은|현재는)(?:없어요|없습니다|괜찮아요|괜찮습니다)[.!]?")) current.replaceAll((key, value) -> value == Status.PRESENT ? Status.UNCERTAIN : Status.NONE);
            for (String sentence : full.split("[.!?。！？\\n]|하지만|그런데")) {
                if (illustrative(sentence)) continue;
                for (String clause : sentence.split("(?=지금|현재|오늘|방금)")) {
                if (nonCurrent(clause)) continue;
                for (Map.Entry<String, Pattern> entry : SIGNS.entrySet()) {
                    Matcher match = entry.getValue().matcher(clause);
                    while (match.find()) {
                        String tail = sharedNegationTail(clause.substring(match.end()));
                        if (uncertainNegation(tail)) current.put(entry.getKey(), Status.UNCERTAIN);
                        else if (NEGATED.matcher(tail).find()) current.put(entry.getKey(), Status.NONE);
                        else if (tail.matches(".*(?:는지모르|인지모르|있을까|일까요|같기도|모르겠).*")) current.put(entry.getKey(), Status.UNCERTAIN);
                        else current.put(entry.getKey(), Status.PRESENT);
                    }
                }
                }
            }
        }
        if (current.containsValue(Status.PRESENT)) return Status.PRESENT;
        return current.containsValue(Status.UNCERTAIN) ? Status.UNCERTAIN : Status.NONE;
    }

    static boolean nonCurrent(String compact) {
        boolean current = compact.contains("지금") || compact.contains("현재") || compact.contains("방금") || compact.contains("오늘") || compact.contains("다시") || compact.contains("계속") || compact.contains("아직") || compact.contains("부터");
        if (illustrative(compact)) return true;
        return !current && compact.matches(".*(?:작년|예전|과거|어릴때|년전에|년전|몇년전|20[0-9]{2}년에|어제|지난주|지난달|지난번|있었던적|있던적).*" );
    }

    private static boolean illustrative(String compact) {
        boolean actual = compact.contains("예시가아니") || compact.contains("예시가아닌") || compact.contains("가정이아니") || compact.contains("실제로");
        return !actual && compact.matches(".*(?:예시|예를들어|가정하면|만약|드라마|기사에서|뜻이뭐|무슨뜻|어떤증상|뭔가요).*" );
    }

    private static boolean uncertainNegation(String text) {
        return text.matches("^(?:은|는|이|가|도|증상은|증상이|모두|둘다|다|건|것은|는건|는것은)*(?:없지않|없지는않|없는건아니|없는것은아니|없다는건아니|없다는뜻은아니|아닌가).*" );
    }

    private static String sharedNegationTail(String tail) {
        String stripped = tail;
        for (Pattern pattern : SIGNS.values()) stripped = pattern.matcher(stripped).replaceAll("");
        // Only coordinated symptom nouns share the final denial. 'A is present, but B is absent' stays separate.
        stripped = stripped.replaceFirst("^(?:(?:과|와|및|또는|이나|랑|이랑|,|·)+)", "");
        return NEGATED.matcher(stripped).find() || uncertainNegation(stripped) ? stripped : tail;
    }

    static boolean supportsCurrentWarning(String evidence, List<String> users) {
        String compact = evidence.replaceAll("\\s+", "");
        if (nonCurrent(compact)) return false;
        boolean recognized = SIGNS.values().stream().anyMatch(pattern -> pattern.matcher(compact).find());
        if (recognized) return assess(users) == Status.PRESENT;
        for (int index = users.size() - 1; index >= 0; index--) {
            String user = users.get(index).replaceAll("\\s+", "");
            if (!user.contains(compact)) continue;
            return !nonCurrent(user) && !user.matches(".*(?:그런증상은없|해당없|증상이아니|예시|가정).*" );
        }
        return false;
    }
}
