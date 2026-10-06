package com.tailor.engine.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** PHASE4_SPEC.md section 6 (bullet endings): the resume's convention, and a generated bullet made to match it. */
class BulletEndingTest {

    @Test
    void theConventionIsTheMajorityOfTheOriginalBullets() {
        assertEquals("", BulletEnding.convention(List.of("Built A", "Shipped B", "Ran C.")), "two of three have none");
        assertEquals(".", BulletEnding.convention(List.of("Built A.", "Shipped B.", "Ran C")), "two of three have one");
        assertEquals("", BulletEnding.convention(List.of("Built A.", "Shipped B")), "a tie goes to no period");
        assertNull(BulletEnding.convention(List.of()), "no bullets to go by: no convention");
        assertNull(BulletEnding.convention(Arrays.asList(null, " ")), "blank bullets don't count");
    }

    @Test
    void aGeneratedFinalPeriodIsRemovedWhenTheResumeHasNone() {
        assertEquals("Cut checkout latency by 38%", BulletEnding.apply("Cut checkout latency by 38%.", ""));
        assertEquals("Cut checkout latency by 38%", BulletEnding.apply("Cut checkout latency by 38%. ", ""),
                "trailing whitespace is dropped with the period");
        assertEquals("Waited...", BulletEnding.apply("Waited...", ""), "an ellipsis isn't a final period");
    }

    @Test
    void aMissingFinalPeriodIsAddedWhenTheResumeHasOne() {
        assertEquals("Built the (v2) API.", BulletEnding.apply("Built the (v2) API", "."));
        assertEquals("Cut latency by 38%.", BulletEnding.apply("Cut latency by 38%", "."));
    }

    @Test
    void endingsThatAreNotPlainWordsAreLeftAloneWhenAddingAPeriod() {
        assertEquals("Did it!", BulletEnding.apply("Did it!", "."));
        assertEquals("Did it?", BulletEnding.apply("Did it?", "."));
        assertEquals("Built A:", BulletEnding.apply("Built A:", "."), "a colon isn't a word end: no period after it");
    }

    @Test
    void noConventionLeavesTheTextAlone() {
        assertEquals("Built A.", BulletEnding.apply("Built A.", null));
    }
}
