package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.golden.Phase5Fixtures;
import com.tailor.engine.golden.Phase5Fixtures.ExpectedSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * P5-T1 (PHASE5_SPEC.md section 10, step 5.1): each fixture JD's title and weighted skills equal
 * {@code expected_selection.json}; the platform rewording parses to exactly the same weighted
 * skills as the original.
 */
class JdParserTest {

    private static final List<String> JD_NAMES = List.of("platform", "platform_reworded", "frontend", "data");

    @Test
    void everyFixtureJdMatchesExpectedTitleAndSkills() throws Exception {
        Path fixturesDir = CorpusPaths.phase5FixturesDir();
        SkillsDictionary skills = SkillsDictionary.load(fixturesDir.resolve("skills_fixture.json"));
        ExpectedSelection expected = Phase5Fixtures.loadExpectedSelection(fixturesDir.resolve("expected_selection.json"));

        Map<String, JobDescription> parsed = new LinkedHashMap<>();
        for (String name : JD_NAMES) {
            String text = Files.readString(fixturesDir.resolve("jds").resolve(name + ".txt"));
            parsed.put(name, JdParser.parse(text, skills));
        }

        for (String name : JD_NAMES) {
            JobDescription actual = parsed.get(name);
            var exp = expected.jds().get(name);
            assertEquals(exp.title(), actual.title(), name + ": title");
            assertEquals(exp.skills(), actual.skills(), name + ": weighted skills");
        }
    }

    @Test
    void platformRewordingParsesToExactlyTheSameWeightedSkillsAsTheOriginal() throws Exception {
        Path fixturesDir = CorpusPaths.phase5FixturesDir();
        SkillsDictionary skills = SkillsDictionary.load(fixturesDir.resolve("skills_fixture.json"));

        String original = Files.readString(fixturesDir.resolve("jds").resolve("platform.txt"));
        String reworded = Files.readString(fixturesDir.resolve("jds").resolve("platform_reworded.txt"));

        JobDescription a = JdParser.parse(original, skills);
        JobDescription b = JdParser.parse(reworded, skills);

        assertEquals(a.skills(), b.skills());
    }
}
