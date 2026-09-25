package com.tailor.engine.golden;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Expected outcome per fixture, from fixtures/phase2/expected.json (PHASE2_SPEC.md section 6). */
public final class Phase2Fixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private Phase2Fixtures() {
    }

    public static Map<String, Expected> load(Path expectedJson) throws IOException {
        if (!Files.isRegularFile(expectedJson)) {
            throw new IOException("phase2 expected.json not found: " + expectedJson.toAbsolutePath());
        }
        return MAPPER.readValue(expectedJson.toFile(), new TypeReference<Map<String, Expected>>() {
        });
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expected(
            boolean accept,
            String reason,
            Integer editable,
            Map<String, String> locked,
            Map<String, String> fontSubstitution,
            Map<String, Integer> lines,
            Integer pages,
            Integer trailingEmptyRemoved,
            String note) {
    }
}
