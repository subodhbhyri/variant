package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.generate.ModelResponse;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.golden.Phase5Fixtures;
import com.tailor.engine.golden.Phase5Fixtures.ExpectedSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * P5-T2 (PHASE5_SPEC.md section 10, step 5.3): {@code scores_job} for every JD equal the expected
 * values exactly, using {@link FakeEmbedder}.
 */
class JdScorerTest {

    private static final List<String> SCORED_JDS = List.of("platform", "frontend", "data");

    @Test
    void scoresJobMatchExpectedForEveryJd() throws Exception {
        Path fixturesDir = CorpusPaths.phase5FixturesDir();
        SkillsDictionary skills = SkillsDictionary.load(fixturesDir.resolve("skills_fixture.json"));
        ExpectedSelection expected =
                Phase5Fixtures.loadExpectedSelection(fixturesDir.resolve("expected_selection.json"));
        Map<String, List<ModelResponse.BulletCandidate>> jobVariants =
                Phase5Fixtures.loadJobVariants(fixturesDir.resolve("job_variants.json"));
        List<ModelResponse.BulletCandidate> candidates = jobVariants.get("northwind");
        Embedder embedder = new FakeEmbedder();

        for (String name : SCORED_JDS) {
            String text = Files.readString(fixturesDir.resolve("jds").resolve(name + ".txt"));
            JobDescription jd = JdParser.parse(text, skills);
            Map<String, Double> expectedScores = expected.jds().get(name).scoresJob();

            for (ModelResponse.BulletCandidate c : candidates) {
                double actual = JdScorer.score(c.variants().get("1"), jd, skills, embedder);
                assertEquals(expectedScores.get(c.id()), actual, name + " " + c.id());
            }
        }
    }
}
