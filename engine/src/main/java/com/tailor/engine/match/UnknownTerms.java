package com.tailor.engine.match;

import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.generate.TruthfulnessGuard;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHASE5_SPEC.md section 1.1: tech-looking tokens not in the skills dictionary — CamelCase,
 * containing {@code .}/{@code +}/{@code #}/a digit, or ALL-CAPS 2-6 letters — go to the alias
 * review queue (section 7.1) instead of being silently ignored or silently matched.
 */
public final class UnknownTerms {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9+#.]*");

    private UnknownTerms() {
    }

    public static boolean looksLikeTech(String token) {
        boolean hasLower = false;
        boolean hasUpper = false;
        boolean hasSymbolOrDigit = false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLowerCase(c)) {
                hasLower = true;
            }
            if (Character.isUpperCase(c)) {
                hasUpper = true;
            }
            if (c == '.' || c == '+' || c == '#' || Character.isDigit(c)) {
                hasSymbolOrDigit = true;
            }
        }
        boolean camelCase = hasLower && hasUpper;
        boolean allCaps2to6 = token.length() >= 2 && token.length() <= 6
                && token.chars().allMatch(Character::isUpperCase);
        return camelCase || hasSymbolOrDigit || allCaps2to6;
    }

    /** Tech-looking tokens in {@code text} that the dictionary doesn't already recognize. */
    public static Set<String> detect(String text, SkillsDictionary skills) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            String token = m.group();
            if (!looksLikeTech(token)) {
                continue;
            }
            if (!TruthfulnessGuard.techs(token, skills).isEmpty()) {
                continue; // already recognized under some canonical term
            }
            found.add(token);
        }
        return found;
    }
}
