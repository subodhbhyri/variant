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
        return new OnboardReport(false, reason, message, null, null, null, null, null, null, null, null);
    }

    public static OnboardReport accepted(
            int pages, double shrinkPt, int squeezeRemoved, int positionRemoved,
            List<FontSub> fonts, int editableCount, List<SlotReport> slots, String rendererVersion) {
        return new OnboardReport(true, null, null, pages, shrinkPt, squeezeRemoved, positionRemoved,
                fonts, editableCount, slots, rendererVersion);
    }

    public void writeTo(Path jsonPath) throws IOException {
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), this);
    }
}
