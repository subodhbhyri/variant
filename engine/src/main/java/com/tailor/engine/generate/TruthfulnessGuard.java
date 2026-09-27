package com.tailor.engine.generate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHASE4_SPEC.md section 5 (step 4.4): the truthfulness guard, a faithful Java port of
 * {@code reference/guard_ref.py}. Runs on every generated variant, before any render, against
 * the section's sources (its raw text, current bullets and field values). Deterministic, no
 * network, no model call.
 */
public final class TruthfulnessGuard {

    private static final List<String> WORD_NUM_ORDER = List.of(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen", "twenty");

    private static final Map<String, Integer> WORD_NUMS;

    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < WORD_NUM_ORDER.size(); i++) {
            m.put(WORD_NUM_ORDER.get(i), i);
        }
        WORD_NUMS = Collections.unmodifiableMap(m);
    }

    // Not "one" (too ambiguous: "one of the"), and not inside a hyphenated word ("zero-downtime").
    private static final List<String> NUM_WORDS =
            WORD_NUM_ORDER.stream().filter(w -> !w.equals("one")).toList();

    private static final Pattern NUM = Pattern.compile(
            "(?<![\\w.])(\\d+(?:[.,]\\d+)*)\\s*([kKmMbB])?(?![\\w])|(?<![\\w-])("
                    + String.join("|", NUM_WORDS) + ")(?![\\w-])",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern URL = Pattern.compile(
            "https?://|www\\.|\\b[\\w.+-]+@[\\w-]+\\.\\w|\\b[\\w-]+\\.(?:com|io|dev|org|net|ai|app|co|me|xyz)\\b(?:/\\S*)?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FIRST_PERSON =
            Pattern.compile("(?<![\\w'])(I|me|my|mine|we|our|ours|us)(?![\\w'])");

    private TruthfulnessGuard() {
    }

    /**
     * @param variant     the generated bullet text
     * @param sourceTexts what the variant may state: for DETAILED, raw text + current bullets +
     *                    field values; for EXISTING_ONLY, current bullets + field values only
     * @param budgetChars the character budget for this length, or null to skip the check
     * @return reason codes, in the order the spec's table lists them; empty means the variant passes
     */
    public static List<String> guard(String variant, List<String> sourceTexts, Integer budgetChars,
            SkillsDictionary skills) {
        List<String> reasons = new ArrayList<>();
        String v = variant.strip();
        if (v.isEmpty()) {
            return List.of("EMPTY");
        }
        if (variant.indexOf('\n') >= 0 || variant.indexOf('\r') >= 0) {
            reasons.add("MULTILINE");
        }
        if (URL.matcher(v).find()) {
            reasons.add("URL");
        }
        if (FIRST_PERSON.matcher(v).find()) {
            reasons.add("FIRST_PERSON");
        }
        if (budgetChars != null && v.length() > budgetChars) {
            reasons.add("OVER_BUDGET");
        }

        String source = String.join("\n", sourceTexts);
        Set<Double> sourceNums = numbers(source);
        List<Double> missingNums = new ArrayList<>();
        for (double n : numbers(v)) {
            if (!sourceNums.contains(n)) {
                missingNums.add(n);
            }
        }
        Collections.sort(missingNums);
        for (double n : missingNums) {
            reasons.add("UNSUPPORTED_NUMBER:" + formatNumber(n));
        }

        Set<String> sourceTechs = techs(source, skills);
        Set<String> missingTechs = new TreeSet<>(techs(v, skills));
        missingTechs.removeAll(sourceTechs);
        for (String t : missingTechs) {
            reasons.add("UNSUPPORTED_TECH:" + t);
        }

        return reasons;
    }

    private static String formatNumber(double n) {
        if (!Double.isInfinite(n) && n == Math.rint(n)) {
            return String.valueOf((long) n);
        }
        return String.valueOf(n);
    }

    static Set<Double> numbers(String text) {
        Set<Double> out = new LinkedHashSet<>();
        Matcher m = NUM.matcher(text);
        while (m.find()) {
            String word = m.group(3);
            if (word != null) {
                out.add((double) WORD_NUMS.get(word.toLowerCase()));
                continue;
            }
            double v = Double.parseDouble(m.group(1).replace(",", ""));
            String suffix = m.group(2);
            double mult = 1;
            if (suffix != null) {
                mult = switch (suffix.toLowerCase()) {
                    case "k" -> 1e3;
                    case "m" -> 1e6;
                    case "b" -> 1e9;
                    default -> 1;
                };
            }
            out.add(v * mult);
        }
        return out;
    }

    /** A longer canonical term containing a shorter one ("React Native" vs "React") keeps both —
     * fine, since the caller only ever takes a set difference against the sources' own techs. */
    static Set<String> techs(String text, SkillsDictionary skills) {
        Set<String> found = new TreeSet<>();
        for (Map.Entry<String, List<Pattern>> e : skills.patternsByCanonical().entrySet()) {
            for (Pattern p : e.getValue()) {
                if (p.matcher(text).find()) {
                    found.add(e.getKey());
                    break;
                }
            }
        }
        return found;
    }
}
