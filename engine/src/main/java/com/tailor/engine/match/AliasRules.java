package com.tailor.engine.match;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHASE5_SPEC.md section 7.1 (revision 2): a faithful port of {@code reference/jd_ref.py}'s
 * {@code alias_rules} — high-confidence spelling rules for suggesting that two skill names are
 * the same term. Measured: 7 of 10 fixture aliases match, 0 of 12 hard negatives (P5-T11).
 */
public final class AliasRules {

    private static final List<String> AFFIXES = List.of("js", "lang", "ful");
    private static final Pattern PARTS = Pattern.compile("[A-Z][a-z]+|[a-z]+|[A-Z]+(?![a-z])|\\d+");
    private static final Pattern NUMERONYM = Pattern.compile("([a-z])(\\d+)([a-z])");

    private AliasRules() {
    }

    /** -> sorted list of rule names under which {@code a} and {@code b} are spellings of one term. */
    public static List<String> rules(String a, String b) {
        String na = normTerm(a);
        String nb = normTerm(b);
        TreeSet<String> hits = new TreeSet<>();
        if (na.equals(nb)) {
            hits.add("punctuation");
        }

        for (String[] xy : new String[][] {{na, nb}, {nb, na}}) {
            String x = xy[0];
            String y = xy[1];
            for (String suf : AFFIXES) {
                if (x.endsWith(suf) && y.length() >= 2 && x.substring(0, x.length() - suf.length()).equals(y)) {
                    hits.add("affix");
                }
            }
            // K8s = k + 8 letters + s
            Matcher m = NUMERONYM.matcher(x);
            if (m.matches()) {
                int n = Integer.parseInt(m.group(2));
                if (y.length() == n + 2 && y.charAt(0) == m.group(1).charAt(0)
                        && y.charAt(y.length() - 1) == m.group(3).charAt(0)) {
                    hits.add("numeronym");
                }
            }
        }

        // TS = Type + Script
        for (String[] sl : new String[][] {{a, b}, {b, a}}) {
            String s = sl[0];
            String l = sl[1];
            List<String> p = parts(l);
            if (p.size() >= 2 && isUpperPython(s) && s.length() == p.size()) {
                StringBuilder initials = new StringBuilder();
                for (String w : p) {
                    initials.append(w.charAt(0));
                }
                if (initials.toString().toUpperCase(Locale.ROOT).equals(s)) {
                    hits.add("initialism");
                }
            }
        }
        return new ArrayList<>(hits);
    }

    /** Lower-case; drop spaces, hyphens and dots; keep {@code +} and {@code #} (C, C++ and C#
     * stay distinct). */
    private static String normTerm(String t) {
        return t.toLowerCase(Locale.ROOT).replaceAll("[\\s.\\-]", "");
    }

    private static List<String> parts(String t) {
        List<String> out = new ArrayList<>();
        Matcher m = PARTS.matcher(t);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** Python's {@code str.isupper()}: every cased character is uppercase, and at least one
     * cased character is present. */
    private static boolean isUpperPython(String s) {
        boolean hasCased = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                hasCased = true;
                if (Character.isLowerCase(c)) {
                    return false;
                }
            }
        }
        return hasCased;
    }
}
