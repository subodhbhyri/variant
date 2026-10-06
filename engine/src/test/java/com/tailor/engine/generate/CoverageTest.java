package com.tailor.engine.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** PHASE4_SPEC.md section 6.2: a project covers a home-section position only with distinct bullets at its line counts. */
class CoverageTest {

    @Test
    void aProjectWithBulletsAtEveryShapeLengthCovers() {
        List<Map<String, String>> bullets = List.of(Map.of("1", "a"), Map.of("2", "b"), Map.of("1", "c"));
        assertEquals(new Coverage.Result(true, List.of()), Coverage.check(bullets, List.of(List.of(1, 2, 1))));
    }

    @Test
    void theMissingLengthIsReported() {
        List<Map<String, String>> bullets = List.of(Map.of("1", "a"), Map.of("1", "b"), Map.of("1", "c"));
        Coverage.Result r = Coverage.check(bullets, List.of(List.of(1, 2)));
        assertFalse(r.covered());
        assertEquals(List.of(2), r.missingLengths());
    }

    @Test
    void oneBulletCannotTakeTwoSlots() {
        List<Map<String, String>> bullets = List.of(Map.of("1", "a", "2", "b"));
        assertFalse(Coverage.check(bullets, List.of(List.of(1, 2))).covered(),
                "a single bullet fills one slot, not two");
    }

    @Test
    void anyOneHomePositionIsEnough() {
        List<Map<String, String>> bullets = List.of(Map.of("1", "a"), Map.of("1", "b"));
        Coverage.Result r = Coverage.check(bullets, List.of(List.of(2), List.of(1, 1)));
        assertTrue(r.covered(), "the second position is fillable by two 1-line bullets");
    }

    @Test
    void aPositionWithNoBulletSlotsIsNotAHomeToCover() {
        assertFalse(Coverage.check(List.of(), List.of(List.of(), List.of(2))).covered(),
                "an empty position must not count as covered; the 2-line one still needs filling");
        assertTrue(Coverage.check(List.of(), List.of(List.of())).covered());
    }

    @Test
    void noHomePositionsMeansNothingToCover() {
        assertTrue(Coverage.check(List.of(), List.of()).covered());
    }
}
