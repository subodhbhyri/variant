package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.blocks.BatchAssembler;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PHASE5_SPEC.md section 5.1 (step B2) fallback: when the batched assembly's own whole-document
 * check fails, the resume must not be delivered from that assembly. Resume #1 then goes through the
 * per-position fail-soft path, which verifies each swap and the final document itself, and either
 * delivers that verified result (with any position it had to give up recorded under {@code
 * degraded}) or drops the resume. Forced here by replacing the batched check with one that always
 * fails, on the real fixture postings through LibreOffice ({@code corpus} tag).
 */
@Tag("corpus")
class BatchedFallbackTest {

    @Test
    void aFailedBatchedCheckFallsBackToVerifiedPerPositionOrDrops() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        FontMap fontMap = FontMap.loadDefault();
        Path onboardDir = Files.createTempDirectory("fallback-onboard");
        byte[] upload = Files.readAllBytes(CorpusPaths.phase3FixturesDir().resolve("projects_synthetic.docx"));
        OnboardReport report = new OnboardPipeline(renderer, fontMap).run(upload, onboardDir);
        assumeTrue(report.accepted(), "fixture failed to onboard: " + report.reason());

        Path fixtures = CorpusPaths.phase5FixturesDir().resolve("match_run");
        Path workDir = Files.createTempDirectory("fallback-work");
        MatchRunner.Context ctx = MatchRunner.buildContext(onboardDir.resolve("normalized.docx"),
                fixtures.resolve("variants.json"), CorpusPaths.phase3FixturesDir().resolve("library.json"),
                SkillsDictionary.loadDefault(), new FakeEmbedder(), renderer, fontMap);

        String jdText = Files.readString(CorpusPaths.phase5FixturesDir().resolve("jds").resolve("platform.txt"));
        Path resumeDocx = Files.createTempDirectory("fallback-out").resolve("resume-1.docx");
        AtomicInteger batchedChecks = new AtomicInteger();
        BatchAssembler.Check alwaysFails = (docx, regions, wd) -> {
            batchedChecks.incrementAndGet();
            return new BatchAssembler.Verified(false, "forced by test", null);
        };

        MatchRunner.Result result = MatchRunner.runOne(ctx, jdText, workDir, resumeDocx, alwaysFails);

        assertEquals(1, batchedChecks.get(), "the batched check must have run, or the fallback was never exercised");
        assertTrue(result.timing().report("platform").stages().stream()
                        .anyMatch(s -> s.name().startsWith("batched assembly not used: final whole-document check: "
                                + "forced by test")),
                "the fallback must be recorded in the timing stages");
        assertNull(result.resume1Pdf(), "a per-position result has no batched check PDF to deliver");
        System.out.println("BatchedFallbackTest outcome: "
                + (result.renderFailureReason() == null ? "delivered per position, degraded="
                        + result.resumes().get(0).degraded() : "dropped: " + result.renderFailureReason()));

        if (result.renderFailureReason() != null) {
            assertNull(result.resume1Docx(), "a dropped resume delivers no document");
            return;
        }
        assertNotNull(result.resume1Docx(), "a delivered resume has its verified document");
        assertTrue(Files.exists(result.resume1Docx()), "the verified document must exist on disk");
        AssembledResume settled = result.resumes().get(0);
        int positions = ctx.shapes().positions().size();
        assertEquals(positions, settled.projects().size() + settled.degraded().size(),
                "every position is either placed (verified per swap) or recorded under degraded");
        for (AssembledResume.Degraded d : settled.degraded()) {
            assertTrue(d.reason() != null && !d.reason().isBlank(), "a degraded position must say why");
        }
    }
}
