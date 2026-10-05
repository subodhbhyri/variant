package com.tailor.engine.generate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
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

    // A number, optionally followed by a short unit glued on or after one space (48ms, 300 s,
    // 1.5x, 15 km). Only K/k/M/B/bn are multipliers (4K = 4,000); a lowercase "m" is a unit
    // (metres, minutes) — MULTIPLIER's lookup below is deliberately case-sensitive.
    private static final Pattern NUM = Pattern.compile(
            "(?<![\\w.])(\\d+(?:[.,]\\d+)*)(?:\\s?([A-Za-z]{1,4}))?(?![\\w])|(?<![\\w-])("
                    + String.join("|", NUM_WORDS) + ")(?![\\w-])",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, Double> MULTIPLIER =
            Map.of("K", 1e3, "k", 1e3, "M", 1e6, "B", 1e9, "bn", 1e9);

    private static final Pattern URL = Pattern.compile(
            "https?://|www\\.|\\b[\\w.+-]+@[\\w-]+\\.\\w|\\b[\\w-]+\\.(?:com|io|dev|org|net|ai|app|co|me|xyz)\\b(?:/\\S*)?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FIRST_PERSON =
            Pattern.compile("(?<![\\w'])(I|me|my|mine|we|our|ours|us)(?![\\w'])");

    // --- Grounding (revision 4): catches wholesale invented sentences, which contain no
    // checkable number or technology. Share of a variant's content words that also occur in the
    // sources, with a crude stem so "reducing"/"reduced" match.
    private static final Set<String> STOP = Set.of(
            "a", "an", "the", "and", "or", "but", "of", "in", "on", "at", "to", "for", "from", "by", "with",
            "without", "via", "into", "onto", "over", "under", "as", "is", "are", "was", "were", "be", "been",
            "being", "this", "that", "these", "those", "it", "its", "their", "them", "they", "he", "she", "his",
            "her", "our", "we", "i", "my", "me", "you", "your", "up", "down", "out", "off", "than", "then", "so",
            "such", "very", "more", "most", "less", "least", "same", "across", "while", "during", "after",
            "before", "about", "per", "each", "all", "any", "both", "either", "neither", "not", "no", "nor",
            "only", "own", "also");

    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z'\\-]+");

    private static final List<String> STEM_SUFFIXES = List.of(
            "ations", "ation", "ising", "izing", "ised", "ized", "ings", "ing", "edly", "ed", "es", "s", "ly");

    static final double GROUNDING_MIN = 0.40;

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
        Set<NumberToken> sourceNums = numbers(source);
        List<NumberToken> missingNums = new ArrayList<>();
        for (NumberToken n : numbers(v)) {
            if (!sourceNums.contains(n)) {
                missingNums.add(n);
            }
        }
        missingNums.sort(Comparator.comparing(TruthfulnessGuard::sortKey));
        for (NumberToken n : missingNums) {
            reasons.add("UNSUPPORTED_NUMBER:" + formatToken(n));
        }

        // PHASE4_SPEC.md section 5 (revision 7): implications apply to the sources only -- a source
        // naming GitHub Actions supports "CI/CD" -- never to the variant itself.
        Set<String> sourceTechs = techsImplied(source, skills);
        Set<String> missingTechs = new TreeSet<>(techs(v, skills));
        missingTechs.removeAll(sourceTechs);
        for (String t : missingTechs) {
            reasons.add("UNSUPPORTED_TECH:" + t);
        }

        if (grounding(v, sourceTexts) < GROUNDING_MIN) {
            reasons.add("UNGROUNDED");
        }

        return reasons;
    }

    /** PHASE4_SPEC.md section 5.1: a candidate's versions must describe the same facts — the
     * shorter version's content words must be grounded in the longer version. */
    public static boolean consistent(String shorter, String longer) {
        return grounding(shorter, List.of(longer)) >= GROUNDING_MIN;
    }

    /** Share (0.0-1.0) of {@code variant}'s content words that are grounded in {@code
     * sourceTexts} — 1.0 if the variant has no content words at all. */
    public static double grounding(String variant, List<String> sourceTexts) {
        Set<String> src = new HashSet<>();
        for (String t : sourceTexts) {
            src.addAll(contentStems(t));
        }
        List<String> words = contentStems(variant);
        if (words.isEmpty()) {
            return 1.0;
        }
        long groundedCount = 0;
        for (String w : words) {
            if (grounded(w, src)) {
                groundedCount++;
            }
        }
        return (double) groundedCount / words.size();
    }

    private static boolean grounded(String stem, Set<String> sourceStems) {
        if (sourceStems.contains(stem)) {
            return true;
        }
        if (stem.length() >= 5) {
            String prefix = stem.substring(0, 5);
            for (String s : sourceStems) {
                if (s.length() >= 5 && s.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** PHASE5_SPEC.md's {@code fake_similarity} and scoring reuse this directly (D1: no LLM,
     * the same dictionary matcher and stem logic as Phase 4). */
    public static List<String> contentStems(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            String w = m.group();
            if (w.length() > 2 && !STOP.contains(w.toLowerCase())) {
                out.add(stem(w));
            }
        }
        return out;
    }

    private static String stem(String w) {
        String s = stripApostrophesAndHyphens(w.toLowerCase());
        for (String suffix : STEM_SUFFIXES) {
            if (s.endsWith(suffix) && s.length() - suffix.length() >= 4) {
                return s.substring(0, s.length() - suffix.length());
            }
        }
        return s;
    }

    private static String stripApostrophesAndHyphens(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == '\'' || s.charAt(start) == '-')) {
            start++;
        }
        while (end > start && (s.charAt(end - 1) == '\'' || s.charAt(end - 1) == '-')) {
            end--;
        }
        return s.substring(start, end);
    }

    /** Either a parsed numeric value, or (for a version like "0.111.0", two or more dots) its
     * literal text, compared and sorted as text rather than a number — the two kinds never equal
     * each other even if their text happens to coincide, since exactly one field is null. */
    private record NumberToken(Double value, String versionText) {
        static NumberToken ofValue(double v) {
            return new NumberToken(v, null);
        }

        static NumberToken ofVersion(String s) {
            return new NumberToken(null, s);
        }
    }

    private static String sortKey(NumberToken n) {
        return n.versionText() != null ? n.versionText() : pythonFloatStr(n.value());
    }

    private static String formatToken(NumberToken n) {
        return n.versionText() != null ? n.versionText() : formatNumber(n.value());
    }

    private static String formatNumber(double n) {
        if (!Double.isInfinite(n) && n == Math.rint(n)) {
            return String.valueOf((long) n);
        }
        return String.valueOf(n);
    }

    /** Mimics Python's {@code str(float)} closely enough for the guard's own sort key (every
     * whole value gets a trailing ".0", matching Python's repr) — only relative order among a
     * variant's own handful of missing numbers matters, never an external comparison. */
    private static String pythonFloatStr(double n) {
        if (!Double.isInfinite(n) && n == Math.rint(n) && Math.abs(n) < 1e16) {
            return (long) n + ".0";
        }
        return String.valueOf(n);
    }

    private static int countDots(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '.') {
                count++;
            }
        }
        return count;
    }

    private static Set<NumberToken> numbers(String text) {
        Set<NumberToken> out = new LinkedHashSet<>();
        Matcher m = NUM.matcher(text);
        while (m.find()) {
            String word = m.group(3);
            if (word != null) {
                out.add(NumberToken.ofValue(WORD_NUMS.get(word.toLowerCase())));
                continue;
            }
            String raw = m.group(1);
            if (countDots(raw) > 1) {
                out.add(NumberToken.ofVersion(raw));
                continue;
            }
            double v = Double.parseDouble(raw.replace(",", ""));
            String unit = m.group(2);
            double mult = unit == null ? 1 : MULTIPLIER.getOrDefault(unit, 1.0);
            out.add(NumberToken.ofValue(v * mult));
        }
        return out;
    }

    /** A longer canonical term containing a shorter one ("React Native" vs "React") keeps both —
     * fine, since the caller only ever takes a set difference against the sources' own techs.
     * PHASE5_SPEC.md section 1.1: also the skill matcher for job-description parsing and scoring. */
    public static Set<String> techs(String text, SkillsDictionary skills) {
        return skills.techsByText().computeIfAbsent(text, t -> {
            Set<String> found = new TreeSet<>();
            for (Map.Entry<String, List<Pattern>> e : skills.patternsByCanonical().entrySet()) {
                for (Pattern p : e.getValue()) {
                    if (p.matcher(t).find()) {
                        found.add(e.getKey());
                        break;
                    }
                }
            }
            return Collections.unmodifiableSet(found);
        });
    }

    /** PHASE5_SPEC.md section 8.1 (revision 4, reference {@code techs_implied}): every skill
     * {@code text} names, plus everything they unambiguously imply, transitively (dictionary
     * v2.1's {@code _implies} — PostgreSQL implies SQL, GitHub Actions implies CI/CD/GitHub/Git).
     * Used for the user's own material and for a bullet's keyword coverage when scoring it
     * against a job description, never for the job description's own skill set (a posting
     * asking for PostgreSQL does not ask for every SQL database). Memoized per text: both
     * functions are pure in (text, dictionary). */
    public static Set<String> techsImplied(String text, SkillsDictionary skills) {
        return skills.techsImpliedByText().computeIfAbsent(text, t -> {
            Set<String> out = new TreeSet<>();
            Deque<String> todo = new ArrayDeque<>(techs(t, skills));
            while (!todo.isEmpty()) {
                String next = todo.pop();
                if (!out.add(next)) {
                    continue;
                }
                todo.addAll(skills.implies().getOrDefault(next, List.of()));
            }
            return Collections.unmodifiableSet(out);
        });
    }
}
