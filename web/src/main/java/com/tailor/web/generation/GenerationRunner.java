package com.tailor.web.generation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tailor.engine.blocks.HomeSections;
import com.tailor.engine.blocks.LibraryProject;
import com.tailor.engine.blocks.Position;
import com.tailor.engine.generate.BudgetTracker;
import com.tailor.engine.generate.BulletEnding;
import com.tailor.engine.generate.Coverage;
import com.tailor.engine.generate.FitLoop;
import com.tailor.engine.generate.Intake;
import com.tailor.engine.generate.IntakeFields;
import com.tailor.engine.generate.IntakeSection;
import com.tailor.engine.generate.ModelClient;
import com.tailor.engine.generate.ModelResponse;
import com.tailor.engine.generate.PriceTable;
import com.tailor.engine.generate.PromptBuilder;
import com.tailor.engine.generate.SectionContext;
import com.tailor.engine.generate.SectionPositions;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.generate.TargetBuilder;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.Renderer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The generation step of {@code tailor generate} (PHASE4_SPEC.md section 8), as a library: the same
 * calls to the same engine classes in the same order as the CLI's {@code GenerateCommand}, with
 * the model client supplied from outside and the output returned instead of written to files.
 * The CLI keeps this logic inside its command class, which the web module may not change or call,
 * so it is repeated here; the web test compares the two on the same inputs and recorded responses.
 *
 * <p>{@code only} limits a run to some sections (after a user edits one section's notes); sections
 * left out are neither generated nor reported, and the caller carries their earlier material over.
 */
public final class GenerationRunner {

    /** Reports where the run is, and whether the worker has given up on it. */
    public interface Progress {
        void step(String stage, int step, int of);

        boolean cancelled();

        Progress NONE = new Progress() {
            @Override
            public void step(String stage, int step, int of) {
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        };
    }

    /** One section's entry in the generation report (same shape as the CLI's generation-report.json). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReportEntry(
            String status, Integer rounds, Map<String, FitLoop.CandidateOutcome> candidates,
            Integer calls, ModelResponse.Usage tokens, Double costUsd, List<FitLoop.Attempt> attempts,
            CoverageEntry coverage) {

        /** PHASE4_SPEC.md section 6.2: whether the project can fill a home-section position, and what's missing. */
        public record CoverageEntry(boolean covered, List<Integer> missingLines) {
            static CoverageEntry of(Coverage.Result r) {
                return r == null ? null : new CoverageEntry(r.covered(), r.missingLengths());
            }
        }

        static ReportEntry locked(String status) {
            return new ReportEntry(status, null, null, null, null, null, List.of(), null);
        }

        static ReportEntry of(FitLoop.FitLoopResult result, PriceTable prices, Coverage.Result coverage) {
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
            return new ReportEntry(result.status(), result.rounds(), result.finalResults(),
                    result.callUsages().size(), totals, cost, result.attempts(), CoverageEntry.of(coverage));
        }
    }

    /** What a run produced; {@code jobVariants} and {@code projects} are {@code variants.json}'s two halves. */
    public record Result(
            Map<String, Map<String, FitLoop.CandidateOutcome>> jobVariants,
            List<LibraryProject> projects,
            Map<String, ReportEntry> report,
            double totalUsd,
            int calls) {
    }

    private final Renderer renderer;
    private final ModelClientFactory clients;

    public GenerationRunner(Renderer renderer, ModelClientFactory clients) {
        this.renderer = renderer;
        this.clients = clients;
    }

    public Result run(Path onboardedPath, OnboardReport report, Intake intake, Set<String> only, Progress progress)
            throws Exception {
        Map<String, IntakeSection> sectionsById = new LinkedHashMap<>();
        for (IntakeSection s : intake.sections()) {
            sectionsById.put(s.id(), s);
        }
        SectionPositions positions = SectionPositions.detect(onboardedPath, report);
        SkillsDictionary skills = SkillsDictionary.loadDefault();
        BudgetTracker tracker = new BudgetTracker(PriceTable.loadDefault());

        Map<String, Map<String, FitLoop.CandidateOutcome>> jobVariants = new LinkedHashMap<>();
        List<LibraryProject> libraryProjects = new ArrayList<>();
        Map<String, ReportEntry> reportSections = new LinkedHashMap<>();
        // PHASE4_SPEC.md section 6.1: every generated bullet takes the resume's own ending convention.
        String bulletEnding = BulletEnding.convention(report.slots().stream().map(OnboardReport.SlotReport::text).toList());

        int total = countModelSections(positions, sectionsById, only);
        int done = 0;

        for (int i = 0; i < positions.jobPositions().size(); i++) {
            String sectionId = "job-" + i;
            if (only != null && !only.contains(sectionId)) {
                continue;
            }
            IntakeSection section = sectionsById.get(sectionId);
            if (section == null || "SKIPPED".equals(section.mode())) {
                reportSections.put(sectionId, ReportEntry.locked("SKIPPED"));
                continue;
            }
            if (!tracker.canGenerate()) {
                reportSections.put(sectionId, ReportEntry.locked("COST_LIMIT"));
                continue;
            }
            stop(progress);
            progress.step("generating", ++done, total);
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
                    positions.slotIndicesByLineCount(job, report), onboardedPath, renderer, bulletEnding);

            ModelClient client = clients.forSection(sectionId);
            FitLoop.FitLoopResult result = FitLoop.run(ctx, client, skills);
            tracker.recordAll(result.callUsages());
            jobVariants.put(sectionId, keptOnly(result.finalResults()));
            reportSections.put(sectionId, ReportEntry.of(result, tracker.prices(), null));
        }

        TargetBuilder.SectionTarget projectTarget = TargetBuilder.forProjects(report, positions);
        Map<Integer, List<Integer>> pooledSlots = pooledProjectSlots(report, positions);
        String addedProjectHomeSection = HomeSections.forAddedProject(positions.projectSectionEntries());
        for (IntakeSection section : intake.sections()) {
            if (!"project".equals(section.kind())) {
                continue;
            }
            if (only != null && !only.contains(section.id())) {
                continue;
            }
            if ("SKIPPED".equals(section.mode())) {
                reportSections.put(section.id(), ReportEntry.locked("SKIPPED"));
                continue;
            }
            if (!tracker.canGenerate()) {
                reportSections.put(section.id(), ReportEntry.locked("COST_LIMIT"));
                continue;
            }
            stop(progress);
            progress.step("generating", ++done, total);
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
                    onboardedPath, renderer, bulletEnding);

            ModelClient client = clients.forSection(section.id());
            IntakeFields fields = section.fields();
            String homeSection = existingIndex != null && existingIndex < positions.projectPositionSections().size()
                    ? positions.projectPositionSections().get(existingIndex)
                    : addedProjectHomeSection;
            FitLoop.FitLoopResult result = FitLoop.run(ctx, client, skills);
            // PHASE4_SPEC.md section 6.2: a DETAILED project must be able to fill a position in its home section.
            Coverage.Result coverage = null;
            if ("DETAILED".equals(section.mode()) && homeSection != null && tracker.canGenerate()) {
                FitLoop.TopUp topUp = FitLoop.coverTopUp(ctx, client, skills, result,
                        homeShapes(positions, report, homeSection));
                result = topUp.result();
                coverage = topUp.after();
            }
            tracker.recordAll(result.callUsages());

            libraryProjects.add(new LibraryProject(
                    section.id(), fields == null ? null : fields.title(), fields == null ? null : fields.detail(),
                    toLibraryLinks(fields), fields == null ? null : fields.date(),
                    keptBulletsInOrder(result.finalResults()), homeSection));
            reportSections.put(section.id(), ReportEntry.of(result, tracker.prices(), coverage));
        }
        return new Result(jobVariants, libraryProjects, reportSections, tracker.totalUsd(), tracker.callCount());
    }

    /** How many sections will call the model (for "generating section 2 of 6"). */
    private static int countModelSections(SectionPositions positions, Map<String, IntakeSection> sectionsById,
            Set<String> only) {
        int n = 0;
        for (int i = 0; i < positions.jobPositions().size(); i++) {
            String id = "job-" + i;
            IntakeSection s = sectionsById.get(id);
            if ((only == null || only.contains(id)) && s != null && !"SKIPPED".equals(s.mode())) {
                n++;
            }
        }
        for (IntakeSection s : sectionsById.values()) {
            if ("project".equals(s.kind()) && (only == null || only.contains(s.id())) && !"SKIPPED".equals(s.mode())) {
                n++;
            }
        }
        return Math.max(n, 1);
    }

    private static void stop(Progress progress) throws InterruptedException {
        if (progress.cancelled() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("generation stopped");
        }
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

    /** The bullet line counts of each swappable project position in the home section, one list per position. */
    private static List<List<Integer>> homeShapes(SectionPositions positions, OnboardReport report, String homeSection) {
        List<List<Integer>> out = new ArrayList<>();
        for (int i = 0; i < positions.projectPositions().size(); i++) {
            Position p = positions.projectPositions().get(i);
            if (p.swappable() && homeSection.equals(positions.projectPositionSections().get(i))) {
                out.add(linesOf(positions.bulletSlotIndices(p), report).stream().filter(Objects::nonNull).toList());
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

    /** {@code project-N} follows an existing swappable position (Phase 3 position order, matching
     * {@code job-N}); {@code project-new-N} has none. */
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

    /** Every swappable project position's bullet slots, grouped by line count and pooled together
     * (PHASE4_SPEC.md section 2: "across all swappable project positions"). */
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
}
