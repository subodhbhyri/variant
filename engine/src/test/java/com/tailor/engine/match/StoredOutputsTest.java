package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.onboard.OnboardReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Match reads the onboarding outputs instead of calibrating again, so a missing or out-of-date output
 * must fail with a request to onboard again, not recalibrate silently. Fast: nothing renders here,
 * because each case fails before any render could happen.
 */
class StoredOutputsTest {

    @Test
    void missingOnboardReportAsksForReonboarding(@TempDir Path dir) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> build(dir));
        assertTrue(e.getMessage().contains("re-onboard"), e.getMessage());
    }

    @Test
    void onboardReportWithoutCalibratedLinesIsAnOlderFormat(@TempDir Path dir) throws Exception {
        OnboardReport.SlotReport uncalibrated = new OnboardReport.SlotReport(0, "text", null, true, null, null);
        OnboardReport.accepted(1, 0.0, 0, 0, 0, List.of(), 1, List.of(uncalibrated), "v")
                .writeTo(dir.resolve("onboard.json"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> build(dir));
        assertTrue(e.getMessage().contains("re-onboard"), e.getMessage());
        assertTrue(e.getMessage().contains("older format or incomplete"), e.getMessage());
    }

    @Test
    void baselineFromAnotherFormatIsRejected(@TempDir Path dir) throws Exception {
        OnboardReport.SlotReport calibrated = new OnboardReport.SlotReport(0, "text", 1, true, null, 40);
        OnboardReport.accepted(1, 0.0, 0, 0, 0, List.of(), 1, List.of(calibrated), "v")
                .writeTo(dir.resolve("onboard.json"));
        Files.write(dir.resolve("preview.pdf"), new byte[] {0});
        Files.writeString(dir.resolve("baseline.json"), "{\"pages\": 1, \"lines\": []}");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> build(dir));
        assertTrue(e.getMessage().contains("re-onboard"), e.getMessage());
        assertTrue(e.getMessage().contains("older format"), e.getMessage());
    }

    private static void build(Path dir) throws Exception {
        MatchRunner.buildContext(dir.resolve("normalized.docx"), dir.resolve("variants.json"),
                dir.resolve("library.json"), null, new FakeEmbedder(), null, null);
    }
}
