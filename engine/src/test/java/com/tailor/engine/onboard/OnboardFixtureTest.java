package com.tailor.engine.onboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.golden.Phase2Fixtures;
import com.tailor.engine.golden.Phase2Fixtures.Expected;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** P2-T2 and P2-T3 (PHASE2_SPEC.md section 6), run through the full {@code tailor onboard} pipeline. */
class OnboardFixtureTest {

    @Test
    void acceptedFixturesMatchExpectedEditableAndLocked() throws Exception { // P2-T2
        Path fixturesDir = CorpusPaths.phase2FixturesDir();
        Map<String, Expected> expected = Phase2Fixtures.load(fixturesDir.resolve("expected.json"));
        OnboardPipeline pipeline = newPipeline();

        StringBuilder failures = new StringBuilder();
        for (String name : List.of("ok_synthetic.docx", "unknown_font.docx", "link_in_bullet.docx", "side_by_side.docx")) {
            Expected exp = expected.get(name);
            OnboardReport report = runOnboard(pipeline, fixturesDir.resolve(name), name);

            if (!report.accepted()) {
                failures.append(name).append(": expected accept, got reason=").append(report.reason()).append('\n');
                continue;
            }
            if (!exp.editable().equals(report.editableCount())) {
                failures.append(name).append(": editable=").append(report.editableCount())
                        .append(" expected=").append(exp.editable()).append('\n');
            }
            Map<String, String> gotLocked = new LinkedHashMap<>();
            for (var s : report.slots()) {
                if (!s.editable()) {
                    gotLocked.put(String.valueOf(s.index()), s.lockReason());
                }
            }
            if (!exp.locked().equals(gotLocked)) {
                failures.append(name).append(": locked=").append(gotLocked)
                        .append(" expected=").append(exp.locked()).append('\n');
            }
            if (exp.lines() != null) {
                for (var e : exp.lines().entrySet()) {
                    int index = Integer.parseInt(e.getKey());
                    Integer got = report.slots().get(index).lines();
                    if (!e.getValue().equals(got)) {
                        failures.append(name).append(": slot ").append(index).append(" lines=").append(got)
                                .append(" expected=").append(e.getValue()).append('\n');
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), "P2-T2 failures:\n" + failures);
    }

    @Test
    void renderStageRejectsMatchExpected() throws Exception { // P2-T3
        Path fixturesDir = CorpusPaths.phase2FixturesDir();
        OnboardPipeline pipeline = newPipeline();

        Map<String, String> expectedReasons = Map.of(
                "too_many_pages.docx", "TOO_MANY_PAGES",
                "too_few_bullets.docx", "TOO_FEW_EDITABLE");

        for (var e : expectedReasons.entrySet()) {
            OnboardReport report = runOnboard(pipeline, fixturesDir.resolve(e.getKey()), e.getKey());
            assertFalse(report.accepted(), e.getKey() + ": expected reject");
            assertEquals(e.getValue(), report.reason(), e.getKey());
        }
    }

    private static OnboardPipeline newPipeline() {
        Renderer renderer = new LibreOfficeRenderer();
        FontMap fontMap = FontMap.loadDefault();
        return new OnboardPipeline(renderer, fontMap);
    }

    private static OnboardReport runOnboard(OnboardPipeline pipeline, Path fixture, String label) throws Exception {
        Path outDir = Files.createTempDirectory("onboard-fixture-" + label);
        byte[] upload = Files.readAllBytes(fixture);
        return pipeline.run(upload, outDir);
    }
}
