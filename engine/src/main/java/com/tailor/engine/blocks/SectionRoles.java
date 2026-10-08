package com.tailor.engine.blocks;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The one place a section's role is decided (PHASE3_SPEC.md section 2, and PHASE6_SPEC.md revision 3): the role the
 * heading's vocabulary suggests ({@link Vocab}), unless the user chose another for that heading. Everything that needs
 * a section's role (section detection, block analysis, swapping, generation, matching) goes through here, so a role
 * the user confirmed is the same one everywhere.
 *
 * <p>Overrides are keyed by section heading text, ignoring case and extra spaces. With no overrides every answer is
 * exactly {@link Vocab}'s, so nothing changes for a resume whose roles nobody changed.
 */
public final class SectionRoles {

    public static final Set<String> ROLES = Set.of("projects", "experience", "other");

    /** No overrides: the vocabulary's suggestion everywhere. */
    public static final SectionRoles NONE = new SectionRoles(Map.of());

    private final Map<String, String> overrides;

    private SectionRoles(Map<String, String> overrides) {
        this.overrides = overrides;
    }

    /** @param byHeading heading text to role; null or empty means no overrides. */
    public static SectionRoles of(Map<String, String> byHeading) {
        if (byHeading == null || byHeading.isEmpty()) {
            return NONE;
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : byHeading.entrySet()) {
            String role = e.getValue() == null ? "" : e.getValue().strip().toLowerCase(Locale.ROOT);
            if (!ROLES.contains(role)) {
                throw new IllegalArgumentException("role for \"" + e.getKey()
                        + "\" must be projects, experience or other, not \"" + e.getValue() + "\"");
            }
            normalized.put(key(e.getKey()), role);
        }
        return new SectionRoles(Map.copyOf(normalized));
    }

    /** Parses {@code "HEADING=role"} (the CLI's {@code --section-role}). */
    public static Map.Entry<String, String> parse(String assignment) {
        int eq = assignment == null ? -1 : assignment.lastIndexOf('=');
        if (eq <= 0 || eq == assignment.length() - 1) {
            throw new IllegalArgumentException("expected HEADING=role, got \"" + assignment + "\"");
        }
        String role = assignment.substring(eq + 1).strip().toLowerCase(Locale.ROOT);
        if (!ROLES.contains(role)) {
            throw new IllegalArgumentException("role must be projects, experience or other, not \"" + role + "\"");
        }
        return Map.entry(assignment.substring(0, eq).strip(), role);
    }

    /** Heading text compared ignoring case and runs of white space. */
    public static String key(String heading) {
        return heading == null ? "" : heading.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** The role the heading's own words suggest, or empty if it has none. */
    public static Optional<String> suggested(String heading) {
        return Vocab.roleOf(heading);
    }

    /** Whether a short paragraph reads as a section heading: it has a vocabulary word, or the user named it. */
    public boolean isHeadingText(String text) {
        return Vocab.roleOf(text).isPresent() || overrides.containsKey(key(text));
    }

    /** The section's role: the user's choice for this heading, else the vocabulary's suggestion, else {@code other}. */
    public String resolve(String heading) {
        String chosen = overrides.get(key(heading));
        return chosen != null ? chosen : Vocab.roleOf(heading).orElse("other");
    }

    public boolean isEmpty() {
        return overrides.isEmpty();
    }

    /** The overrides, keyed by normalized heading (lower case). */
    public Map<String, String> asMap() {
        return overrides;
    }
}
