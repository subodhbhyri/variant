package com.tailor.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.blocks.LibraryProject;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.generate.FitLoop;
import com.tailor.engine.generate.ModelResponse;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.generate.SlotReports;
import com.tailor.engine.match.AssembledResume;
import com.tailor.engine.match.Alternatives;
import com.tailor.engine.match.Embedder;
import com.tailor.engine.match.FakeEmbedder;
import com.tailor.engine.match.JdParser;
import com.tailor.engine.match.JobDescription;
import com.tailor.engine.match.MissingSkills;
import com.tailor.engine.match.ResumeRenderer;
import com.tailor.engine.match.Shapes;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code tailor match <onboarded.docx> <variants.json> <library.json> <jd.txt> <outDir>
 * [--embedder fake|minilm]} — PHASE5_SPEC.md section 9. Writes {@code match.json} (parsed JD,
 * the 1-3 resumes with labels and missing skills) and {@code resume-1.pdf} (verified).
 *
 * <p>{@code --embedder fake} is for fixtures and tests; {@code minilm} is wired in with step
 * 5.2/P5-T7. The cache decision (section 6) needs a store of previously-parsed JD fingerprints
 * per (user, library version) that nothing in the spec's CLI surface names yet, so it's left out
 * of {@code match.json} here rather than guessed at.
 */
@Command(name = "match", description = "Matches a job description to stored material and assembles resumes (spec section 9).")
public final class MatchCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Parameters(index = "0", description = "An already-onboarded (normalized) .docx")
    private Path onboardedPath;

    @Parameters(index = "1", description = "variants.json (Phase 4 tailor generate output)")
    private Path variantsJsonPath;

    @Parameters(index = "2", description = "library.json (Phase 3 contract)")
    private Path libraryJsonPath;

    @Parameters(index = "3", description = "Plain-text job description")
    private Path jdTextPath;

    @Parameters(index = "4", description = "Output directory")
    private Path outDir;

    @Option(names = "--embedder", description = "fake (fixtures/tests) or minilm (production)", defaultValue = "fake")
    private String embedderName;

    record VariantsInput(Map<String, Map<String, FitLoop.CandidateOutcome>> jobs) {
    }

    record MatchOutput(JobDescription jd, List<AssembledResume> resumes, List<String> missing) {
    }

    @Override
    public Integer call() {
        try {
            String jdText = Files.readString(jdTextPath);
            JdParser.ValidationResult validation = JdParser.validate(jdText);
            if (!validation.accepted()) {
                System.err.println("match failed: " + validation.reason() + " - " + validation.message());
                return 1;
            }

            Renderer renderer = new LibreOfficeRenderer();
            FontMap fontMap = FontMap.loadDefault();
            Path workDir = Files.createTempDirectory("match-cli");

            List<OnboardReport.SlotReport> slotReports = SlotReports.build(onboardedPath, renderer, workDir);
            int editableCount = (int) slotReports.stream().filter(OnboardReport.SlotReport::editable).count();
            OnboardReport report = OnboardReport.accepted(
                    0, 0.0, 0, 0, 0, List.of(), editableCount, slotReports, renderer.version());

            SkillsDictionary skills = SkillsDictionary.load(skillsSeedPath());
            JobDescription jd = JdParser.parse(jdText, skills);

            Embedder embedder;
            if ("fake".equals(embedderName)) {
                embedder = new FakeEmbedder();
            } else if ("minilm".equals(embedderName)) {
                System.err.println("match failed: --embedder minilm is not wired up yet (PHASE5_SPEC.md step 5.2)");
                return 1;
            } else {
                System.err.println("match failed: --embedder must be fake or minilm (got " + embedderName + ")");
                return 1;
            }

            Shapes shapes = Shapes.measure(onboardedPath, report);

            VariantsInput variants = MAPPER.readValue(variantsJsonPath.toFile(), VariantsInput.class);
            Map<String, FitLoop.CandidateOutcome> jobOutcomes = variants.jobs().get(shapes.job().id());
            if (jobOutcomes == null) {
                System.err.println("match failed: variants.json has no job \"" + shapes.job().id() + "\"");
                return 1;
            }
            List<ModelResponse.BulletCandidate> jobCandidates = keptCandidates(jobOutcomes);

            LibraryProject.Library library = MAPPER.readValue(libraryJsonPath.toFile(), LibraryProject.Library.class);

            List<AssembledResume> resumes =
                    Alternatives.top3(shapes, jobCandidates, library.projects(), jd, skills, embedder);

            List<String> materialTexts = MissingSkills.materialTexts(jobCandidates, library.projects());
            List<String> missing = MissingSkills.compute(jd, materialTexts, skills);

            Files.createDirectories(outDir);

            Map<String, ModelResponse.BulletCandidate> jobCandidatesById = new LinkedHashMap<>();
            for (ModelResponse.BulletCandidate c : jobCandidates) {
                jobCandidatesById.put(c.id(), c);
            }
            Path resume1Docx = outDir.resolve("resume-1.docx");
            ResumeRenderer.RenderResult rendered = ResumeRenderer.render(onboardedPath, report, resumes.get(0),
                    jobCandidatesById, library.projects(), renderer, fontMap, workDir, resume1Docx);
            if (!rendered.ok()) {
                System.err.println("match failed: resume #1 " + rendered.detail());
                return 1;
            }
            Path resume1Pdf = renderer.render(rendered.outputDocx(), workDir);
            Files.copy(resume1Pdf, outDir.resolve("resume-1.pdf"), StandardCopyOption.REPLACE_EXISTING);

            MatchOutput output = new MatchOutput(jd, resumes, missing);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(outDir.resolve("match.json").toFile(), output);

            System.out.println("match ok -> " + outDir);
            return 0;
        } catch (Exception e) {
            System.err.println("match failed: " + e);
            return 1;
        }
    }

    private static List<ModelResponse.BulletCandidate> keptCandidates(Map<String, FitLoop.CandidateOutcome> outcomes) {
        List<ModelResponse.BulletCandidate> out = new ArrayList<>();
        for (var e : outcomes.entrySet()) {
            if ("OK".equals(e.getValue().status())) {
                out.add(new ModelResponse.BulletCandidate(e.getKey(), e.getValue().variants()));
            }
        }
        return out;
    }

    private static Path skillsSeedPath() {
        // fixtures/phase4/skills_seed.json — Phase 5 grows this into O*NET-backed data (section
        // 1.1); resolved the same way GenerateCommand does, since it's not yet a classpath resource.
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
}
