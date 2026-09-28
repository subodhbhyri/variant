package com.tailor.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.blocks.LibraryProject;
import com.tailor.engine.blocks.Position;
import com.tailor.engine.generate.AnthropicClient;
import com.tailor.engine.generate.BudgetTracker;
import com.tailor.engine.generate.FitLoop;
import com.tailor.engine.generate.Intake;
import com.tailor.engine.generate.IntakeFields;
import com.tailor.engine.generate.IntakeIO;
import com.tailor.engine.generate.IntakeSection;
import com.tailor.engine.generate.ModelClient;
import com.tailor.engine.generate.ModelResponse;
import com.tailor.engine.generate.PriceTable;
import com.tailor.engine.generate.PromptBuilder;
import com.tailor.engine.generate.RecordedModelClient;
import com.tailor.engine.generate.RecordedResponses;
import com.tailor.engine.generate.SectionContext;
import com.tailor.engine.generate.SectionPositions;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.generate.SlotReports;
import com.tailor.engine.generate.TargetBuilder;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code tailor generate <onboarded.docx> <intake.json> <outDir> [--live] [--recorded <file>]}
 * — PHASE4_SPEC.md section 8. Writes {@code variants.json} (jobs: kept candidates with variants
 * and status; projects: the Phase 3 library format, unchanged input for {@code tailor swap}) and
 * {@code generation-report.json} (per section: rounds, kept/dropped with reasons, tokens, cost).
 */
@Command(name = "generate", description = "Generates bullet/project variants with the model (spec section 8).")
public final class GenerateCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Parameters(index = "0", description = "An already-onboarded (normalized) .docx")
    private Path onboardedPath;

    @Parameters(index = "1", description = "intake.json (spec section 1)")
    private Path intakeJsonPath;

    @Parameters(index = "2", description = "Output directory")
    private Path outDir;

    @Option(names = "--live", description = "Call the real Anthropic API (needs ANTHROPIC_API_KEY).")
    private boolean live;

    @Option(names = "--recorded", description = "recorded_responses.json-shaped file (offline; the default in tests).")
    private Path recordedPath;

    @Override
    public Integer call() {
        try {
            if (!live && recordedPath == null) {
                System.err.println("generate failed: pass --live or --recorded <file>");
                return 1;
            }
            Intake intake = IntakeIO.load(intakeJsonPath);
            Map<String, IntakeSection> sectionsById = new LinkedHashMap<>();
            for (IntakeSection s : intake.sections()) {
                sectionsById.put(s.id(), s);
            }

            Renderer renderer = new LibreOfficeRenderer();
            Path workDir = Files.createTempDirectory("generate-cli");
            List<OnboardReport.SlotReport> slotReports = SlotReports.build(onboardedPath, renderer, workDir);
            int editableCount = (int) slotReports.stream().filter(OnboardReport.SlotReport::editable).count();
            OnboardReport report = OnboardReport.accepted(
                    0, 0.0, 0, 0, 0, List.of(), editableCount, slotReports, renderer.version());
            SectionPositions positions = SectionPositions.detect(onboardedPath, report);
            SkillsDictionary skills = SkillsDictionary.load(skillsSeedPath());

            Map<String, List<ModelResponse>> recorded = live ? null : RecordedResponses.load(recordedPath);
            BudgetTracker tracker = new BudgetTracker(PriceTable.loadDefault());

            Map<String, Map<String, FitLoop.CandidateOutcome>> jobVariants = new LinkedHashMap<>();
            List<LibraryProject> libraryProjects = new ArrayList<>();
            Map<String, SectionReportEntry> reportSections = new LinkedHashMap<>();

            for (int i = 0; i < positions.jobPositions().size(); i++) {
                String sectionId = "job-" + i;
                IntakeSection section = sectionsById.get(sectionId);
                if (section == null || "SKIPPED".equals(section.mode())) {
                    reportSections.put(sectionId, SectionReportEntry.locked("SKIPPED"));
                    continue;
                }
                if (!tracker.canGenerate()) {
                    reportSections.put(sectionId, SectionReportEntry.locked("COST_LIMIT"));
                    continue;
                }
                Position job = positions.jobPositions().get(i);
                TargetBuilder.SectionTarget target = TargetBuilder.forJob(job, report, positions, section.mode());
                List<Integer> slotIndices = positions.bulletSlotIndices(job);
                List<String> currentBullets = textsOf(slotIndices, report);
                List<Integer> slotLineCounts = linesOf(slotIndices, report);
                List<String> sourceTexts = sourceTexts(section, currentBullets);

                SectionContext ctx = new SectionContext(
                        sectionId, "job", section.mode(), fieldLines(section.fields()), currentBullets,
                        section.rawText(), sourceTexts, target.lineCounts(), slotLineCounts,
                        target.candidateCount(), target.budgetCharsByLineCount(),
                        positions.slotIndicesByLineCount(job, report), onboardedPath, renderer);

                ModelClient client = clientFor(sectionId, recorded);
                FitLoop.FitLoopResult result = FitLoop.run(ctx, client, skills);
                tracker.recordAll(result.callUsages());
                jobVariants.put(sectionId, keptOnly(result.finalResults()));
                reportSections.put(sectionId, SectionReportEntry.of(result, tracker.prices()));
            }

            TargetBuilder.SectionTarget projectTarget = TargetBuilder.forProjects(report, positions);
            Map<Integer, List<Integer>> pooledSlots = pooledProjectSlots(report, positions);
            for (IntakeSection section : intake.sections()) {
                if (!"project".equals(section.kind())) {
                    continue;
                }
                if ("SKIPPED".equals(section.mode())) {
                    reportSections.put(section.id(), SectionReportEntry.locked("SKIPPED"));
                    continue;
                }
                if (!tracker.canGenerate()) {
                    reportSections.put(section.id(), SectionReportEntry.locked("COST_LIMIT"));
                    continue;
                }
                List<String> currentBullets = List.of();
                Integer existingIndex = existingProjectIndex(section.id());
                if (existingIndex != null && existingIndex < positions.projectPositions().size()) {
                    Position existing = positions.projectPositions().get(existingIndex);
                    currentBullets = textsOf(positions.bulletSlotIndices(existing), report);
                }
                List<String> sourceTexts = sourceTexts(section, currentBullets);

                SectionContext ctx = new SectionContext(
                        section.id(), "project", section.mode(), fieldLines(section.fields()), currentBullets,
                        section.rawText(), sourceTexts, projectTarget.lineCounts(), List.of(),
                        projectTarget.candidateCount(), projectTarget.budgetCharsByLineCount(), pooledSlots,
                        onboardedPath, renderer);

                ModelClient client = clientFor(section.id(), recorded);
                FitLoop.FitLoopResult result = FitLoop.run(ctx, client, skills);
                tracker.recordAll(result.callUsages());

                IntakeFields fields = section.fields();
                libraryProjects.add(new LibraryProject(
                        section.id(), fields == null ? null : fields.title(), fields == null ? null : fields.detail(),
                        toLibraryLinks(fields), fields == null ? null : fields.date(),
                        keptBulletsInOrder(result.finalResults())));
                reportSections.put(section.id(), SectionReportEntry.of(result, tracker.prices()));
            }

            Files.createDirectories(outDir);
            VariantsOutput variants = new VariantsOutput(jobVariants, new LibraryProject.Library(libraryProjects));
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(outDir.resolve("variants.json").toFile(), variants);
            MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(outDir.resolve("generation-report.json").toFile(), reportSections);

            System.out.printf("generate ok -> %s (calls=%d cost_usd=%.5f)%n",
                    outDir, tracker.callCount(), tracker.totalUsd());
            return 0;
        } catch (Exception e) {
            System.err.println("generate failed: " + e);
            return 1;
        }
    }

    private ModelClient clientFor(String sectionId, Map<String, List<ModelResponse>> recorded) {
        if (live) {
            return new AnthropicClient();
        }
        List<ModelResponse> responses = recorded.get(sectionId);
        if (responses == null) {
            throw new IllegalStateException("no recorded responses for section " + sectionId);
        }
        return new RecordedModelClient(responses);
    }

    private static Path skillsSeedPath() {
        // fixtures/phase4/skills_seed.json — the seed dictionary (PHASE4_SPEC.md section 5);
        // Phase 5 grows it. Resolved the same way CorpusPaths walks up to fixtures/, since this
        // is not (yet) a classpath resource.
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("fixtures/phase4/skills_seed.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("fixtures/phase4/skills_seed.json not found");
    }

    private static List<String> textsOf(List<Integer> slotIndices, OnboardReport report) {
        Map<Integer, OnboardReport.SlotReport> bySlotIndex = bySlotIndex(report);
        List<String> out = new ArrayList<>();
        for (int idx : slotIndices) {
            OnboardReport.SlotReport sr = bySlotIndex.get(idx);
            if (sr != null) {
                out.add(sr.text());
            }
        }
        return out;
    }

    private static List<Integer> linesOf(List<Integer> slotIndices, OnboardReport report) {
        Map<Integer, OnboardReport.SlotReport> bySlotIndex = bySlotIndex(report);
        List<Integer> out = new ArrayList<>();
        for (int idx : slotIndices) {
            OnboardReport.SlotReport sr = bySlotIndex.get(idx);
            out.add(sr == null ? null : sr.lines());
        }
        return out;
    }

    private static Map<Integer, OnboardReport.SlotReport> bySlotIndex(OnboardReport report) {
        Map<Integer, OnboardReport.SlotReport> m = new LinkedHashMap<>();
        for (OnboardReport.SlotReport sr : report.slots()) {
            m.put(sr.index(), sr);
        }
        return m;
    }

    /** PHASE4_SPEC.md section 2 "Sources": DETAILED = raw text + current bullets + field values;
     * EXISTING_ONLY = current bullets + field values only. */
    private static List<String> sourceTexts(IntakeSection section, List<String> currentBullets) {
        List<String> out = new ArrayList<>(currentBullets);
        if (section.fields() != null) {
            IntakeFields f = section.fields();
            if (f.title() != null) {
                out.add(f.title());
            }
            if (f.detail() != null) {
                out.add(f.detail());
            }
            if (f.date() != null) {
                out.add(f.date());
            }
        }
        if (!"EXISTING_ONLY".equals(section.mode()) && section.rawText() != null) {
            out.add(section.rawText());
        }
        return out;
    }

    private static List<PromptBuilder.FieldLine> fieldLines(IntakeFields fields) {
        List<PromptBuilder.FieldLine> lines = new ArrayList<>();
        if (fields == null) {
            return lines;
        }
        if (fields.title() != null) {
            lines.add(new PromptBuilder.FieldLine("title", fields.title()));
        }
        if (fields.detail() != null) {
            lines.add(new PromptBuilder.FieldLine("detail", fields.detail()));
        }
        if (fields.date() != null) {
            lines.add(new PromptBuilder.FieldLine("date", fields.date()));
        }
        if (fields.links() != null) {
            for (IntakeFields.Link link : fields.links()) {
                lines.add(new PromptBuilder.FieldLine("link", link.label() + " (" + link.url() + ")"));
            }
        }
        return lines;
    }

    private static List<LibraryProject.Link> toLibraryLinks(IntakeFields fields) {
        if (fields == null || fields.links() == null) {
            return List.of();
        }
        return fields.links().stream().map(l -> new LibraryProject.Link(l.label(), l.url())).toList();
    }

    /** {@code project-N} follows an existing swappable position (Phase 3 position order,
     * matching {@code job-N}); {@code project-new-N} has none. */
    private static Integer existingProjectIndex(String sectionId) {
        if (!sectionId.startsWith("project-") || sectionId.startsWith("project-new-")) {
            return null;
        }
        try {
            return Integer.parseInt(sectionId.substring("project-".length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Every swappable project position's bullet slots, grouped by line count and pooled
     * together (PHASE4_SPEC.md section 2: "across all swappable project positions"). */
    private static Map<Integer, List<Integer>> pooledProjectSlots(OnboardReport report, SectionPositions positions) {
        Map<Integer, List<Integer>> pooled = new LinkedHashMap<>();
        for (Position p : positions.projectPositions()) {
            if (!p.swappable()) {
                continue;
            }
            for (Map.Entry<Integer, List<Integer>> e : positions.slotIndicesByLineCount(p, report).entrySet()) {
                pooled.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
            }
        }
        return pooled;
    }

    private static Map<String, FitLoop.CandidateOutcome> keptOnly(Map<String, FitLoop.CandidateOutcome> finalResults) {
        Map<String, FitLoop.CandidateOutcome> out = new LinkedHashMap<>();
        for (Map.Entry<String, FitLoop.CandidateOutcome> e : finalResults.entrySet()) {
            if ("OK".equals(e.getValue().status())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static List<Map<String, String>> keptBulletsInOrder(Map<String, FitLoop.CandidateOutcome> finalResults) {
        List<Map<String, String>> out = new ArrayList<>();
        for (FitLoop.CandidateOutcome outcome : finalResults.values()) {
            if ("OK".equals(outcome.status())) {
                out.add(outcome.variants());
            }
        }
        return out;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VariantsOutput(Map<String, Map<String, FitLoop.CandidateOutcome>> jobs, LibraryProject.Library projects) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SectionReportEntry(
            String status, Integer rounds, Map<String, FitLoop.CandidateOutcome> candidates,
            Integer calls, ModelResponse.Usage tokens, Double costUsd) {

        static SectionReportEntry locked(String status) {
            return new SectionReportEntry(status, null, null, null, null, null);
        }

        static SectionReportEntry of(FitLoop.FitLoopResult result, PriceTable prices) {
            int inputTokens = 0;
            int outputTokens = 0;
            int cacheCreation = 0;
            int cacheRead = 0;
            double cost = 0;
            for (ModelResponse.Usage u : result.callUsages()) {
                inputTokens += u.inputTokens();
                outputTokens += u.outputTokens();
                cacheCreation += u.cacheCreationInputTokens();
                cacheRead += u.cacheReadInputTokens();
                cost += prices.costUsd(u);
            }
            ModelResponse.Usage totals = new ModelResponse.Usage(inputTokens, outputTokens, cacheCreation, cacheRead);
            return new SectionReportEntry(result.status(), result.rounds(), result.finalResults(),
                    result.callUsages().size(), totals, cost);
        }
    }
}
