package com.tailor.engine.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.golden.Phase3Fixtures;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** P3-T1 (PHASE3_SPEC.md section 9): the synthetic fixture's sections and 5 positions. */
class BlocksFixtureTest {

    @Test
    void fixtureSectionsAndPositionsMatchExpected() throws Exception {
        Path fixturesDir = CorpusPaths.phase3FixturesDir();
        Phase3Fixtures.Expected expected = Phase3Fixtures.loadExpected(fixturesDir.resolve("expected.json"));

        Renderer renderer = new LibreOfficeRenderer();
        FontMap fontMap = FontMap.loadDefault();
        OnboardPipeline onboard = new OnboardPipeline(renderer, fontMap);
        Path outDir = Files.createTempDirectory("blocks-fixture-test");
        byte[] upload = Files.readAllBytes(fixturesDir.resolve("projects_synthetic.docx"));
        OnboardReport onboardReport = onboard.run(upload, outDir);
        assertTrue(onboardReport.accepted(), "fixture failed to onboard: " + onboardReport.reason());

        BlocksReport report = BlocksAnalyzer.analyze(outDir.resolve("normalized.docx"));

        assertEquals(expected.sections().size(), report.sections().size(), "section count");
        for (int i = 0; i < expected.sections().size(); i++) {
            assertEquals(expected.sections().get(i).heading(), report.sections().get(i).heading(), "section[" + i + "].heading");
            assertEquals(expected.sections().get(i).role(), report.sections().get(i).role(), "section[" + i + "].role");
        }
        assertEquals(expected.projectsSection(), report.projectsSection(), "projects_section");

        assertEquals(expected.positions().size(), report.positions().size(), "position count");
        for (int i = 0; i < expected.positions().size(); i++) {
            assertPositionMatches("position[" + i + "]", expected.positions().get(i), report.positions().get(i));
        }
    }

    static void assertPositionMatches(String label, Phase3Fixtures.ExpectedPosition exp, Position got) {
        assertEquals(exp.kind(), got.kind(), label + ".kind");
        assertEquals(exp.swappable(), got.swappable(), label + ".swappable");
        assertEquals(exp.bullets(), got.bullets(), label + ".bullets");
        if (!exp.swappable()) {
            assertEquals(exp.reason(), got.reason(), label + ".reason");
        }
        if ("inline".equals(exp.kind())) {
            assertEquals(exp.segmentsPerBullet(), got.segmentsPerBullet(), label + ".segments_per_bullet");
        }
        if ("paragraph".equals(exp.kind()) && exp.swappable()) {
            assertNotNull(got.header(), label + ".header should be present");
            assertEquals(exp.header().fields(), got.header().fields(), label + ".header.fields");
            assertEquals(exp.header().detailIsList(), got.header().detailIsList(), label + ".header.detail_is_list");
            assertEquals(exp.header().links(), got.header().links(), label + ".header.links");
            assertEquals(exp.header().dateMode(), got.header().dateMode(), label + ".header.date_mode");
        }
    }
}
