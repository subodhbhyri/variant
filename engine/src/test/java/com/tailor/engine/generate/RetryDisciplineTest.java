package com.tailor.engine.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * P4-T12 (PHASE4_SPEC.md section 9, revision 6): a retried variant whose content words are less
 * than 60% grounded in its own previous attempt (same candidate and length) rewrites instead of
 * shortening, and is rejected as {@code REWRITTEN} — never compared against the original source
 * material, only against what the candidate itself said last time. The attempts log records
 * every attempt in order with its outcome and the feedback sent; a rejected length still counts
 * toward the 3-round budget like any other failure. {@code FitLoopTest}'s replay of job-0's own
 * recorded retries (none of which rewrite) confirms this doesn't flag a legitimate shortening.
 */
@Tag("corpus")
class RetryDisciplineTest {

    @Test
    void aRetryThatRewritesInsteadOfShorteningIsRejectedAndLogged() throws Exception {
        Path fixturesDir = CorpusPaths.phase4FixturesDir();
        Renderer renderer = new LibreOfficeRenderer();
        OnboardPipeline onboard = new OnboardPipeline(renderer, FontMap.loadDefault());
        Path outDir = Files.createTempDirectory("retry-discipline-jane-doe");
        byte[] upload = Files.readAllBytes(CorpusPaths.phase2FixturesDir().resolve("ok_synthetic.docx"));
        OnboardReport report = onboard.run(upload, outDir);
        assertTrue(report.accepted(), "Jane Doe fixture failed to onboard: " + report.reason());
        Path normalizedDocx = outDir.resolve("normalized.docx");

        SectionPositions positions = SectionPositions.detect(normalizedDocx, report);
        Position job0 = positions.jobPositions().get(0);
        TargetBuilder.SectionTarget target = TargetBuilder.forJob(job0, report, positions, "DETAILED");

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

        // Round 1: "I built ..." fails the guard (FIRST_PERSON) -- a real fact, just badly worded,
        // so round 2 is a genuine retry rather than something already dead for another reason.
        String round1Text = "I built a feature-flag service used by six teams, enabling same-day "
                + "rollbacks without redeploys.";
        // Round 2: an unrelated fact -- a rewrite, not a shortening, of round 1's own attempt.
        String round2Text = "Migrated the billing pipeline to Kafka and Postgres, cutting p99 latency "
                + "by 38 percent.";

        ModelResponse round1 = new ModelResponse(
                List.of(new ModelResponse.BulletCandidate("x1", Map.of("2", round1Text))),
                new ModelResponse.Usage(0, 0, 0, 0));
        ModelResponse round2 = new ModelResponse(
                List.of(new ModelResponse.BulletCandidate("x1", Map.of("2", round2Text))),
                new ModelResponse.Usage(0, 0, 0, 0));
        ModelResponse round3 = new ModelResponse(List.of(), new ModelResponse.Usage(0, 0, 0, 0));
        RecordedModelClient client = new RecordedModelClient(List.of(round1, round2, round3));

        FitLoop.FitLoopResult result = FitLoop.run(ctx, client, skills);

        assertEquals("GENERATED", result.status());
        assertEquals(3, result.rounds(), "a still-failing length must use the full 3-round budget");
        assertEquals(3, result.callUsages().size(), "round 1, the retry, and the round-3 call that got nothing back");

        FitLoop.CandidateOutcome x1 = result.finalResults().get("x1");
        assertEquals("DROPPED", x1.status(), "its only length never produced an acceptable attempt");
        assertEquals("REWRITTEN", x1.reason());

        List<FitLoop.Attempt> x1Attempts = result.attempts().stream()
                .filter(a -> "x1".equals(a.candidateId())).toList();
        assertEquals(2, x1Attempts.size(), "round 3 returned nothing, so there's no third attempt to log");

        FitLoop.Attempt first = x1Attempts.get(0);
        assertEquals(1, first.round());
        assertEquals(round1Text, first.text());
        assertEquals("FIRST_PERSON", first.outcome());
        assertNotNull(first.feedbackSent());

        FitLoop.Attempt second = x1Attempts.get(1);
        assertEquals(2, second.round());
        assertEquals(round2Text, second.text());
        assertEquals("REWRITTEN", second.outcome());
        assertNotNull(second.reworkScore());
        assertTrue(second.reworkScore() < 0.60, "round 2 must score well below the 0.60 threshold: got "
                + second.reworkScore());
        assertNotNull(second.feedbackSent());
    }
}
