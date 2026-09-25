package com.tailor.engine.onboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.render.RenderException;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * P2-T10 (PHASE2_SPEC.md section 6): the 180s onboarding deadline and the
 * work-directory cleanup, both overridden/observed without a real 180s wait
 * or a real LibreOffice render.
 */
class OnboardDeadlineTest {

    @Test
    void deadlineExceededReturnsProcessingTimeout() throws Exception {
        Renderer sleepy = new SleepyRenderer(2000);
        OnboardPipeline pipeline = new OnboardPipeline(sleepy, FontMap.loadDefault(), Duration.ofMillis(150));

        Path outDir = Files.createTempDirectory("onboard-deadline-test");
        OnboardReport report = pipeline.run(okSyntheticUpload(), outDir);

        assertFalse(report.accepted());
        assertEquals("PROCESSING_TIMEOUT", report.reason());
        assertEquals("We couldn't process this file in time. Try saving it again from Word.", report.message());
    }

    @Test
    void workDirectoryIsDeletedAfterATimedOutRun() throws Exception {
        Set<Path> before = onboardWorkDirs();
        Renderer sleepy = new SleepyRenderer(2000);
        OnboardPipeline pipeline = new OnboardPipeline(sleepy, FontMap.loadDefault(), Duration.ofMillis(150));

        Path outDir = Files.createTempDirectory("onboard-deadline-cleanup-test");
        pipeline.run(okSyntheticUpload(), outDir);

        assertEquals(before, onboardWorkDirs(), "no onboard-work directory should remain after a timeout");
    }

    @Test
    void workDirectoryIsDeletedAfterAnAcceptedRun() throws Exception {
        Set<Path> before = onboardWorkDirs();
        OnboardPipeline pipeline = new OnboardPipeline(
                new com.tailor.engine.render.LibreOfficeRenderer(), FontMap.loadDefault());

        Path outDir = Files.createTempDirectory("onboard-cleanup-accepted-test");
        OnboardReport report = pipeline.run(okSyntheticUpload(), outDir);

        assertTrue(report.accepted());
        assertEquals(before, onboardWorkDirs(), "no onboard-work directory should remain after an accepted run");
    }

    @Test
    void workDirectoryIsDeletedAfterARenderStageRejectedRun() throws Exception {
        Set<Path> before = onboardWorkDirs();
        OnboardPipeline pipeline = new OnboardPipeline(
                new com.tailor.engine.render.LibreOfficeRenderer(), FontMap.loadDefault());

        byte[] upload = Files.readAllBytes(CorpusPaths.phase2FixturesDir().resolve("too_few_bullets.docx"));
        Path outDir = Files.createTempDirectory("onboard-cleanup-rejected-test");
        OnboardReport report = pipeline.run(upload, outDir);

        assertFalse(report.accepted());
        assertEquals("TOO_FEW_EDITABLE", report.reason());
        assertEquals(before, onboardWorkDirs(), "no onboard-work directory should remain after a render-stage reject");
    }

    private static byte[] okSyntheticUpload() throws Exception {
        return Files.readAllBytes(CorpusPaths.phase2FixturesDir().resolve("ok_synthetic.docx"));
    }

    private static Set<Path> onboardWorkDirs() throws Exception {
        Path tmpRoot = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> s = Files.list(tmpRoot)) {
            return s.filter(p -> p.getFileName().toString().startsWith("onboard-work")).collect(Collectors.toSet());
        }
    }

    /** A Renderer whose render() just sleeps, so the deadline test needs neither LibreOffice nor 180 real seconds. */
    private static final class SleepyRenderer implements Renderer {
        private final long sleepMillis;

        SleepyRenderer(long sleepMillis) {
            this.sleepMillis = sleepMillis;
        }

        @Override
        public Path render(Path docxPath, Path outDir) throws RenderException {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RenderException("interrupted", e);
            }
            throw new RenderException("should have timed out before this renderer ever finished");
        }

        @Override
        public String version() {
            return "sleepy-test-renderer";
        }
    }
}
