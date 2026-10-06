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
import com.tailor.engine.measure.StoredBaseline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.verify.Verifier;
import java.io.IOException;
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
            Renderer renderer, FontMap fontMap, StoredBaseline baseline, Path baselinePdf,
            List<MatchBatchSummary.Infeasible> infeasible) {
    }

    /** {@code resume1Docx}/{@code renderFailureReason} are mutually exclusive: a null reason
     * means resume #1 rendered (after PHASE5_SPEC.md section 5's fail-soft re-solves, if any —
     * {@code resumes.get(0)} already reflects whatever it settled on, including any {@code
     * degraded} positions); a non-null reason means it was dropped (no feasible assignment, or
     * the final whole-document verify failed) and {@code resumes.get(0)} is the original,
     * unrendered best-first candidate — never silently replaced, per the spec. {@code resume1Pdf}
     * is the PDF of exactly {@code resume1Docx}, when the check that verified it rendered it. */
    public record Result(JobDescription jd, List<AssembledResume> resumes, List<String> missing, Path resume1Docx,
            String renderFailureReason, PipelineTiming timing, Path resume1Pdf,
            List<MatchBatchSummary.Infeasible> rejections) {
    }

    /**
     * Reads one posting's context from the onboarding outputs written beside {@code onboardedDocx}:
     * {@code onboard.json} (slot reports with their calibrated line counts and hints), {@code
     * baseline.json} (the baseline's line positions) and {@code preview.pdf} (its render, for the
     * date-tab edges). Nothing is calibrated or rendered here; a missing or out-of-date output
     * fails with a request to onboard again.
     */
    public static Context buildContext(Path onboardedDocx, Path variantsJsonPath, Path libraryJsonPath,
            SkillsDictionary skills, Embedder embedder, Renderer renderer, FontMap fontMap) throws Exception {
        Path normalized = onboardedDocx.toAbsolutePath().normalize();
        OnboardReport report = storedReport(onboardedDocx.resolveSibling("onboard.json"));
        Path baselinePdf = onboardedDocx.resolveSibling("preview.pdf");
        if (!Files.isRegularFile(baselinePdf)) {
            throw OnboardReport.reonboard(baselinePdf, "missing");
        }
        StoredBaseline baseline = StoredBaseline.readFrom(onboardedDocx.resolveSibling("baseline.json"));

        CountingRenderer counter = new CountingRenderer(renderer);
        Renderer memo = new BaselineMemoRenderer(counter, normalized, baselinePdf);

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

        List<MatchBatchSummary.Infeasible> infeasible = MatchBatchSummary.infeasibilities(
                library.projects(), shapes.positions(), skills, embedder);
        return new Context(onboardedDocx, report, shapes, jobCandidates, jobCandidatesById, library.projects(),
                skills, embedder, counter, memo, fontMap, baseline, baselinePdf, infeasible);
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
            return new Result(jd, resumes, missing, null, "resume #1 " + rendered.render().detail(), timing, null,
                    rejections(rendered));
        }

        double total = Assembler.totalScore(
                new AssembledResume(first.job(), rendered.resume().projects(), null, null), ctx.shapes(),
                ctx.jobCandidates(), jd, ctx.skills(), scoring);
        AssembledResume settled = new AssembledResume(
                first.job(), rendered.resume().projects(), first.label(), total, rendered.resume().degraded());
        List<AssembledResume> finalResumes = new ArrayList<>(resumes);
        finalResumes.set(0, settled);

        return new Result(jd, finalResumes, missing, rendered.render().outputDocx(), null, timing, rendered.pdf(),
                rejections(rendered));
    }

    /** onboard.json as stored at onboarding: accepted, with a renderer version and a calibrated line count
     * for every editable slot. Anything else is from an older format or incomplete. */
    static OnboardReport storedReport(Path onboardJson) throws IOException {
        if (!Files.isRegularFile(onboardJson)) {
            throw OnboardReport.reonboard(onboardJson, "missing");
        }
        OnboardReport report = OnboardReport.readFrom(onboardJson);
        boolean current = report.accepted() && report.rendererVersion() != null && report.slots() != null
                && report.slots().stream().allMatch(s -> !s.editable() || s.lines() != null);
        if (!current) {
            throw OnboardReport.reonboard(onboardJson, "from an older format or incomplete");
        }
        return report;
    }

    /** Every project that can't fill a position for this posting: the batch-wide reasons, then the re-solves. */
    public static List<MatchBatchSummary.Infeasible> infeasibleFor(Context ctx, Result result) {
        List<MatchBatchSummary.Infeasible> out = new ArrayList<>(ctx.infeasible());
        out.addAll(result.rejections());
        return out;
    }

    /** The placements resume #1's batch had to re-solve around, in the output's own terms. */
    private static List<MatchBatchSummary.Infeasible> rejections(ResumeRenderer.FailSoftResult rendered) {
        List<MatchBatchSummary.Infeasible> out = new ArrayList<>();
        for (BatchAssembler.Rejection r : rendered.rejections()) {
            out.add(new MatchBatchSummary.Infeasible(r.project(), r.position(), r.reason()));
        }
        return out;
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
