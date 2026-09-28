package com.tailor.engine.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.blocks.Position;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.golden.Phase4Fixtures;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * P4-T3 (PHASE4_SPEC.md section 9): replaying fixtures/phase4/recorded_responses.json for
 * Jane Doe's job-0 must give exactly fixtures/phase4/expected_generation.json's job-0 entry —
 * final statuses and texts, rounds = 3, round-1 feedback reasons and measured lines.
 */
@Tag("corpus")
class FitLoopTest {

    @Test
    void replayingJobZeroMatchesExpectedGeneration() throws Exception {
        Path fixturesDir = CorpusPaths.phase4FixturesDir();
        Renderer renderer = new LibreOfficeRenderer();
        OnboardPipeline onboard = new OnboardPipeline(renderer, FontMap.loadDefault());
        Path outDir = Files.createTempDirectory("fit-loop-jane-doe");
        byte[] upload = Files.readAllBytes(CorpusPaths.phase2FixturesDir().resolve("ok_synthetic.docx"));
        OnboardReport report = onboard.run(upload, outDir);
        assertTrue(report.accepted(), "Jane Doe fixture failed to onboard: " + report.reason());
        Path normalizedDocx = outDir.resolve("normalized.docx");

        SectionPositions positions = SectionPositions.detect(normalizedDocx, report);
        Position job0 = positions.jobPositions().get(0);
        TargetBuilder.SectionTarget target = TargetBuilder.forJob(job0, report, positions);
        assertEquals(List.of(1, 2), target.lineCounts());
        assertEquals(6, target.candidateCount());

        Map<Integer, OnboardReport.SlotReport> bySlotIndex = new HashMap<>();
        for (OnboardReport.SlotReport sr : report.slots()) {
            bySlotIndex.put(sr.index(), sr);
        }
        List<String> currentBullets = new ArrayList<>();
        List<Integer> slotLineCounts = new ArrayList<>();
        for (int idx : positions.bulletSlotIndices(job0)) {
            currentBullets.add(bySlotIndex.get(idx).text());
            slotLineCounts.add(bySlotIndex.get(idx).lines());
        }
        String rawText = "Worked on checkout: moved order processing to Kafka + PostgreSQL, p99 latency went "
                + "from 410 ms to 254 ms at 4K requests per second (38% lower). Ran the K8s migration of twelve "
                + "services off EC2; infra spend fell 22%. Built the feature-flag service six teams use; "
                + "same-day rollbacks, no redeploys.";
        List<String> sourceTexts = new ArrayList<>(currentBullets);
        sourceTexts.add(rawText);
        sourceTexts.add("Senior Software Engineer | Northwind Labs");
        sourceTexts.add("Jan 2023 – Present");

        SectionContext ctx = new SectionContext(
                "job-0", "job", "DETAILED",
                List.of(new PromptBuilder.FieldLine("title", "Senior Software Engineer | Northwind Labs"),
                        new PromptBuilder.FieldLine("date", "Jan 2023 – Present")),
                currentBullets, rawText, sourceTexts,
                target.lineCounts(), slotLineCounts, target.candidateCount(), target.budgetCharsByLineCount(),
                positions.slotIndicesByLineCount(job0, report), normalizedDocx, renderer);

        SkillsDictionary skills = SkillsDictionary.load(fixturesDir.resolve("skills_seed.json"));
        Map<String, List<ModelResponse>> recorded =
                RecordedResponses.load(fixturesDir.resolve("recorded_responses.json"));
        RecordedModelClient client = new RecordedModelClient(recorded.get("job-0"));

        FitLoop.FitLoopResult result = FitLoop.run(ctx, client, skills);

        Map<String, Phase4Fixtures.ExpectedSection> expected =
                Phase4Fixtures.loadExpectedGeneration(fixturesDir.resolve("expected_generation.json"));
        Phase4Fixtures.ExpectedSection expectedJob0 = expected.get("job-0");

        assertEquals(expectedJob0.slotLineCounts(), result.slotLineCounts(), "slot line counts");
        assertEquals(expectedJob0.rounds(), result.rounds(), "rounds");

        for (Map.Entry<String, Phase4Fixtures.ExpectedCandidate> e : expectedJob0.finalResults().entrySet()) {
            String id = e.getKey();
            Phase4Fixtures.ExpectedCandidate exp = e.getValue();
            FitLoop.CandidateOutcome got = result.finalResults().get(id);
            assertEquals(exp.status(), got.status(), id + " status");
            assertEquals(exp.variants(), got.variants(), id + " variants");
            assertEquals(exp.acceptedInRound(), got.acceptedInRound(), id + " accepted_in_round");
            assertEquals(exp.reason(), got.reason(), id + " reason");
            assertEquals(exp.attempts(), got.attempts(), id + " attempts");
        }
        assertEquals(expectedJob0.finalResults().keySet(), result.finalResults().keySet(), "candidate ids");

        for (Map.Entry<String, Phase4Fixtures.ExpectedFeedback> e : expectedJob0.round1Feedback().entrySet()) {
            String id = e.getKey();
            Phase4Fixtures.ExpectedFeedback exp = e.getValue();
            FitLoop.RoundOneFeedback got = result.round1Feedback().get(id);
            assertEquals(exp.reason(), got.reason(), id + " round1 reason");
            assertEquals(exp.targetLines(), got.targetLines(), id + " round1 target_lines");
            assertEquals(exp.measuredLines(), got.measuredLines(), id + " round1 measured_lines");
        }
        assertEquals(expectedJob0.round1Feedback().keySet(), result.round1Feedback().keySet(), "round1 feedback ids");
    }
}
