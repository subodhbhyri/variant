package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.generate.ModelResponse.BulletCandidate;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * P5-T6 (PHASE5_SPEC.md section 10, step 5.6): resume #1 for each fixture JD, assembled on
 * fixtures/phase3/projects_synthetic.docx, passes the Verifier.
 */
@Tag("corpus")
class RenderVerifyCorpusTest {

    private static final List<String> JD_NAMES = List.of("platform", "frontend", "data");

    @Test
    void resumeOneRendersAndVerifiesOnAllFixtureJds() throws Exception {
        Phase5TestSetup s = Phase5TestSetup.load();

        Renderer renderer = new LibreOfficeRenderer();
        FontMap fontMap = FontMap.loadDefault();
        OnboardPipeline onboard = new OnboardPipeline(renderer, fontMap);
        Path outDir = Files.createTempDirectory("match-render-onboard");
        byte[] upload = Files.readAllBytes(CorpusPaths.phase3FixturesDir().resolve("projects_synthetic.docx"));
        OnboardReport report = onboard.run(upload, outDir);
        assertTrue(report.accepted(), "fixture failed to onboard: " + report.reason());
        Path normalizedDocx = outDir.resolve("normalized.docx");

        Map<String, BulletCandidate> jobCandidatesById = new LinkedHashMap<>();
        for (BulletCandidate c : s.jobCandidates()) {
            jobCandidatesById.put(c.id(), c);
        }

        StringBuilder failures = new StringBuilder();
        for (String name : JD_NAMES) {
            JobDescription jd = s.jds.get(name);
            AssembledResume resume =
                    Assembler.assemble(s.shapes, s.jobCandidates(), s.library, jd, s.skills, s.embedder, Set.of());

            Path workDir = Files.createTempDirectory("match-render-" + name);
            Path outputDocx = workDir.resolve(name + "-resume1.docx");
            ResumeRenderer.RenderResult result = ResumeRenderer.render(
                    normalizedDocx, report, resume, jobCandidatesById, s.library, renderer, fontMap, workDir,
                    outputDocx);

            System.out.println(name + ": " + (result.ok() ? "OK -> " + result.outputDocx() : "FAILED: " + result.detail()));
            if (!result.ok()) {
                failures.append(name).append(": ").append(result.detail()).append('\n');
            }
        }

        System.out.println("FAILURES:\n" + (failures.isEmpty() ? "(none)" : failures));
        assertTrue(failures.isEmpty(), failures.toString());
    }
}
