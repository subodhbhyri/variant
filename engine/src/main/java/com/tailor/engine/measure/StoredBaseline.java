package com.tailor.engine.measure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * PHASE5_SPEC.md section 5.1 (step B1): the onboarded document's own render, kept beside the
 * onboarding outputs ({@code baseline.json}) so resume assembly can measure and verify against it
 * without rendering the baseline again. Holds what the line-count anchors and the verifier read
 * from a render: its page count and every extracted line, in document order.
 */
public record StoredBaseline(int pages, List<PdfLines.Line> lines) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    public static StoredBaseline measure(Path renderedPdf) throws IOException {
        return new StoredBaseline(PdfPageCounter.count(renderedPdf), PdfLines.extract(renderedPdf));
    }

    public void writeTo(Path json) throws IOException {
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), this);
    }

    public static StoredBaseline readFrom(Path json) throws IOException {
        return MAPPER.readValue(json.toFile(), StoredBaseline.class);
    }
}
