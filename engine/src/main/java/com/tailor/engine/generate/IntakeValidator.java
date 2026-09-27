package com.tailor.engine.generate;

import com.tailor.engine.blocks.LinkValidator;

/**
 * PHASE4_SPEC.md section 1 (step 4.1): the intake form's entry-time limits. A section is checked
 * as it's added, one at a time (D1); over-limit input is refused with a message, nothing is cut
 * or truncated.
 */
public final class IntakeValidator {

    public static final int MAX_RAW_WORDS = 1500;
    public static final int MAX_ADDED_PROJECTS = 8;

    public record Result(boolean accepted, String reason, String message) {
        public static Result ok() {
            return new Result(true, null, null);
        }

        static Result refused(String reason, String message) {
            return new Result(false, reason, message);
        }
    }

    private IntakeValidator() {
    }

    /** @param existingAddedProjects how many {@code project-new-N} sections were already accepted */
    public static Result validate(IntakeSection section, int existingAddedProjects) {
        int words = wordCount(section.rawText());
        if (words > MAX_RAW_WORDS) {
            return Result.refused("RAW_TEXT_TOO_LONG",
                    "raw text is " + words + " words; the limit is " + MAX_RAW_WORDS + ".");
        }
        if (isAddedProject(section) && existingAddedProjects >= MAX_ADDED_PROJECTS) {
            return Result.refused("TOO_MANY_PROJECTS",
                    "at most " + MAX_ADDED_PROJECTS + " added projects are allowed.");
        }
        if (section.fields() != null && section.fields().links() != null) {
            for (IntakeFields.Link link : section.fields().links()) {
                if (!LinkValidator.isValid(link.url())) {
                    return Result.refused("INVALID_LINK", "\"" + link.url() + "\" is not a valid link.");
                }
            }
        }
        return Result.ok();
    }

    private static boolean isAddedProject(IntakeSection section) {
        return "project".equals(section.kind()) && section.id() != null
                && section.id().startsWith("project-new-");
    }

    private static int wordCount(String text) {
        if (text == null) {
            return 0;
        }
        String trimmed = text.strip();
        return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
    }
}
