package com.tailor.engine.match;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.blocks.BatchAssembler;
import com.tailor.engine.blocks.LibraryProject;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.generate.FitLoop;
import com.tailor.engine.generate.ModelResponse;
import com.tailor.engine.generate.ModelResponse.BulletCandidate;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.generate.SlotReports;
import com.tailor.engine.measure.StoredBaseline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.verify.Verifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PHASE5_SPEC.md section 9: the shared per-onboarding-document setup ({@link #buildContext}) and
 * per-job-description run ({@link #runOne}) behind both {@code tailor match} and {@code tailor
 * match-batch} — a batch reuses one {@link Context} (onboard report, shapes, stored job
 * candidates, library, stored baseline) across every job description instead of redoing that work
 * per file.
 */
public final class MatchRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private MatchRunner() {
    }

    // ignoreUnknown: tailor generate's own output file has both "jobs" and "projects" at the top
    // level, and callers are free to pass that whole file as <variants.json> here directly.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record VariantsInput(Map<String, Map<String, FitLoop.CandidateOutcome>> jobs) {
    }

    /** {@code counter} counts real renders for {@link PipelineTiming}; {@code renderer} is what the
     * pipeline calls — the baseline memo over {@code counter}, so a repeated baseline render is free
     * and not counted. {@code baseline}/{@code baselinePdf} are the onboarded document's own render,
     * stored with the onboarding outputs (PHASE5_SPEC.md section 5.1, step B1). */
    public record Context(Path onboardedDocx, OnboardReport report, Shapes shapes,
            List<BulletCandidate> jobCandidates, Map<String, BulletCandidate> jobCandidatesById,
            List<LibraryProject> library, SkillsDictionary skills, Embedder embedder, CountingRenderer counter,
            Renderer renderer, FontMap fontMap, StoredBaseline baseline, Path baselinePdf) {
    }

    /** {@code resume1Docx}/{@code renderFailureReason} are mutually exclusive: a null reason
     * means resume #1 rendered (after PHASE5_SPEC.md section 5's fail-soft re-solves, if any —
     * {@code resumes.get(0)} already reflects whatever it settled on, including any {@code
     * degraded} positions); a non-null reason means it was dropped (no feasible assignment, or
     * the final whole-document verify failed) and {@code resumes.get(0)} is the original,
     * unrendered best-first candidate — never silently replaced, per the spec. {@code resume1Pdf}
     * is the PDF of exactly {@code resume1Docx}, when the check that verified it rendered it. */
    public record Result(JobDescription jd, List<AssembledResume> resumes, List<String> missing, Path resume1Docx,
            String renderFailureReason, PipelineTiming timing, Path resume1Pdf) {
    }

    public static Context buildContext(Path onboardedDocx, Path variantsJsonPath, Path libraryJsonPath,
            SkillsDictionary skills, Embedder embedder, Renderer renderer, FontMap fontMap, Path workDir)
            throws Exception {
        Path normalized = onboardedDocx.toAbsolutePath().normalize();
        Path storedPdf = onboardedDocx.resolveSibling("preview.pdf");
        Path storedJson = onboardedDocx.resolveSibling("baseline.json");
        boolean stored = Files.isRegularFile(storedPdf) && Files.isRegularFile(storedJson);
        Path baselinePdf = stored ? storedPdf : renderer.render(normalized, workDir);
        StoredBaseline baseline = stored ? StoredBaseline.readFrom(storedJson) : StoredBaseline.measure(baselinePdf);

        CountingRenderer counter = new CountingRenderer(renderer);
        Renderer memo = new BaselineMemoRenderer(counter, normalized, baselinePdf);

        List<OnboardReport.SlotReport> slotReports = SlotReports.build(onboardedDocx, memo, workDir);
        int editableCount = (int) slotReports.stream().filter(OnboardReport.SlotReport::editable).count();
        OnboardReport report = OnboardReport.accepted(
                0, 0.0, 0, 0, 0, List.of(), editableCount, slotReports, renderer.version());

        Shapes shapes = Shapes.measure(onboardedDocx, report);

        VariantsInput variants = MAPPER.readValue(variantsJsonPath.toFile(), VariantsInput.class);
        Map<String, FitLoop.CandidateOutcome> jobOutcomes = variants.jobs().get(shapes.job().id());
        if (jobOutcomes == null) {
            throw new IllegalStateException("variants.json has no job \"" + shapes.job().id() + "\"");
        }
        List<BulletCandidate> jobCandidates = keptCandidates(jobOutcomes);
        Map<String, BulletCandidate> jobCandidatesById = new LinkedHashMap<>();
        for (BulletCandidate c : jobCandidates) {
            jobCandidatesById.put(c.id(), c);
        }

        LibraryProject.Library library = MAPPER.readValue(libraryJsonPath.toFile(), LibraryProject.Library.class);

        return new Context(onboardedDocx, report, shapes, jobCandidates, jobCandidatesById, library.projects(),
                skills, embedder, counter, memo, fontMap, baseline, baselinePdf);
    }

    public static Result runOne(Context ctx, String jdText, Path workDir, Path resume1DocxOut) throws Exception {
        return runOne(ctx, jdText, workDir, resume1DocxOut, productionCheck(ctx));
    }

    /** The whole-document check of the batched assembly: resume #1 against the stored baseline. */
    static BatchAssembler.Check productionCheck(Context ctx) {
        return (docx, regions, wd) -> {
            Verifier.Checked checked = Verifier.verifyRegionsAgainstBaseline(
                    ctx.baseline(), docx, ctx.renderer(), ctx.fontMap(), regions, wd);
            return new BatchAssembler.Verified(checked.report().ok(), Verifier.describe(checked.report()),
                    checked.assembledPdf());
        };
    }

    /** As {@link #runOne(Context, String, Path, Path)}, with {@code check} as the batched assembly's
     * whole-document check; package-visible so a test can force that check to fail. */
    static Result runOne(Context ctx, String jdText, Path workDir, Path resume1DocxOut,
            BatchAssembler.Check check) throws Exception {
        PipelineTiming timing = new PipelineTiming(ctx.counter());
        Embedder scoring = new CachingEmbedder(ctx.embedder());
        JobDescription jd = timing.time("parse", () -> JdParser.parse(jdText, ctx.skills()));
        List<AssembledResume> resumes = Alternatives.top3(
                ctx.shapes(), ctx.jobCandidates(), ctx.library(), jd, ctx.skills(), scoring, timing);

        List<String> missing = timing.time("missing skills", () -> {
            String wholeResume = MissingSkills.wholeResumeText(ctx.onboardedDocx());
            List<String> materialTexts = MissingSkills.materialTexts(wholeResume, ctx.jobCandidates(), ctx.library());
            return MissingSkills.compute(jd, materialTexts, ctx.skills());
        });

        ResumeRenderer.BatchInputs batch = new ResumeRenderer.BatchInputs(ctx.baseline(), ctx.baselinePdf(), check);

        AssembledResume first = resumes.get(0);
        ResumeRenderer.FailSoftResult rendered = ResumeRenderer.renderFailSoft(ctx.onboardedDocx(), ctx.report(),
                ctx.shapes(), first.job(), ctx.library(), jd, ctx.skills(), scoring, ctx.jobCandidatesById(),
                ctx.renderer(), ctx.fontMap(), workDir, resume1DocxOut, timing, batch);
        if (!rendered.render().ok()) {
            return new Result(jd, resumes, missing, null, "resume #1 " + rendered.render().detail(), timing, null);
        }

        double total = Assembler.totalScore(
                new AssembledResume(first.job(), rendered.resume().projects(), null, null), ctx.shapes(),
                ctx.jobCandidates(), jd, ctx.skills(), scoring);
        AssembledResume settled = new AssembledResume(
                first.job(), rendered.resume().projects(), first.label(), total, rendered.resume().degraded());
        List<AssembledResume> finalResumes = new ArrayList<>(resumes);
        finalResumes.set(0, settled);

        return new Result(jd, finalResumes, missing, rendered.render().outputDocx(), null, timing, rendered.pdf());
    }

    private static List<BulletCandidate> keptCandidates(Map<String, FitLoop.CandidateOutcome> outcomes) {
        List<BulletCandidate> out = new ArrayList<>();
        for (Map.Entry<String, FitLoop.CandidateOutcome> e : outcomes.entrySet()) {
            if ("OK".equals(e.getValue().status())) {
                out.add(new ModelResponse.BulletCandidate(e.getKey(), e.getValue().variants()));
            }
        }
        return out;
    }
}
