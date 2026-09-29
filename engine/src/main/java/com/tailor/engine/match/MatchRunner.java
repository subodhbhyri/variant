package com.tailor.engine.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.blocks.LibraryProject;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.generate.FitLoop;
import com.tailor.engine.generate.ModelResponse;
import com.tailor.engine.generate.ModelResponse.BulletCandidate;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.generate.SlotReports;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.Renderer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PHASE5_SPEC.md section 9: the shared per-onboarding-document setup ({@link #buildContext}) and
 * per-job-description run ({@link #runOne}) behind both {@code tailor match} and {@code tailor
 * match-batch} — a batch reuses one {@link Context} (onboard report, shapes, stored job
 * candidates, library) across every job description instead of redoing that work per file.
 */
public final class MatchRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private MatchRunner() {
    }

    record VariantsInput(Map<String, Map<String, FitLoop.CandidateOutcome>> jobs) {
    }

    public record Context(Path onboardedDocx, OnboardReport report, Shapes shapes,
            List<BulletCandidate> jobCandidates, Map<String, BulletCandidate> jobCandidatesById,
            List<LibraryProject> library, SkillsDictionary skills, Embedder embedder, Renderer renderer,
            FontMap fontMap) {
    }

    public record Result(JobDescription jd, List<AssembledResume> resumes, List<String> missing, Path resume1Docx) {
    }

    public static Context buildContext(Path onboardedDocx, Path variantsJsonPath, Path libraryJsonPath,
            SkillsDictionary skills, Embedder embedder, Renderer renderer, FontMap fontMap, Path workDir)
            throws Exception {
        List<OnboardReport.SlotReport> slotReports = SlotReports.build(onboardedDocx, renderer, workDir);
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
                skills, embedder, renderer, fontMap);
    }

    public static Result runOne(Context ctx, String jdText, Path workDir, Path resume1DocxOut) throws Exception {
        JobDescription jd = JdParser.parse(jdText, ctx.skills());
        List<AssembledResume> resumes =
                Alternatives.top3(ctx.shapes(), ctx.jobCandidates(), ctx.library(), jd, ctx.skills(), ctx.embedder());

        List<String> materialTexts = MissingSkills.materialTexts(ctx.jobCandidates(), ctx.library());
        List<String> missing = MissingSkills.compute(jd, materialTexts, ctx.skills());

        ResumeRenderer.RenderResult rendered = ResumeRenderer.render(ctx.onboardedDocx(), ctx.report(),
                resumes.get(0), ctx.jobCandidatesById(), ctx.library(), ctx.renderer(), ctx.fontMap(), workDir,
                resume1DocxOut);
        if (!rendered.ok()) {
            throw new IllegalStateException("resume #1 " + rendered.detail());
        }
        return new Result(jd, resumes, missing, rendered.outputDocx());
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
