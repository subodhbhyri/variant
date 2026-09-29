package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tailor.engine.golden.Phase5Fixtures.ExpectedProjectAssignment;
import com.tailor.engine.golden.Phase5Fixtures.ExpectedResume;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * P5-T3 (PHASE5_SPEC.md section 10, step 5.4): resume #1 for platform, frontend and data equals
 * expected — job slots, positions, projects, chosen bullet indices, project scores.
 */
class AssemblerTest {

    private static final List<String> JD_NAMES = List.of("platform", "frontend", "data");

    @Test
    void resumeOneMatchesExpectedForEveryJd() throws Exception {
        Phase5TestSetup s = Phase5TestSetup.load();

        for (String name : JD_NAMES) {
            JobDescription jd = s.jds.get(name);
            AssembledResume actual =
                    Assembler.assemble(s.shapes, s.jobCandidates(), s.library, jd, s.skills, s.embedder, Set.of());
            double actualTotal = Assembler.totalScore(actual, s.shapes, s.jobCandidates(), jd, s.skills, s.embedder);

            ExpectedResume expected = s.expected.jds().get(name).resumes().get(0);
            assertEquals("best match", expected.label(), name + ": fixture sanity");
            assertEquals(expected.job(), actual.job(), name + ": job slots");
            assertEquals(expected.total(), actualTotal, name + ": total score");
            assertProjectsMatch(name, expected.projects(), actual.projects());
        }
    }

    private static void assertProjectsMatch(String name, List<ExpectedProjectAssignment> expected,
            List<AssembledResume.ProjectAssignment> actual) {
        assertEquals(expected.size(), actual.size(), name + ": number of positions filled");
        for (int i = 0; i < expected.size(); i++) {
            ExpectedProjectAssignment e = expected.get(i);
            AssembledResume.ProjectAssignment a = actual.get(i);
            String where = name + " position " + i + " (" + e.position() + ")";
            assertEquals(e.position(), a.position(), where + ": position id");
            assertEquals(e.project(), a.project(), where + ": project id");
            assertEquals(e.bullets(), a.bullets(), where + ": chosen bullet indices");
            assertEquals(e.score(), a.score(), where + ": project score");
            assertEquals(e.stack(), a.stack(), where + ": stack order");
        }
    }
}
