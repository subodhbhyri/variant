package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tailor.engine.golden.Phase5Fixtures.CacheCase;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P5-T5 (PHASE5_SPEC.md section 10, steps 5.7-5.8): the four cache decisions and Jaccard values,
 * and each JD's missing skills, equal expected.
 */
class CacheAndMissingSkillsTest {

    private static final List<String> JD_NAMES = List.of("platform", "frontend", "data");

    @Test
    void missingSkillsMatchExpectedForEveryJd() throws Exception {
        Phase5TestSetup s = Phase5TestSetup.load();
        List<String> materialTexts = MissingSkills.materialTexts(s.jobCandidates(), s.library);

        for (String name : JD_NAMES) {
            JobDescription jd = s.jds.get(name);
            List<String> actual = MissingSkills.compute(jd, materialTexts, s.skills);
            assertEquals(s.expected.jds().get(name).missing(), actual, name);
        }
    }

    @Test
    void cacheDecisionsMatchExpected() throws Exception {
        Phase5TestSetup s = Phase5TestSetup.load();

        for (CacheCase c : s.expected.cache()) {
            JobDescription a = s.jds.get(c.a());
            JobDescription b = s.jds.get(c.b());
            CacheDecision actual = JdCache.decide(a, b, s.embedder);
            String where = c.a() + " vs " + c.b();
            assertEquals(c.decision(), actual.decision(), where + ": decision");
            assertEquals(c.weightedJaccard(), actual.weightedJaccard(), where + ": weighted jaccard");
        }
    }
}
