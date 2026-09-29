package com.tailor.engine.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailor.engine.CorpusPaths;
import com.tailor.engine.blocks.LibraryProject;
import com.tailor.engine.generate.ModelResponse.BulletCandidate;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.golden.Phase5Fixtures;
import com.tailor.engine.golden.Phase5Fixtures.ExpectedSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared fixture loading for the Phase 5 match tests (mirrors jd_ref.py's own fixture inputs). */
final class Phase5TestSetup {

    final SkillsDictionary skills;
    final ExpectedSelection expected;
    final Map<String, List<BulletCandidate>> jobVariants;
    final List<LibraryProject> library;
    final Shapes shapes;
    final Map<String, JobDescription> jds;
    final Embedder embedder = new FakeEmbedder();

    private Phase5TestSetup(SkillsDictionary skills, ExpectedSelection expected,
            Map<String, List<BulletCandidate>> jobVariants, List<LibraryProject> library, Shapes shapes,
            Map<String, JobDescription> jds) {
        this.skills = skills;
        this.expected = expected;
        this.jobVariants = jobVariants;
        this.library = library;
        this.shapes = shapes;
        this.jds = jds;
    }

    static Phase5TestSetup load() throws Exception {
        Path fixturesDir = CorpusPaths.phase5FixturesDir();
        SkillsDictionary skills = SkillsDictionary.load(fixturesDir.resolve("skills_fixture.json"));
        ExpectedSelection expected =
                Phase5Fixtures.loadExpectedSelection(fixturesDir.resolve("expected_selection.json"));
        Map<String, List<BulletCandidate>> jobVariants =
                Phase5Fixtures.loadJobVariants(fixturesDir.resolve("job_variants.json"));

        Path libraryJson = CorpusPaths.phase3FixturesDir().resolve("library.json");
        LibraryProject.Library lib =
                new ObjectMapper().readValue(libraryJson.toFile(), LibraryProject.Library.class);

        Shapes shapes = Shapes.load(fixturesDir.resolve("shapes.json"));

        Map<String, JobDescription> jds = new LinkedHashMap<>();
        for (String name : List.of("platform", "platform_reworded", "frontend", "data")) {
            String text = Files.readString(fixturesDir.resolve("jds").resolve(name + ".txt"));
            jds.put(name, JdParser.parse(text, skills));
        }

        return new Phase5TestSetup(skills, expected, jobVariants, lib.projects(), shapes, jds);
    }

    List<BulletCandidate> jobCandidates() {
        return jobVariants.get(shapes.job().id());
    }
}
