package com.tailor.engine.onboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailor.engine.CorpusPaths;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.RenderException;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
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

    /**
     * The full ask: after a timeout, nothing left running afterward can corrupt the result or
     * leak a process. Uses a real LibreOfficeRenderer (not the sleeping fake) pointed at a
     * uniquely-named profile-dir root, so any soffice process this run spawns is identifiable
     * in ProcessHandle.allProcesses() by that root appearing in its -env:UserInstallation
     * argument — and asserts none is still alive 5s after run() returns.
     */
    @Test
    void afterATimeoutNothingWritesOverItAndNoSofficeProcessSurvives() throws Exception {
        Path uniqueProfileRoot = Files.createTempDirectory("locheck-" + System.nanoTime());
        Renderer real = new LibreOfficeRenderer("soffice", uniqueProfileRoot, 60);
        // Real renders take well over this; the deadline fires while soffice is still starting
        // up or mid-convert, exercising the exact "interrupted while renderOnce() is blocked in
        // process.waitFor()" path the LibreOfficeRenderer cleanup fix is for.
        OnboardPipeline pipeline = new OnboardPipeline(real, FontMap.loadDefault(), Duration.ofMillis(300));

        Path outDir = Files.createTempDirectory("onboard-deadline-process-test");
        OnboardReport report = pipeline.run(okSyntheticUpload(), outDir);

        assertFalse(report.accepted());
        assertEquals("PROCESSING_TIMEOUT", report.reason());

        Thread.sleep(5000); // let any still-finishing interrupt/destroyForcibly sequence complete

        assertEquals("PROCESSING_TIMEOUT", reasonInOnboardJson(outDir),
                "nothing the background task did after the deadline should have overwritten onboard.json");

        String marker = uniqueProfileRoot.toString();
        boolean anySofficeStillAlive = ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .anyMatch(ph -> ph.info().commandLine().map(cmd -> cmd.contains(marker)).orElse(false));
        assertFalse(anySofficeStillAlive, "no soffice process for this run's profile directory should still be alive");

        deleteQuietly(uniqueProfileRoot);
    }

    private static String reasonInOnboardJson(Path outDir) throws Exception {
        JsonNode node = new ObjectMapper().readTree(outDir.resolve("onboard.json").toFile());
        return node.get("reason").asText();
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best effort
                }
            });
        } catch (Exception ignored) {
            // best effort
        }
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
