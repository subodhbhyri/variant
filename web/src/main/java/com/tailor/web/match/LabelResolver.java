package com.tailor.web.match;

import java.util.Comparator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHASE6_SPEC.md section 4.4: the engine's alternative labels name library projects by id ("includes
 * project-new-0 instead of forge"). The frontend never maps ids itself, so they are replaced by the
 * projects' titles here.
 */
public final class LabelResolver {

    private LabelResolver() {
    }

    /** @param titles project id to title; ids without a title are left as they are */
    public static String resolve(String label, Map<String, String> titles) {
        if (label == null || titles.isEmpty()) {
            return label;
        }
        String out = label;
        // Longest ids first, so "project-new-10" is not eaten by "project-new-1".
        for (String id : titles.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            String title = titles.get(id);
            if (title == null || title.isBlank()) {
                continue;
            }
            out = Pattern.compile("(?<![\\w-])" + Pattern.quote(id) + "(?![\\w-])").matcher(out)
                    .replaceAll(Matcher.quoteReplacement(title));
        }
        return out;
    }
}
