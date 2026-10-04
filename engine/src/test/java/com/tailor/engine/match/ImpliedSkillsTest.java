package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.generate.TruthfulnessGuard;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * P5-T16 (PHASE5_SPEC.md section 10, section 8.1 revision 4): with dictionary v2.1, a material
 * text naming only PostgreSQL and GitHub Actions makes SQL, CI/CD, GitHub and Git present; a job
 * description naming PostgreSQL does not acquire SQL; missing skills consider the whole onboarded
 * resume text.
 */
class ImpliedSkillsTest {

    @Test
    void materialImpliesTransitivelyButTheJobDescriptionNever() throws Exception {
        SkillsDictionary skills = SkillsDictionary.loadDefault();

        Set<String> implied = TruthfulnessGuard.techsImplied("We use PostgreSQL and GitHub Actions.", skills);
        assertTrue(implied.containsAll(Set.of("PostgreSQL", "GitHub Actions", "SQL", "CI/CD", "GitHub", "Git")),
                "PostgreSQL implies SQL; GitHub Actions implies CI/CD, GitHub and Git -- got " + implied);

        JobDescription jd = JdParser.parse("Requirements:\nPostgreSQL required.", skills);
        assertEquals(Map.of("PostgreSQL", 1.0), jd.skills(),
                "a posting naming PostgreSQL must not acquire SQL -- the job description is never implied");
    }

    @Test
    void missingSkillsConsiderTheWholeOnboardedResumeText() throws Exception {
        SkillsDictionary skills = SkillsDictionary.loadDefault();
        JobDescription jdNeedsSql = JdParser.parse("Requirements:\nSQL required.", skills);

        // Measured on a real run (PHASE5_SPEC.md section 8.1): without the whole-resume text,
        // missing-skills claimed SQL was missing from a resume whose own Skills section lists
        // PostgreSQL -- the stored job/project material alone (empty here) never would have
        // covered it.
        List<String> materials = MissingSkills.materialTexts("Skills: PostgreSQL, Java.", List.of(), List.of());
        List<String> missing = MissingSkills.compute(jdNeedsSql, materials, skills);

        assertTrue(missing.isEmpty(),
                "PostgreSQL named in the resume's own Skills section must cover an SQL requirement, got " + missing);
    }

    @Test
    void withoutTheResumeTextAnUnimpliedRequirementIsStillMissing() throws Exception {
        SkillsDictionary skills = SkillsDictionary.loadDefault();
        JobDescription jdNeedsAirflow = JdParser.parse("Requirements:\nAirflow required.", skills);

        List<String> materials = MissingSkills.materialTexts("Skills: PostgreSQL, Java.", List.of(), List.of());
        List<String> missing = MissingSkills.compute(jdNeedsAirflow, materials, skills);

        assertEquals(List.of("Airflow"), missing, "nothing in the material names or implies Airflow");
    }
}
