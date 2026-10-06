package com.tailor.engine.onboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** PHASE2_SPEC.md section 5: {@code onboard.json}'s schema. */
public record OnboardReport(
        boolean accepted,
        String reason,
        String message,
        Integer pages,
        Double shrinkPt,
        Integer squeezeRemoved,
        Integer positionRemoved,
        Integer trailingEmptyRemoved,
        List<FontSub> fonts,
        Integer editableCount,
        List<SlotReport> slots,
        String rendererVersion) {

    public record FontSub(String from, String to, boolean metricCompatible) {
    }

    public record SlotReport(
            int index, String text, Integer lines, boolean editable, String lockReason, Integer hintChars) {
    }

    public static OnboardReport rejected(String reason, String message) {
        return new OnboardReport(false, reason, message, null, null, null, null, null, null, null, null, null);
    }

    public static OnboardReport accepted(
            int pages, double shrinkPt, int squeezeRemoved, int positionRemoved, int trailingEmptyRemoved,
            List<FontSub> fonts, int editableCount, List<SlotReport> slots, String rendererVersion) {
        return new OnboardReport(true, null, null, pages, shrinkPt, squeezeRemoved, positionRemoved,
                trailingEmptyRemoved, fonts, editableCount, slots, rendererVersion);
    }

    public static OnboardReport readFrom(Path jsonPath) throws IOException {
        return new ObjectMapper().readValue(jsonPath.toFile(), OnboardReport.class);
    }

    /**
     * The error for a stored onboarding output that match needs but that is absent or from an older
     * format. Match reads these outputs instead of recalibrating, so the fix is to onboard again.
     */
    public static IllegalStateException reonboard(Path output, String problem) {
        return new IllegalStateException(output + " is " + problem + "; re-onboard this resume (tailor onboard) so"
                + " match can use its stored outputs");
    }

    public void writeTo(Path jsonPath) throws IOException {
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), this);
    }
}
