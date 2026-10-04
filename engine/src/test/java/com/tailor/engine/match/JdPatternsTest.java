package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.golden.Phase5Fixtures;
import com.tailor.engine.golden.Phase5Fixtures.JdPatternCase;
import com.tailor.engine.golden.Phase5Fixtures.JdPatterns;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * P5-T13 (PHASE5_SPEC.md section 10, revision 3): every case in {@code
 * fixtures/phase5/jd_patterns/expected.json} — 10 synthetic postings, one per pattern found in
 * 20 real ones — parses to exactly its title and weighted skills, with the bundled, production
 * dictionary v2 (not the small fixture dictionary the other Phase 5 tests use).
 */
class JdPatternsTest {

    @Test
    void everyPatternMatchesExpectedTitleAndSkills() throws Exception {
        Path dir = CorpusPaths.phase5FixturesDir().resolve("jd_patterns");
        JdPatterns patterns = Phase5Fixtures.loadJdPatterns(dir.resolve("expected.json"));
        SkillsDictionary skills = SkillsDictionary.loadDefault();

        for (Map.Entry<String, JdPatternCase> e : patterns.cases().entrySet()) {
            String name = e.getKey();
            JdPatternCase expected = e.getValue();
            String text = Files.readString(dir.resolve(name + ".txt"));

            JobDescription actual = JdParser.parse(text, skills);

            assertEquals(expected.title(), actual.title(), name + ": title");
            assertEquals(expected.skills(), actual.skills(), name + ": weighted skills");
        }
    }
}
