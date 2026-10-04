package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.blocks.LibraryProject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * P5-T14 (PHASE5_SPEC.md section 10, step 5.6): a position whose swap always fails verification
 * is re-solved (same best-first rule, excluding the failed pairing) at most 3 times, then
 * dropped from the assignment — left at its own original content — and recorded under {@code
 * degraded} with the reason; the other positions still get filled normally.
 *
 * <p>Exercises {@link ResumeRenderer#resolveAssignment} directly, with a fake {@code attemptSwap}
 * standing in for the real {@code BlockSwapper} call: P1 always reports failure, P0 always
 * succeeds — isolating the re-solve/degrade bookkeeping from LibreOffice rendering, which the
 * rest of the fail-soft path (a real {@code BlockSwapper.swap} always failing on a
 * deliberately-broken header) can't be driven through without a render. Six synthetic,
 * one-bullet library projects (any one of which trivially fills either one-bullet position) keep
 * the scenario self-contained: whichever the real best-first scoring would pick first is
 * irrelevant here, only that each re-solve genuinely excludes the previous failure.
 */
class FailSoftAssemblyTest {

    @Test
    void aPositionThatAlwaysFailsIsDroppedAndRecordedAsDegradedAfterThreeResolves() throws Exception {
        Phase5TestSetup s = Phase5TestSetup.load();
        JobDescription jd = s.jds.get("platform");

        Shapes.PositionShape p0 = new Shapes.PositionShape("P0", List.of(1), false, null);
        Shapes.PositionShape p1 = new Shapes.PositionShape("P1", List.of(1), false, null);
        Shapes shapes = new Shapes(new Shapes.JobShape("job-0", List.of()), List.of(p0, p1), List.of());

        List<LibraryProject> library = new ArrayList<>();
        for (String id : List.of("a", "b", "c", "d", "e", "f")) {
            library.add(new LibraryProject(id, id, null, List.of(), null,
                    List.of(Map.of("1", "Did something with " + id + ".")), null));
        }

        List<String> p1Attempts = new ArrayList<>();
        int[] passCount = {0};
        List<AssembledResume.Degraded> degraded = new ArrayList<>();

        List<AssembledResume.ProjectAssignment> projects = ResumeRenderer.resolveAssignment(
                shapes, library, jd, s.skills, s.embedder,
                () -> passCount[0]++,
                assignment -> {
                    if ("P1".equals(assignment.position())) {
                        p1Attempts.add(assignment.project());
                        return "FAKE_SWAP_FAILURE";
                    }
                    return null;
                },
                degraded);

        assertNotNull(projects, "P0 must still get a full assignment");
        assertTrue(projects.stream().noneMatch(a -> "P1".equals(a.position())),
                "P1 must be dropped from the final assignment, not forced through anyway");
        assertEquals(1, projects.size(), "P0 must still be filled");
        assertEquals("P0", projects.get(0).position());

        assertEquals(1, degraded.size(), "exactly one position should end up degraded");
        AssembledResume.Degraded d = degraded.get(0);
        assertEquals("P1", d.position());
        assertEquals("FAKE_SWAP_FAILURE", d.reason());
        assertEquals(p1Attempts.get(p1Attempts.size() - 1), d.project(),
                "the degraded note must name the last project actually tried there");

        // re-solve at most 3 times after the first failure = at most 4 attempts at P1 before
        // giving up; it must not have given up any earlier, and must not still be trying forever.
        assertEquals(4, p1Attempts.size(), "P1 must be tried exactly 4 times (1 + 3 re-solves) before degrading");
        assertEquals(5, passCount[0], "4 failing passes, then one final successful pass without P1");

        // each re-solve must have genuinely excluded the previous failure, not repeated it.
        assertEquals(p1Attempts.size(), p1Attempts.stream().distinct().count(),
                "each re-solve must try a different project at P1, never one already marked infeasible there");
    }
}
