package com.tailor.engine.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.blocks.Position;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * P4-T2 (PHASE4_SPEC.md section 9): Jane Doe's two jobs both need lengths {1, 2} with 6
 * candidates each (2 x 3 editable slots); the Phase 3 fixture's project section needs
 * max-bullets + 1 (3 + 1 = 4) bullets per candidate. Budgets are cross-checked against an
 * independently computed median of the same calibration hints.
 */
@Tag("corpus")
class TargetBuilderTest {

    @Test
    void janeDoeBothJobsNeedLengths1And2With6Candidates() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        OnboardPipeline onboard = new OnboardPipeline(renderer, FontMap.loadDefault());
        Path outDir = Files.createTempDirectory("target-builder-jane-doe");
        byte[] upload = Files.readAllBytes(CorpusPaths.phase2FixturesDir().resolve("ok_synthetic.docx"));
        OnboardReport report = onboard.run(upload, outDir);
        assertTrue(report.accepted(), "Jane Doe fixture failed to onboard: " + report.reason());
        Path normalizedDocx = outDir.resolve("normalized.docx");

        SectionPositions positions = SectionPositions.detect(normalizedDocx, report);
        assertEquals(2, positions.jobPositions().size(), "expected 2 job positions");

        for (Position job : positions.jobPositions()) {
            TargetBuilder.SectionTarget target = TargetBuilder.forJob(job, report, positions);
            assertEquals(List.of(1, 2), target.lineCounts(), "job line counts");
            assertEquals(6, target.candidateCount(), "job candidate count (2 x 3 editable slots)");
            assertBudgetsAreMedianHints(target, job, report, positions);
        }
    }

    @Test
    void fixtureProjectSectionNeedsMaxBulletsPlusOne() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        OnboardPipeline onboard = new OnboardPipeline(renderer, FontMap.loadDefault());
        Path fixturesDir = CorpusPaths.phase3FixturesDir();
        Path outDir = Files.createTempDirectory("target-builder-phase3-projects");
        byte[] upload = Files.readAllBytes(fixturesDir.resolve("projects_synthetic.docx"));
        OnboardReport report = onboard.run(upload, outDir);
        assertTrue(report.accepted(), "phase3 fixture failed to onboard: " + report.reason());
        Path normalizedDocx = outDir.resolve("normalized.docx");

        SectionPositions positions = SectionPositions.detect(normalizedDocx, report);
        TargetBuilder.SectionTarget target = TargetBuilder.forProjects(report, positions);

        // fixtures/phase3/expected.json: swappable positions have bullets 3, 2, 2, 3 (max 3) -> 3 + 1.
        assertEquals(4, target.candidateCount(), "bullets per project (max swappable bullets + 1)");
    }

    private static void assertBudgetsAreMedianHints(TargetBuilder.SectionTarget target, Position job,
            OnboardReport report, SectionPositions positions) {
        Map<Integer, OnboardReport.SlotReport> bySlotIndex = new java.util.HashMap<>();
        for (OnboardReport.SlotReport sr : report.slots()) {
            bySlotIndex.put(sr.index(), sr);
        }
        for (int lineCount : target.lineCounts()) {
            List<Integer> hints = new java.util.ArrayList<>();
            for (int idx : positions.bulletSlotIndices(job)) {
                OnboardReport.SlotReport sr = bySlotIndex.get(idx);
                if (sr != null && sr.editable() && Integer.valueOf(lineCount).equals(sr.lines())
                        && sr.hintChars() != null) {
                    hints.add(sr.hintChars());
                }
            }
            if (hints.isEmpty()) {
                continue;
            }
            java.util.Collections.sort(hints);
            int n = hints.size();
            int expectedMedian = n % 2 == 1 ? hints.get(n / 2)
                    : (int) Math.round((hints.get(n / 2 - 1) + hints.get(n / 2)) / 2.0);
            assertEquals(expectedMedian, target.budgetCharsByLineCount().get(lineCount),
                    "median budget for " + lineCount + "-line variants");
        }
    }
}
