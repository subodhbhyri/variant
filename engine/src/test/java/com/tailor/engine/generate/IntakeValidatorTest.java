package com.tailor.engine.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** P4-T7 (PHASE4_SPEC.md section 9): intake entry-time limits. Pure, no render. */
class IntakeValidatorTest {

    @Test
    void rawTextOver1500WordsIsRefused() {
        IntakeSection section = new IntakeSection("job-0", "job", "DETAILED", null, words(1501));
        IntakeValidator.Result result = IntakeValidator.validate(section, 0);
        assertFalse(result.accepted());
        assertEquals("RAW_TEXT_TOO_LONG", result.reason());
    }

    @Test
    void rawTextAt1500WordsIsAccepted() {
        IntakeSection section = new IntakeSection("job-0", "job", "DETAILED", null, words(1500));
        assertTrue(IntakeValidator.validate(section, 0).accepted());
    }

    @Test
    void ninthAddedProjectIsRefused() {
        IntakeSection section = new IntakeSection("project-new-9", "project", "DETAILED", null, "");
        IntakeValidator.Result result = IntakeValidator.validate(section, IntakeValidator.MAX_ADDED_PROJECTS);
        assertFalse(result.accepted());
        assertEquals("TOO_MANY_PROJECTS", result.reason());
    }

    @Test
    void eighthAddedProjectIsAccepted() {
        IntakeSection section = new IntakeSection("project-new-8", "project", "DETAILED", null, "");
        assertTrue(IntakeValidator.validate(section, IntakeValidator.MAX_ADDED_PROJECTS - 1).accepted());
    }

    @Test
    void existingProjectIdIsNeverCountedAgainstTheAddedLimit() {
        IntakeSection section = new IntakeSection("project-2", "project", "DETAILED", null, "");
        assertTrue(IntakeValidator.validate(section, IntakeValidator.MAX_ADDED_PROJECTS).accepted());
    }

    @Test
    void javascriptLinkIsRefusedAtEntry() {
        IntakeFields fields = new IntakeFields("Title", null,
                List.of(new IntakeFields.Link("Evil", "javascript:alert(1)")), null);
        IntakeSection section = new IntakeSection("project-new-1", "project", "DETAILED", fields, "");
        IntakeValidator.Result result = IntakeValidator.validate(section, 0);
        assertFalse(result.accepted());
        assertEquals("INVALID_LINK", result.reason());
    }

    @Test
    void validLinkIsAccepted() {
        IntakeFields fields = new IntakeFields("Title", null,
                List.of(new IntakeFields.Link("Site", "https://example.com")), null);
        IntakeSection section = new IntakeSection("project-new-1", "project", "DETAILED", fields, "");
        assertTrue(IntakeValidator.validate(section, 0).accepted());
    }

    private static String words(int count) {
        return "word ".repeat(count).strip();
    }
}
