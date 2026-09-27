package com.tailor.engine.golden;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** fixtures/phase4/guard_cases.json (PHASE4_SPEC.md section 5 / 9, test P4-T1). */
public final class Phase4Fixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private Phase4Fixtures() {
    }

    public static GuardCases loadGuardCases(Path guardCasesJson) throws IOException {
        return MAPPER.readValue(requireFile(guardCasesJson).toFile(), GuardCases.class);
    }

    private static Path requireFile(Path p) throws IOException {
        if (!Files.isRegularFile(p)) {
            throw new IOException("phase4 fixture not found: " + p.toAbsolutePath());
        }
        return p;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuardCases(List<String> sourceTexts, List<GuardCase> cases) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuardCase(String name, String variant, Integer budgetChars, List<String> expected) {
    }
}
