package com.tailor.engine.generate;

import java.util.List;

/**
 * PHASE4_SPEC.md section 6.1 (bullet endings): every generated bullet ends the way the resume's own
 * bullets do. The resume's convention is the majority of its original bullets; a model's ending is
 * then made to match it by plain code, with no model call. Deterministic.
 */
public final class BulletEnding {

    /** The convention for a set of original bullets: {@code "."} when more than half of the non-blank
     * ones end with a period, {@code ""} when more than half don't, and null when there are none
     * to go by (in which case no bullet is changed). */
    public static String convention(List<String> originalBullets) {
        int withPeriod = 0;
        int total = 0;
        for (String b : originalBullets) {
            if (b == null || b.isBlank()) {
                continue;
            }
            total++;
            if (b.strip().endsWith(".")) {
                withPeriod++;
            }
        }
        if (total == 0) {
            return null;
        }
        return withPeriod * 2 > total ? "." : "";
    }

    /** {@code text} with its final period made to match {@code convention}. A convention of null leaves
     * the text alone. A period is added only after a plain word or closing bracket, and removed only
     * when it is a single final period (not an ellipsis). */
    public static String apply(String text, String convention) {
        if (convention == null || text == null) {
            return text;
        }
        String t = text.stripTrailing();
        if (".".equals(convention)) {
            if (t.isEmpty() || !endsWithWordOrBracket(t)) {
                return t.equals(text) ? text : t;
            }
            return t + ".";
        }
        if (t.endsWith(".") && !t.endsWith("..")) {
            return t.substring(0, t.length() - 1);
        }
        return t.equals(text) ? text : t;
    }

    private static boolean endsWithWordOrBracket(String t) {
        char c = t.charAt(t.length() - 1);
        return Character.isLetterOrDigit(c) || c == ')' || c == '%';
    }
}
