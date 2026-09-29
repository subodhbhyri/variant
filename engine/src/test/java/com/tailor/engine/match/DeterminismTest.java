package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * P5-T9 (PHASE5_SPEC.md section 10, D7): running assembly twice on the same inputs gives an
 * identical result. Checked here at the data level (the {@code match.json} writer itself lands
 * with step 5.6/the CLI, at which point this should be extended to compare the written bytes).
 */
class DeterminismTest {

    private static final List<String> JD_NAMES = List.of("platform", "frontend", "data");

    @Test
    void assembleIsDeterministic() throws Exception {
        Phase5TestSetup s = Phase5TestSetup.load();
        for (String name : JD_NAMES) {
            JobDescription jd = s.jds.get(name);
            AssembledResume a =
                    Assembler.assemble(s.shapes, s.jobCandidates(), s.library, jd, s.skills, s.embedder, Set.of());
            AssembledResume b =
                    Assembler.assemble(s.shapes, s.jobCandidates(), s.library, jd, s.skills, s.embedder, Set.of());
            assertEquals(a, b, name);
        }
    }

    @Test
    void top3IsDeterministic() throws Exception {
        Phase5TestSetup s = Phase5TestSetup.load();
        for (String name : JD_NAMES) {
            JobDescription jd = s.jds.get(name);
            List<AssembledResume> a = Alternatives.top3(s.shapes, s.jobCandidates(), s.library, jd, s.skills, s.embedder);
            List<AssembledResume> b = Alternatives.top3(s.shapes, s.jobCandidates(), s.library, jd, s.skills, s.embedder);
            assertEquals(a, b, name);
        }
    }
}
