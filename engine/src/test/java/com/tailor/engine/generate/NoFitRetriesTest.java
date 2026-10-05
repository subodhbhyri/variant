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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * P4-T13 (PHASE4_SPEC.md section 9, revision 7): no retry for a fit or guard failure. A variant
 * that fails the guard or exceeds its budget is dropped for that length with no second call (the
 * recorded client holds only one response, so a retry would throw); an empty response still gets
 * exactly one retry; and a source naming GitHub Actions supports "CI/CD" through the dictionary's
 * implications, while the variant itself is never implied.
 */
@Tag("corpus")
class NoFitRetriesTest {

    @Test
    void guardAndOverBudgetFailuresAreDroppedWithoutASecondCall() throws Exception {
        JobZero f = JobZero.load();
        String firstPerson = "I built a feature-flag service used by six teams, enabling same-day rollbacks.";
        String overBudget = "Led the migration of twelve services from EC2 to Kubernetes, reducing monthly "
                + "infrastructure spend by 22 percent, after moving order processing to Kafka and PostgreSQL, "
                + "which cut p99 latency from 410 ms to 254 ms and let the checkout team ship every day.";

        RecordedModelClient client = new RecordedModelClient(List.of(new ModelResponse(
                List.of(
                        new ModelResponse.BulletCandidate("g1", Map.of("1", firstPerson)),
                        new ModelResponse.BulletCandidate("o1", Map.of("2", overBudget))),
                new ModelResponse.Usage(0, 0, 0, 0))));

        FitLoop.FitLoopResult result = FitLoop.run(f.ctx, client, f.skills);

        assertEquals(1, result.callUsages().size(), "a guard or budget failure must never trigger a second call");
        assertEquals("FIRST_PERSON", result.finalResults().get("g1").reason());
        assertEquals("OVER_BUDGET", result.finalResults().get("o1").reason());
        assertTrue(result.finalResults().values().stream().noneMatch(c -> "OK".equals(c.status())));
    }

    /** A variant within its budget that still renders too long is dropped at that length -- no
     * second call. Budgets are widened so the render check, not OVER_BUDGET, is what fails it:
     * this is the round-1 c3 text from recorded_responses.json, which rendered on 2 lines at a
     * 1-line target. */
    @Test
    void aTooLongRenderIsDroppedWithoutASecondCall() throws Exception {
        JobZero f = JobZero.load(Map.of(1, 1000, 2, 1000));
        String tooLong = "Built the feature-flag service that six teams use, which lets them roll back the same day "
                + "without any redeploys.";

        RecordedModelClient client = new RecordedModelClient(List.of(new ModelResponse(
                List.of(new ModelResponse.BulletCandidate("c3", Map.of("1", tooLong))),
                new ModelResponse.Usage(0, 0, 0, 0))));

        FitLoop.FitLoopResult result = FitLoop.run(f.ctx, client, f.skills);

        assertEquals(1, result.callUsages().size(), "a too-long render must never trigger a second call");
        assertEquals("TOO_LONG", result.finalResults().get("c3").reason());
        FitLoop.Attempt attempt = result.attempts().stream().filter(a -> "c3".equals(a.candidateId())).findFirst()
                .orElseThrow();
        assertEquals("TOO_LONG", attempt.outcome());
        assertEquals(2, attempt.renderedLines());
    }

    @Test
    void anEmptyResponseGetsExactlyOneRetry() throws Exception {
        JobZero f = JobZero.load();
        String kept = "Designed an event-driven order pipeline on Kafka and Postgres, cutting p99 checkout latency "
                + "by 38% from 410 ms to 254 ms at 4K requests per second.";

        RecordedModelClient client = new RecordedModelClient(List.of(
                new ModelResponse(List.of(), new ModelResponse.Usage(0, 0, 0, 0)),
                new ModelResponse(List.of(new ModelResponse.BulletCandidate("c1", Map.of("2", kept))),
                        new ModelResponse.Usage(0, 0, 0, 0))));

        FitLoop.FitLoopResult result = FitLoop.run(f.ctx, client, f.skills);

        assertEquals("GENERATED", result.status());
        assertEquals(2, result.callUsages().size(), "the empty-output retry, and only that one");
        assertEquals(Map.of("2", kept), result.finalResults().get("c1").variants());
    }

    /** PHASE4_SPEC.md section 5 (revision 7): implications apply to the sources, not the variant. */
    @Test
    void aSourceNamingGitHubActionsSupportsCiCdButTheVariantIsNeverImplied() throws Exception {
        SkillsDictionary dict = SkillsDictionary.loadDefault();
        List<String> sources = List.of("Built and ran GitHub Actions pipelines for every service.");

        assertEquals(List.of(), TruthfulnessGuard.guard("Ran CI/CD pipelines for every service.", sources, null, dict),
                "CI/CD is implied by a source naming GitHub Actions");

        List<String> noSourceForCiCd = List.of("Built and ran pipelines for every service.");
        assertTrue(TruthfulnessGuard.guard("Ran CI/CD pipelines for every service.", noSourceForCiCd, null, dict)
                .contains("UNSUPPORTED_TECH:CI/CD"), "without the implying source, CI/CD is unsupported");
    }

    /** Shared setup: Jane Doe job-0's real SectionContext, as in {@code ConsistencyTest}. */
    private record JobZero(SectionContext ctx, SkillsDictionary skills) {
        static JobZero load() throws Exception {
            return load(null);
        }

        static JobZero load(Map<Integer, Integer> budgetOverride) throws Exception {
            Path fixturesDir = CorpusPaths.phase4FixturesDir();
            Renderer renderer = new LibreOfficeRenderer();
            OnboardPipeline onboard = new OnboardPipeline(renderer, FontMap.loadDefault());
            Path outDir = Files.createTempDirectory("no-fit-retries-jane-doe");
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
                    target.lineCounts(), slotLineCounts, target.candidateCount(), (budgetOverride == null ? target.budgetCharsByLineCount() : budgetOverride),
                    positions.slotIndicesByLineCount(job0, report), normalizedDocx, renderer);

            return new JobZero(ctx, SkillsDictionary.load(fixturesDir.resolve("skills_seed.json")));
        }
    }
}
