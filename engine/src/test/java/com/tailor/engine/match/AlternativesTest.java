package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.generate.ModelResponse.BulletCandidate;
import com.tailor.engine.golden.Phase5Fixtures.ExpectedResume;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * P5-T4 (PHASE5_SPEC.md section 10, step 5.5): alternative #2 for each JD equals expected
 * (label, job, projects); achievement groups equal expected; no resume ever has two candidates of
 * one achievement group.
 */
class AlternativesTest {

    private static final List<String> JD_NAMES = List.of("platform", "frontend", "data");

    @Test
    void achievementGroupsMatchExpected() throws Exception {
        Phase5TestSetup s = Phase5TestSetup.load();
        Map<String, String> groups = Assembler.achievementGroups(s.jobCandidates());
        assertEquals(s.expected.achievementGroups(), groups);
    }

    @Test
    void alternativesMatchExpectedForEveryJd() throws Exception {
        Phase5TestSetup s = Phase5TestSetup.load();

        for (String name : JD_NAMES) {
            JobDescription jd = s.jds.get(name);
            List<AssembledResume> resumes = Alternatives.top3(s.shapes, s.jobCandidates(), s.library, jd, s.skills, s.embedder);
            List<ExpectedResume> expected = s.expected.jds().get(name).resumes();

            assertEquals(expected.size(), resumes.size(), name + ": number of resumes offered");
            for (int i = 0; i < expected.size(); i++) {
                ExpectedResume e = expected.get(i);
                AssembledResume a = resumes.get(i);
                String where = name + " resume " + i;
                assertEquals(e.label(), a.label(), where + ": label");
                assertEquals(e.job(), a.job(), where + ": job slots");
                assertEquals(e.total(), a.total(), where + ": total score");
                assertEquals(e.projects().size(), a.projects().size(), where + ": positions filled");
                for (int p = 0; p < e.projects().size(); p++) {
                    var ep = e.projects().get(p);
                    var ap = a.projects().get(p);
                    String pwhere = where + " position " + p;
                    assertEquals(ep.position(), ap.position(), pwhere + ": position id");
                    assertEquals(ep.project(), ap.project(), pwhere + ": project id");
                    assertEquals(ep.bullets(), ap.bullets(), pwhere + ": chosen bullet indices");
                    assertEquals(ep.score(), ap.score(), pwhere + ": project score");
                    assertEquals(ep.stack(), ap.stack(), pwhere + ": stack order");
                }
            }
        }
    }

    @Test
    void noResumeEverPlacesTwoCandidatesOfOneAchievementGroup() throws Exception {
        Phase5TestSetup s = Phase5TestSetup.load();
        Map<String, String> groups = Assembler.achievementGroups(s.jobCandidates());

        for (String name : JD_NAMES) {
            JobDescription jd = s.jds.get(name);
            List<AssembledResume> resumes =
                    Alternatives.top3(s.shapes, s.jobCandidates(), s.library, jd, s.skills, s.embedder);
            for (AssembledResume resume : resumes) {
                Set<String> seenGroups = new HashSet<>();
                for (String c : resume.job()) {
                    if (c == null) {
                        continue;
                    }
                    String g = groups.get(c);
                    assertTrue(seenGroups.add(g),
                            name + " " + resume.label() + ": two candidates of group " + g + " in " + resume.job());
                }
            }
        }
    }

    /** Sanity: {@link BulletCandidate} ids used across these tests actually come from the
     * fixture's own job variants (guards against a stale/duplicated fixture load). */
    @Test
    void jobVariantsFixtureHasFourCandidates() throws Exception {
        Phase5TestSetup s = Phase5TestSetup.load();
        assertEquals(4, s.jobCandidates().size());
    }
}
