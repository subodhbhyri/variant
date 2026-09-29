package com.tailor.engine.match;

import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.generate.TruthfulnessGuard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * PHASE5_SPEC.md section 1 (step 5.1): a faithful port of {@code reference/jd_ref.py}'s
 * {@code parse_jd}. Deterministic, no network, no model call — dictionary matching only (D1).
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

    private static final Pattern REQUIRED = Pattern.compile(
            "\\b(requirements?|qualifications?|must[- ]haves?|what you('|’)ll need|you have|minimum)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PREFERRED = Pattern.compile(
            "\\b(nice[- ]to[- ]haves?|preferred|bonus|plus|good to have)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern HEADING = Pattern.compile(
            "^\\s*[A-Z][^.!?]{0,60}:\\s*$|^\\s*[A-Z][A-Za-z ,'’&/-]{2,40}$");

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
        String title = lines.stream().map(String::strip).filter(s -> !s.isEmpty()).findFirst().orElse("");

        double weight = 0.3;
        List<String> reqLines = new ArrayList<>();
        Map<String, Double> found = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String s = lines.get(i).strip();
            if (s.isEmpty()) {
                continue;
            }
            boolean isRequired = REQUIRED.matcher(s).find();
            boolean isPreferred = PREFERRED.matcher(s).find();
            if (HEADING.matcher(s).matches() && (isRequired || isPreferred || s.endsWith(":"))) {
                weight = isRequired ? 1.0 : isPreferred ? 0.5 : 0.3;
                continue;
            }
            if (weight >= 0.5) {
                reqLines.add(s);
            }
            for (String t : TruthfulnessGuard.techs(s, skills)) {
                found.merge(t, weight, Math::max);
            }
        }
        for (String t : TruthfulnessGuard.techs(title, skills)) {
            found.put(t, 1.0); // a skill in the title counts as required
        }

        return new JobDescription(title, new TreeMap<>(found), String.join(" ", reqLines));
    }

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\R", -1)) {
            out.add(line.stripTrailing());
        }
        return out;
    }
}
