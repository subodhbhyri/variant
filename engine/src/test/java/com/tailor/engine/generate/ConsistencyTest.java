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
 * P4-T10 (PHASE4_SPEC.md section 9): sibling consistency (5.1). {@code consistency_cases.json}'s
 * pairs match their expected verdict/score exactly; a fit-loop candidate whose longer version is
 * inconsistent with its shorter one drops only the longer version; a too-short render is kept as
 * its own shorter length or dropped, never sent back for a retry.
 */
@Tag("corpus")
class ConsistencyTest {

    /** Offline: fixtures/phase4/consistency_cases.json against {@code TruthfulnessGuard}
     * directly. */
    @Test
    void consistencyCasesMatchExpectedVerdictsAndScores() throws Exception {
        Phase4Fixtures.ConsistencyCases fixture = Phase4Fixtures.loadConsistencyCases(
                CorpusPaths.phase4FixturesDir().resolve("consistency_cases.json"));

        StringBuilder failures = new StringBuilder();
        for (Phase4Fixtures.ConsistencyCase c : fixture.pairs()) {
            boolean gotConsistent = TruthfulnessGuard.consistent(c.shorter(), c.longer());
            double gotScore = TruthfulnessGuard.grounding(c.shorter(), List.of(c.longer()));
            if (gotConsistent != c.consistent()) {
                failures.append(c.name()).append(": expected consistent=").append(c.consistent())
                        .append(", got ").append(gotConsistent).append('\n');
            }
            if (Math.abs(gotScore - c.score()) > 0.01) {
                failures.append(c.name()).append(": expected score=").append(c.score())
                        .append(", got ").append(gotScore).append('\n');
            }
        }
        assertTrue(failures.isEmpty(), "P4-T10 failures:\n" + failures);
    }

    /** Render-based: a candidate whose longer version is inconsistent with its shorter one keeps
     * only the shorter (INCONSISTENT_VARIANTS drops just the longer version, not the candidate).
     * Reuses Jane Doe job-0's own two faithful bullets (feature-flag / k8s migration) as the
     * "sibling" pair — both are individually truthful against job-0's sources, but describe
     * different achievements, so they fail sibling consistency against each other exactly like
     * the live fabrication did. */
    @Test
    void inconsistentLongerVersionIsDroppedKeepingTheShorter() throws Exception {
        JobZeroFixture f = JobZeroFixture.load();
        String shorterText = "Built a feature-flag service used by six teams, enabling same-day rollbacks without redeploys.";
        String longerText = "Led the migration of twelve services from EC2 to Kubernetes, reducing monthly infrastructure spend by 22%.";

        RecordedModelClient client = new RecordedModelClient(List.of(new ModelResponse(
                List.of(new ModelResponse.BulletCandidate("x2", Map.of("1", shorterText, "2", longerText))),
                new ModelResponse.Usage(0, 0, 0, 0))));

        FitLoop.FitLoopResult result = FitLoop.run(f.ctx, client, f.skills);

        assertEquals("GENERATED", result.status());
        FitLoop.CandidateOutcome x2 = result.finalResults().get("x2");
        assertEquals("OK", x2.status());
        assertEquals(Map.of("1", shorterText), x2.variants(), "only the shorter (consistent) version survives");
    }

    /** Render-based: a variant submitted for length 2 that renders on fewer lines (k=1) is kept
     * as its own k-line version and is never retried — a single-response RecordedModelClient
     * would throw if FitLoop tried to call again. */
    @Test
    void tooShortVariantIsReclassifiedAndNeverRetried() throws Exception {
        JobZeroFixture f = JobZeroFixture.load();
        // Job-0's own 1-line bullet, submitted (mislabeled) as a length-2 candidate.
        String shortText = "Built a feature-flag service used by six teams, enabling same-day rollbacks without redeploys.";

        RecordedModelClient client = new RecordedModelClient(List.of(new ModelResponse(
                List.of(new ModelResponse.BulletCandidate("x1", Map.of("2", shortText))),
                new ModelResponse.Usage(0, 0, 0, 0))));

        FitLoop.FitLoopResult result = FitLoop.run(f.ctx, client, f.skills);

        assertEquals("GENERATED", result.status());
        assertEquals(1, result.callUsages().size(), "a too-short render must never trigger a retry call");
        FitLoop.CandidateOutcome x1 = result.finalResults().get("x1");
        assertEquals("OK", x1.status());
        assertEquals(Map.of("1", shortText), x1.variants(), "kept as its own (shorter) real length");
    }

    /** Shared setup: Jane Doe job-0's real SectionContext (DETAILED), for crafting hand-built
     * ModelResponses against real slots/renders without needing new trial-and-error fixture text. */
    private record JobZeroFixture(SectionContext ctx, SkillsDictionary skills) {
        static JobZeroFixture load() throws Exception {
            Path fixturesDir = CorpusPaths.phase4FixturesDir();
            Renderer renderer = new LibreOfficeRenderer();
            OnboardPipeline onboard = new OnboardPipeline(renderer, FontMap.loadDefault());
            Path outDir = Files.createTempDirectory("consistency-jane-doe");
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
            return new JobZeroFixture(ctx, skills);
        }
    }
}
