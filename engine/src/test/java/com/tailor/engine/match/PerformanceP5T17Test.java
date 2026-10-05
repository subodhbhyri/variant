package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.CorpusPaths;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * P5-T17 (PHASE5_SPEC.md section 10, revision 5): on the fixture postings with {@code --embedder
 * minilm}, the optimised path's {@code match.json} and resume #1 document are identical to the
 * unoptimised baseline stored in {@code fixtures/phase5/match_run/golden}; resume #1 takes at most
 * 8 renders and 10 s. Manual: needs the MiniLM model ({@code VARIANT_MODEL_DIR}), so it is tagged
 * {@code minilm} and runs in neither {@code test} nor {@code corpusTest}. The identity check is
 * reported separately from the render and time checks, which are not relaxed to pass.
 *
 * <p>PDFs are not compared byte-for-byte: LibreOffice stamps a wall-clock CreationDate into every
 * PDF, so two runs of the same code never match. The compared document is the resume's own
 * {@code word/document.xml}.
 */
@Tag("minilm")
class PerformanceP5T17Test {

    private static final int MAX_RESUME_ONE_RENDERS = 8;
    private static final long MAX_RESUME_ONE_MS = 10_000;
    private static final List<String> POSTINGS = List.of("platform", "frontend", "data");

    /** Field-for-field the CLI's {@code MatchCommand.MatchOutput}, so the bytes match. */
    record MatchOutput(JobDescription jd, List<AssembledResume> resumes, List<String> missing) {
    }

    @Test
    void optimisedPathMatchesTheBaselineWithinRenderAndTimeTargets() throws Exception {
        assumeTrue(System.getenv("VARIANT_MODEL_DIR") != null, "needs VARIANT_MODEL_DIR (MiniLM)");
        Path fixtures = CorpusPaths.phase5FixturesDir();
        Path golden = fixtures.resolve("match_run").resolve("golden");

        Renderer renderer = new LibreOfficeRenderer();
        FontMap fontMap = FontMap.loadDefault();
        Path outDir = Files.createTempDirectory("p5t17-onboard");
        byte[] upload = Files.readAllBytes(CorpusPaths.phase3FixturesDir().resolve("projects_synthetic.docx"));
        OnboardReport report = new OnboardPipeline(renderer, fontMap).run(upload, outDir);
        assumeTrue(report.accepted(), "fixture failed to onboard: " + report.reason());

        Path workDir = Files.createTempDirectory("p5t17-work");
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        try (MiniLmEmbedder embedder = MiniLmEmbedder.loadFromEnv()) {
            MatchRunner.Context ctx = MatchRunner.buildContext(outDir.resolve("normalized.docx"),
                    fixtures.resolve("match_run").resolve("variants.json"),
                    CorpusPaths.phase3FixturesDir().resolve("library.json"),
                    SkillsDictionary.loadDefault(), embedder, renderer, fontMap, workDir);

            StringBuilder identity = new StringBuilder();
            StringBuilder renders = new StringBuilder();
            StringBuilder time = new StringBuilder();
            for (String name : POSTINGS) {
                String jdText = Files.readString(fixtures.resolve("jds").resolve(name + ".txt"));
                Path resumeDocx = Files.createTempDirectory("p5t17-" + name).resolve("resume-1.docx");
                MatchRunner.Result result = MatchRunner.runOne(ctx, jdText, workDir, resumeDocx);
                assertTrue(result.renderFailureReason() == null, name + " failed: " + result.renderFailureReason());

                byte[] matchJson = mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(new MatchOutput(result.jd(), result.resumes(), result.missing()));
                byte[] goldenJson = Files.readAllBytes(golden.resolve(name).resolve("match.json"));
                if (!java.util.Arrays.equals(matchJson, goldenJson)) {
                    identity.append(name).append(": match.json differs from baseline\n");
                }
                String docXml = documentXml(resumeDocx);
                String goldenXml = documentXml(golden.resolve(name).resolve("resume-1.docx"));
                if (!docXml.equals(goldenXml)) {
                    identity.append(name).append(": resume #1 document.xml differs from baseline\n");
                }

                long resumeOneRenders = result.timing().totalRenders();
                System.out.println("P5-T17 " + name + " renders=" + resumeOneRenders + " ms=" + result.timing().totalMs());
                if (resumeOneRenders > MAX_RESUME_ONE_RENDERS) {
                    renders.append(name).append(": ").append(resumeOneRenders).append(" renders > ")
                            .append(MAX_RESUME_ONE_RENDERS).append('\n');
                }
                long ms = result.timing().totalMs();
                if (ms > MAX_RESUME_ONE_MS) {
                    time.append(name).append(": ").append(ms).append(" ms > ").append(MAX_RESUME_ONE_MS).append('\n');
                }
            }

            System.out.println("P5-T17 identity failures: [" + identity + "]");
            System.out.println("P5-T17 render failures: [" + renders + "]");
            System.out.println("P5-T17 time failures: [" + time + "]");
            assertTrue(identity.isEmpty(), "identity (must always hold):\n" + identity);
            assertTrue(renders.isEmpty(), "renders target not met:\n" + renders);
            assertTrue(time.isEmpty(), "time target not met:\n" + time);
        }
    }

    private static String documentXml(Path docx) throws Exception {
        DocxPackage pkg = DocxPackage.open(docx);
        return new String(pkg.readPart("word/document.xml"), java.nio.charset.StandardCharsets.UTF_8);
    }
}
