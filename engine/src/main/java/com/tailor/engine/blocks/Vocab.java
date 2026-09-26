package com.tailor.engine.blocks;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** PHASE3_SPEC.md section 2: vocabulary-based role suggestion (reference: {@code role_of}). */
public final class Vocab {

    private static final Map<String, List<String>> WORDS = Map.of(
            "projects", List.of("project", "research paper", "publication", "open-source", "open source",
                    "contribution", "portfolio"),
            "experience", List.of("experience", "employment", "internship", "work history"),
            "other", List.of("education", "skill", "certification", "award", "honor", "achievement", "leadership",
                    "activit", "extracurricular", "summary", "objective", "coursework", "profile", "interest",
                    "volunteer", "position", "language", "hobbies", "reference"));

    private static final List<String> ROLE_ORDER = List.of("projects", "experience", "other");

    private Vocab() {
    }

    /** The role a heading's text suggests, or empty if it contains no vocabulary word at all. */
    public static Optional<String> roleOf(String text) {
        String s = stripChars(text.toLowerCase(Locale.ROOT), " :");
        for (String role : ROLE_ORDER) {
            for (String k : WORDS.get(role)) {
                if (s.contains(k)) {
                    return Optional.of(role);
                }
            }
        }
        return Optional.empty();
    }

    /** Python's {@code str.strip(chars)}: strips any leading/trailing characters found in {@code chars}. */
    static String stripChars(String s, String chars) {
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
}
