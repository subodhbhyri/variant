package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.blocks.LibraryProject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Each reason a project can't fill a position, as summary.md and match.json report it. */
class AssemblerInfeasibilityTest {

    private static Phase5TestSetup s;

    @BeforeAll
    static void load() throws Exception {
        s = Phase5TestSetup.load();
    }

    private static LibraryProject project(String home, List<Map<String, String>> bullets) {
        return new LibraryProject("p", "p", null, List.of(), null, bullets, home);
    }

    @Test
    void aHomeSectionMismatchIsTheReason() {
        LibraryProject p = project("Projects", List.of(Map.of("1", "Did a thing.")));
        String reason = Assembler.infeasibility(p, new Shapes.PositionShape("P0", List.of(1), false, "Work"),
                s.skills, s.embedder);
        assertTrue(reason.contains("home section \"Projects\" is not the position's section \"Work\""), reason);
    }

    @Test
    void aMissingLengthIsTheReason() {
        LibraryProject p = project(null, List.of(Map.of("1", "Did a thing.")));
        String reason = Assembler.infeasibility(p, new Shapes.PositionShape("P0", List.of(2), false, null),
                s.skills, s.embedder);
        assertEquals("no bullet has a variant at 2 line(s)", reason);
    }

    @Test
    void tooFewBulletsIsTheReason() {
        LibraryProject p = project(null, List.of(Map.of("1", "Did a thing.")));
        String reason = Assembler.infeasibility(p, new Shapes.PositionShape("P0", List.of(1, 1), false, null),
                s.skills, s.embedder);
        assertEquals("has 1 bullet(s), and the position needs 2", reason);
    }

    @Test
    void aProjectThatCanFillTheShapeHasNoReason() {
        LibraryProject p = project(null, List.of(Map.of("1", "One."), Map.of("2", "Two two.")));
        assertNull(Assembler.infeasibility(p, new Shapes.PositionShape("P0", List.of(2), false, null),
                s.skills, s.embedder));
    }
}
