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
        TargetBuilder.SectionTarget target = TargetBuilder.forJob(job0, report, positions, "DETAILED");
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

        assertEquals("GENERATED", result.status(), "status");
        assertEquals(expectedJob0.slotLineCounts(), result.slotLineCounts(), "slot line counts");
        assertEquals(expectedJob0.rounds(), result.rounds(), "rounds");
        assertEquals(expectedJob0.calls(), result.callUsages().size(), "calls (revision 7: one model call)");

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

        for (FitLoop.Attempt a : result.attempts()) {
            assertTrue(a.text() != null && !a.text().isBlank() && !a.text().contains("<UNKNOWN>"),
                    "attempt " + a.candidateId() + " must log its real text");
        }
    }

    /**
     * P4-T9 (PHASE4_SPEC.md section 9): replaying job-1's two recorded empty responses gives
     * {@code NO_OUTPUT} after exactly 2 calls, and the section produces no candidates (stays
     * locked).
     */
    @Test
    void emptyJobOneResponsesTwiceGivesNoOutputAfterExactlyTwoCalls() throws Exception {
        Path fixturesDir = CorpusPaths.phase4FixturesDir();
        Renderer renderer = new LibreOfficeRenderer();
        OnboardPipeline onboard = new OnboardPipeline(renderer, FontMap.loadDefault());
        Path outDir = Files.createTempDirectory("fit-loop-jane-doe-job1");
        byte[] upload = Files.readAllBytes(CorpusPaths.phase2FixturesDir().resolve("ok_synthetic.docx"));
        OnboardReport report = onboard.run(upload, outDir);
        assertTrue(report.accepted(), "Jane Doe fixture failed to onboard: " + report.reason());
        Path normalizedDocx = outDir.resolve("normalized.docx");

        SectionPositions positions = SectionPositions.detect(normalizedDocx, report);
        Position job1 = positions.jobPositions().get(1);
        TargetBuilder.SectionTarget target = TargetBuilder.forJob(job1, report, positions, "EXISTING_ONLY");
        assertEquals(List.of(1, 2), target.lineCounts());
        assertEquals(3, target.candidateCount(), "EXISTING_ONLY: one candidate per editable bullet");

        Map<Integer, OnboardReport.SlotReport> bySlotIndex = new HashMap<>();
        for (OnboardReport.SlotReport sr : report.slots()) {
            bySlotIndex.put(sr.index(), sr);
        }
        List<String> currentBullets = new ArrayList<>();
        List<Integer> slotLineCounts = new ArrayList<>();
        for (int idx : positions.bulletSlotIndices(job1)) {
            currentBullets.add(bySlotIndex.get(idx).text());
            slotLineCounts.add(bySlotIndex.get(idx).lines());
        }
        List<String> sourceTexts = new ArrayList<>(currentBullets);
        sourceTexts.add("Software Engineer | Contoso Health");
        sourceTexts.add("Jun 2020 – Dec 2022");

        SectionContext ctx = new SectionContext(
                "job-1", "job", "EXISTING_ONLY",
                List.of(new PromptBuilder.FieldLine("title", "Software Engineer | Contoso Health"),
                        new PromptBuilder.FieldLine("date", "Jun 2020 – Dec 2022")),
                currentBullets, "", sourceTexts,
                target.lineCounts(), slotLineCounts, target.candidateCount(), target.budgetCharsByLineCount(),
                positions.slotIndicesByLineCount(job1, report), normalizedDocx, renderer);

        SkillsDictionary skills = SkillsDictionary.load(fixturesDir.resolve("skills_seed.json"));
        Map<String, List<ModelResponse>> recorded =
                RecordedResponses.load(fixturesDir.resolve("recorded_responses.json"));
        RecordedModelClient client = new RecordedModelClient(recorded.get("job-1"));

        FitLoop.FitLoopResult result = FitLoop.run(ctx, client, skills);

        assertEquals("NO_OUTPUT", result.status());
        assertEquals(2, result.callUsages().size(), "exactly 2 calls");
        assertTrue(result.finalResults().isEmpty(), "no candidates: the section stays locked");
    }
}
