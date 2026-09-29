package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link Shapes#measure} (used by the real {@code tailor match} CLI, unlike the other Phase 5
 * tests which read the fixture's static {@code shapes.json}) reproduces exactly the same
 * per-bullet line counts and {@code shows_detail} flags shapes.json records as "measured on the
 * normalized fixtures/phase3/projects_synthetic.docx" — job id and position ids differ (the
 * fixture uses "northwind"/P0../P3; live measurement uses the production "job-0"/P0../P3
 * convention), so only the shapes themselves are compared.
 */
@Tag("corpus")
class LiveShapesCorpusTest {

    @Test
    void measuredShapesMatchTheFixtureShapesJson() throws Exception {
        Phase5TestSetup s = Phase5TestSetup.load();

        Renderer renderer = new LibreOfficeRenderer();
        FontMap fontMap = FontMap.loadDefault();
        OnboardPipeline onboard = new OnboardPipeline(renderer, fontMap);
        Path outDir = Files.createTempDirectory("live-shapes-onboard");
        byte[] upload = Files.readAllBytes(CorpusPaths.phase3FixturesDir().resolve("projects_synthetic.docx"));
        OnboardReport report = onboard.run(upload, outDir);
        assertTrue(report.accepted(), "fixture failed to onboard: " + report.reason());
        Path normalizedDocx = outDir.resolve("normalized.docx");

        Shapes measured = Shapes.measure(normalizedDocx, report);

        assertEquals(s.shapes.job().slots(), measured.job().slots(), "job slots");
        assertEquals(toShapeList(s.shapes.positions()), toShapeList(measured.positions()), "swappable positions");
        assertEquals(toShapeList(s.shapes.lockedPositions()), toShapeList(measured.lockedPositions()), "locked positions");
        assertEquals(toDetailList(s.shapes.positions()), toDetailList(measured.positions()), "shows_detail");
    }

    private static List<List<Integer>> toShapeList(List<Shapes.PositionShape> positions) {
        return positions.stream().map(Shapes.PositionShape::shape).toList();
    }

    private static List<Boolean> toDetailList(List<Shapes.PositionShape> positions) {
        return positions.stream().map(Shapes.PositionShape::showsDetail).toList();
    }
}
