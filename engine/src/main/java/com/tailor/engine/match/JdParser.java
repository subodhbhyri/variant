package com.tailor.engine.match;

import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.generate.TruthfulnessGuard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHASE5_SPEC.md section 1 (step 5.1): a faithful port of {@code reference/jd_ref.py}'s {@code
 * parse_jd} (= {@code parse_jd_v3}, revision 3, measured on 20 real postings). Deterministic, no
 * network, no model call — dictionary matching only (D1).
 */
public final class JdParser {

    /** Longer input is rejected before parsing (JD_TOO_LONG). */
    public static final int MAX_CHARS = 20_000;

    public record ValidationResult(boolean accepted, String reason, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null, null);
        }

        static ValidationResult refused(String reason, String message) {
            return new ValidationResult(false, reason, message);
        }
    }

    // --- title extraction -----------------------------------------------------------------

    private static final Pattern BOILERPLATE_TITLE = Pattern.compile(
            "^(?:(job description( summary)?|description|overview|general information|"
                    + "company|summary|apply|job details|position description)\\s*:?\\s*$|about\\b)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TITLE_LABEL = Pattern.compile(
            "^\\s*(?:job title|role|position|title)\\s*[:\\-–]\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE);

    private static final String ROLE_NOUN =
            "(?:engineer|developer|sdet|programmer|architect|scientist|analyst|tester|assistant)s?\\b";

    private static final Pattern ROLE_NOUN_SEARCH = Pattern.compile(ROLE_NOUN, Pattern.CASE_INSENSITIVE);
    private static final Pattern ROLE_NOUN_WORD_BOUNDARY =
            Pattern.compile("\\b" + ROLE_NOUN, Pattern.CASE_INSENSITIVE);

    private static final Pattern TITLE_PHRASE = Pattern.compile(
            "\\b(?:as an?|seeking (?:an?\\s+)?|hiring (?:an?\\s+)?|looking for (?:an?\\s+)?|join us as an?)\\s*"
                    + "((?:[A-Za-z0-9/+#.&()-]+\\s+){0,6}\\b" + ROLE_NOUN + ")",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern JOB_AREA_LINE = Pattern.compile(
            "(?:^|[>:,])\\s*([^>:,]{2,60}\\b" + ROLE_NOUN + ")\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern SENTENCE_END = Pattern.compile("[.!?]$");

    private static final Pattern TITLE_PREFIX_FILLER =
            Pattern.compile("^(skilled|passionate|talented|motivated)\\s+", Pattern.CASE_INSENSITIVE);

    private static final Pattern TITLE_PLURAL_SUFFIX = Pattern.compile(
            "(?i)(engineer|developer|programmer|architect|scientist|analyst|tester)s$");

    // --- sections / weighting --------------------------------------------------------------

    private record SectionRule(String kind, Pattern pattern) {
    }

    private static final List<SectionRule> SECTION_RULES = List.of(
            new SectionRule("ignore", Pattern.compile(
                    "\\b(benefits?|perks|salary|compensation|pay (range|scale)|equal opportunity|eeo\\b|privacy|disclosures?|"
                            + "physical|work environment|interview process|about (?!the role|you|the job)\\w|our (purpose|virtues|stands)|"
                            + "who thrives|why work|why join|e-verify|accommodation)", Pattern.CASE_INSENSITIVE)),
            new SectionRule("preferred", Pattern.compile(
                    "\\b(nice[- ]to[- ]haves?|preferred|bonus|desir(ed|able)|additional skills|good to have|a plus)\\b",
                    Pattern.CASE_INSENSITIVE)),
            new SectionRule("required", Pattern.compile(
                    "\\b(requirements?|required|qualifications?|must[- ]haves?|minimum|what you('|’)ll need|what you (bring|have)|"
                            + "who you are|about you|what we('|’)re looking for|skills|knowledge|education and experience|you have)\\b",
                    Pattern.CASE_INSENSITIVE)),
            new SectionRule("duties", Pattern.compile(
                    "\\b(responsibilit|what you('|’)ll do|what you will (do|work on)|duties|essential (job )?functions|"
                            + "the opportunity|the role|role description|your role|how we work|outcomes|day to day)",
                    Pattern.CASE_INSENSITIVE)));

    private static final Map<String, Double> WEIGHT = Map.of(
            "required", 1.0, "preferred", 0.5, "duties", 0.5, "other", 0.3, "ignore", 0.0);

    private static final Pattern LINE_PREFERRED = Pattern.compile(
            "\\b(preferred|a plus|is a plus|bonus|nice to have|desired|desirable|not required)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SKILLS_OR_JOB_DETAILS =
            Pattern.compile("skills|job details", Pattern.CASE_INSENSITIVE);

    private JdParser() {
    }

    public static ValidationResult validate(String text) {
        if (text.length() > MAX_CHARS) {
            return ValidationResult.refused("JD_TOO_LONG",
                    "job description is " + text.length() + " characters; the limit is " + MAX_CHARS + ".");
        }
        return ValidationResult.ok();
    }

    public static JobDescription parse(String text, SkillsDictionary skills) {
        List<String> lines = splitLines(text.strip());
        String title = extractTitle(lines);

        String kind = "other";
        Map<String, Double> found = new LinkedHashMap<>();
        List<String> reqLines = new ArrayList<>();
        for (String line : lines) {
            String s = line.strip();
            if (s.isEmpty()) {
                continue;
            }
            String k = headingKind(s);
            if (k != null) {
                kind = k;
                continue;
            }
            double w = WEIGHT.get(kind);
            if (w == 0) {
                continue;
            }
            if ("required".equals(kind) && LINE_PREFERRED.matcher(s).find()) {
                w = WEIGHT.get("preferred");
            }
            if (w >= 0.5) {
                reqLines.add(s);
            }
            for (String t : TruthfulnessGuard.techs(s, skills)) {
                found.merge(t, w, Math::max);
            }
        }
        for (String t : TruthfulnessGuard.techs(title, skills)) {
            found.put(t, 1.0); // a skill in the title counts as required
        }

        return new JobDescription(title, new TreeMap<>(found), String.join(" ", reqLines));
    }

    /** Explicit label > first meaningful line naming a role > "Job Area: ... > Role" line >
     * "As a X" phrase > none (never a guessed line). */
    private static String extractTitle(List<String> lines) {
        List<String> head = new ArrayList<>();
        for (String l : lines) {
            if (head.size() >= 40) {
                break;
            }
            String s = l.strip();
            if (!s.isEmpty()) {
                head.add(s);
            }
        }

        for (String l : head) {
            Matcher m = TITLE_LABEL.matcher(l);
            if (m.matches() && ROLE_NOUN_SEARCH.matcher(m.group(1)).find()) {
                return m.group(1);
            }
        }

        for (int i = 0; i < Math.min(3, head.size()); i++) {
            String l = head.get(i);
            if (BOILERPLATE_TITLE.matcher(l).lookingAt()) {
                continue;
            }
            if (ROLE_NOUN_WORD_BOUNDARY.matcher(l).find() && l.length() <= 90 && wordCount(l) <= 10
                    && !SENTENCE_END.matcher(l).find() && !TITLE_PHRASE.matcher(l).find()) {
                return l;
            }
        }

        for (int i = 0; i < Math.min(8, head.size()); i++) {
            String l = head.get(i);
            Matcher m = JOB_AREA_LINE.matcher(l);
            if (m.find() && !BOILERPLATE_TITLE.matcher(l).lookingAt()) {
                return m.group(1).strip();
            }
        }

        List<String> found = new ArrayList<>();
        for (String l : head) {
            Matcher m = TITLE_PHRASE.matcher(l);
            while (m.find()) {
                String t = TITLE_PREFIX_FILLER.matcher(m.group(1)).replaceFirst("").strip();
                t = TITLE_PLURAL_SUFFIX.matcher(t).replaceFirst("$1");
                found.add(t.isEmpty() ? t : t.substring(0, 1).toUpperCase() + t.substring(1));
            }
        }
        for (String t : found) {
            if (wordCount(t) >= 2) {
                return t;
            }
        }
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return "";
    }

    /** The section a heading-like line belongs to, or null if the line isn't a heading at all. */
    private static String headingKind(String line) {
        String s = stripChars(line.strip(), "()").strip();
        if (s.isEmpty() || s.length() > 70 || endsLikeASentence(s)) {
            return null;
        }
        String noTrailingColons = rstripChar(s, ':');
        if (noTrailingColons.indexOf(':') >= 0) {
            return null; // "Education: Bachelor's preferred" is a label, not a heading
        }
        if (SKILLS_OR_JOB_DETAILS.matcher(noTrailingColons.strip()).matches()) {
            return "other"; // job-board tag lists
        }
        int wordCount = wordCount(noTrailingColons);
        boolean looks = s.endsWith(":") || wordCount <= 6 || isAllUpperWithLetter(s);
        if (!looks) {
            return null;
        }
        for (SectionRule rule : SECTION_RULES) {
            if (rule.pattern().matcher(s).find()) {
                return rule.kind();
            }
        }
        return s.endsWith(":") ? "other" : null;
    }

    private static boolean endsLikeASentence(String s) {
        char c = s.charAt(s.length() - 1);
        return c == '.' || c == '!' || c == '?';
    }

    private static boolean isAllUpperWithLetter(String s) {
        boolean sawLetter = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                sawLetter = true;
                if (Character.isLowerCase(c)) {
                    return false;
                }
            }
        }
        return sawLetter;
    }

    private static int wordCount(String s) {
        String t = s.strip();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    private static String rstripChar(String s, char c) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == c) {
            end--;
        }
        return s.substring(0, end);
    }

    private static String stripChars(String s, String chars) {
        int start = 0;
        int end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end);
    }

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\R", -1)) {
            out.add(line.stripTrailing());
        }
        return out;
    }
}
