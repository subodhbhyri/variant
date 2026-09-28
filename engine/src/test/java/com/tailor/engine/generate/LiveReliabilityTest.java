package com.tailor.engine.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailor.engine.CorpusPaths;
import com.tailor.engine.blocks.Position;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * P4-T11 (PHASE4_SPEC.md section 9, revision 6): runs Jane Doe's job-0 live 20 times. Pass: 0
 * {@code NO_OUTPUT}, the empty-output retry never fires (every run's first call already returns
 * at least one candidate), and every kept variant passes the guard and fits (both already
 * guaranteed by {@link FitLoop}'s own invariants, re-verified here explicitly). Reports per run:
 * candidates returned, kept, dropped with reasons, lowest grounding, calls, cost; runs that
 * return exactly one candidate are called out separately — and, only for a {@code NO_OUTPUT} run,
 * the model's own text blocks and {@code stop_reason} (never captured for real user input:
 * {@link DiagnosticClient} is test-only code, used only against this fixture).
 *
 * <p>Live, opt-in, costs real money — {@code @Tag("live")}, excluded from {@code test} and
 * {@code corpusTest}; run explicitly, e.g. {@code gradle :engine:liveTest --tests
 * "*.LiveReliabilityTest"}.
 */
@Tag("live")
class LiveReliabilityTest {

    private static final int RUNS = 20;

    @Test
    void job0Runs20TimesLiveWithZeroNoOutputAndNoEmptyOutputRetry() throws Exception {
        Path fixturesDir = CorpusPaths.phase4FixturesDir();
        Renderer renderer = new LibreOfficeRenderer();
        OnboardPipeline onboard = new OnboardPipeline(renderer, FontMap.loadDefault());
        Path outDir = Files.createTempDirectory("live-reliability-jane-doe");
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
        PriceTable prices = PriceTable.loadDefault();

        StringBuilder report10 = new StringBuilder();
        StringBuilder callLog = new StringBuilder();
        int noOutputCount = 0;
        StringBuilder noOutputDiagnostics = new StringBuilder();
        StringBuilder guardFailures = new StringBuilder();
        List<Integer> failedRunNumbers = new ArrayList<>();
        List<Integer> emptyOutputRetryFiredRuns = new ArrayList<>();
        List<Integer> singleCandidateRuns = new ArrayList<>();

        for (int run = 1; run <= RUNS; run++) {
            DiagnosticClient client = new DiagnosticClient();
            FitLoop.FitLoopResult result = FitLoop.run(ctx, client, skills);

            Set<String> candidatesReturned = new HashSet<>();
            for (DiagnosticClient.CallDiagnostic call : client.calls) {
                candidatesReturned.addAll(call.candidateIds());
            }

            // PHASE4_SPEC.md revision 6: "the empty-output retry never fires" — FitLoop's own
            // built-in retry (section 3.5) only fires when the very first call comes back with no
            // candidates at all, so that's exactly what the first call's candidateIds tells us.
            if (!client.calls.isEmpty() && client.calls.get(0).candidateIds().isEmpty()) {
                emptyOutputRetryFiredRuns.add(run);
            }
            if (candidatesReturned.size() == 1) {
                singleCandidateRuns.add(run);
            }

            for (int i = 0; i < client.calls.size(); i++) {
                DiagnosticClient.CallDiagnostic call = client.calls.get(i);
                callLog.append(String.format(
                        "run %2d call %d: retry=%-5s status=%d id=%s output_tokens=%d stop_reason=%s "
                                + "candidates=%d raw_tool_input=%s%n",
                        run, i + 1, call.isRetryCall(), call.httpStatus(), call.responseId(), call.outputTokens(),
                        call.stopReason(), call.candidateIds().size(), call.rawToolInput()));
            }

            int kept = 0;
            double lowestGrounding = Double.NaN;
            List<String> droppedDescriptions = new ArrayList<>();
            for (Map.Entry<String, FitLoop.CandidateOutcome> e : result.finalResults().entrySet()) {
                FitLoop.CandidateOutcome outcome = e.getValue();
                if ("OK".equals(outcome.status())) {
                    kept++;
                    for (String text : outcome.variants().values()) {
                        List<String> guardReasons = TruthfulnessGuard.guard(text, ctx.sourceTexts(), null, skills);
                        if (!guardReasons.isEmpty()) {
                            guardFailures.append("run ").append(run).append(" candidate ").append(e.getKey())
                                    .append(": kept but guard now says ").append(guardReasons).append('\n');
                        }
                        double g = TruthfulnessGuard.grounding(text, ctx.sourceTexts());
                        if (Double.isNaN(lowestGrounding) || g < lowestGrounding) {
                            lowestGrounding = g;
                        }
                    }
                } else {
                    droppedDescriptions.add(e.getKey() + "=" + outcome.reason());
                }
            }

            double cost = 0;
            for (ModelResponse.Usage u : result.callUsages()) {
                cost += prices.costUsd(u);
            }

            report10.append(String.format(
                    "run %2d: status=%-10s calls=%d returned=%d kept=%d dropped=%s lowest_grounding=%s cost_usd=%.5f%n",
                    run, result.status(), result.callUsages().size(), candidatesReturned.size(), kept,
                    droppedDescriptions, Double.isNaN(lowestGrounding) ? "n/a" : String.format("%.4f", lowestGrounding),
                    cost));

            if ("NO_OUTPUT".equals(result.status())) {
                noOutputCount++;
                failedRunNumbers.add(run);
                noOutputDiagnostics.append("--- run ").append(run).append(" (NO_OUTPUT) ---\n");
                for (int i = 0; i < client.calls.size(); i++) {
                    DiagnosticClient.CallDiagnostic call = client.calls.get(i);
                    noOutputDiagnostics.append("  call ").append(i + 1).append(": retry=").append(call.isRetryCall())
                            .append(" status=").append(call.httpStatus()).append(" id=").append(call.responseId())
                            .append(" output_tokens=").append(call.outputTokens())
                            .append(" stop_reason=").append(call.stopReason())
                            .append(" text_blocks=").append(call.textBlocks())
                            .append(" raw_tool_input=").append(call.rawToolInput()).append('\n');
                }
            }
        }

        System.out.println("P4-T11 live reliability report (job-0, " + RUNS + " runs):");
        System.out.println(report10);
        System.out.println("Per-call diagnostic log (root-cause investigation, revision 6):");
        System.out.println(callLog);
        if (noOutputCount > 0) {
            System.out.println("NO_OUTPUT diagnostics:");
            System.out.println(noOutputDiagnostics);
        }
        System.out.println("Root-cause questions:");
        System.out.println("(a) failed run numbers: " + failedRunNumbers);
        System.out.println("(c) every failed call's retry/state fields are logged above per-call; "
                + "inspect for shared response ids, statuses, or retry-flag coincidence.");
        System.out.println("Runs where the empty-output retry fired: " + emptyOutputRetryFiredRuns);
        System.out.println("Runs with exactly one candidate: " + singleCandidateRuns);

        assertTrue(guardFailures.isEmpty(), "kept variant failed a guard re-check:\n" + guardFailures);
        assertEquals(0, noOutputCount, "P4-T11 requires 0 NO_OUTPUT runs out of " + RUNS + ":\n" + noOutputDiagnostics);
        assertTrue(emptyOutputRetryFiredRuns.isEmpty(),
                "P4-T11 (revision 6) requires the empty-output retry to never fire; it fired on runs "
                        + emptyOutputRetryFiredRuns);
    }

    /**
     * Test-only client for P4-T11: calls the real API directly (not through {@link
     * AnthropicClient}, which must never capture response text) so this diagnostic can record
     * each call's {@code stop_reason} and any non-tool-use text blocks — only ever run against
     * fixture input, on this one opt-in test.
     */
    private static final class DiagnosticClient implements ModelClient {

        /**
         * Root-cause investigation (revision 6 follow-up): both prior 10-run batches got
         * NO_OUTPUT at exactly runs 3 and 6, which points at something deterministic rather than
         * model randomness. {@code rawToolInput} is the tool_use block's own {@code input} JSON,
         * captured verbatim (not re-derived through {@link AnthropicResponse#parse}) so a parsing
         * bug can be told apart from the API/model genuinely sending an empty array under
         * {@code strict: true}. {@code isRetryCall} flags FitLoop's own built-in empty-output
         * retry (the literal "Return at least one candidate." message), not an HTTP-level retry —
         * this client makes exactly one HTTP request per {@link #call}, no 429/5xx retry path.
         */
        record CallDiagnostic(String stopReason, List<String> textBlocks, Set<String> candidateIds,
                String rawToolInput, int outputTokens, String responseId, int httpStatus, boolean isRetryCall) {
        }

        private static final String RETURN_AT_LEAST_ONE = "Return at least one candidate.";

        private final String apiKey;
        private final HttpClient http;
        private final ObjectMapper mapper = new ObjectMapper();
        final List<CallDiagnostic> calls = new ArrayList<>();

        DiagnosticClient() {
            this.apiKey = System.getenv("ANTHROPIC_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("ANTHROPIC_API_KEY is not set");
            }
            this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(60)).build();
        }

        @Override
        public ModelResponse call(String systemPrompt, String userMessage) throws Exception {
            String body = mapper.writeValueAsString(AnthropicRequest.build(systemPrompt, userMessage));
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                    .timeout(Duration.ofSeconds(60))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int httpStatus = response.statusCode();
            if (httpStatus >= 300) {
                throw new IllegalStateException("Anthropic API returned HTTP " + httpStatus);
            }
            JsonNode root = mapper.readTree(response.body());
            ModelResponse parsed = AnthropicResponse.parse(root);

            String stopReason = root.path("stop_reason").isMissingNode() ? null : root.path("stop_reason").asText();
            String responseId = root.path("id").isMissingNode() ? null : root.path("id").asText();
            int outputTokens = root.path("usage").path("output_tokens").asInt(-1);
            List<String> textBlocks = new ArrayList<>();
            Set<String> ids = new LinkedHashSet<>();
            String rawToolInput = null;
            for (JsonNode block : root.path("content")) {
                String type = block.path("type").asText();
                if ("text".equals(type)) {
                    textBlocks.add(block.path("text").asText());
                } else if ("tool_use".equals(type)) {
                    rawToolInput = mapper.writeValueAsString(block.path("input"));
                }
            }
            for (ModelResponse.BulletCandidate bc : parsed.bullets()) {
                ids.add(bc.id());
            }
            boolean isRetryCall = RETURN_AT_LEAST_ONE.equals(userMessage);
            calls.add(new CallDiagnostic(stopReason, textBlocks, ids, rawToolInput, outputTokens, responseId,
                    httpStatus, isRetryCall));
            return parsed;
        }
    }
}
