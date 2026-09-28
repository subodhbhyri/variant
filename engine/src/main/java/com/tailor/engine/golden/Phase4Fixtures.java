package com.tailor.engine.golden;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** fixtures/phase4/guard_cases.json and expected_generation.json (PHASE4_SPEC.md section 5-6 / 9,
 * tests P4-T1 and P4-T3). */
public final class Phase4Fixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private Phase4Fixtures() {
    }

    public static GuardCases loadGuardCases(Path guardCasesJson) throws IOException {
        return MAPPER.readValue(requireFile(guardCasesJson).toFile(), GuardCases.class);
    }

    public static Map<String, ExpectedSection> loadExpectedGeneration(Path expectedGenerationJson) throws IOException {
        return MAPPER.readValue(requireFile(expectedGenerationJson).toFile(),
                MAPPER.getTypeFactory().constructMapType(Map.class, String.class, ExpectedSection.class));
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExpectedSection(
            List<Integer> slotLineCounts,
            Integer rounds,
            @JsonProperty("final") Map<String, ExpectedCandidate> finalResults,
            Map<String, ExpectedFeedback> round1Feedback,
            Double costUsd,
            String mode,
            String note) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExpectedCandidate(
            String status, Map<String, String> variants, Integer acceptedInRound, String reason, Integer attempts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExpectedFeedback(String reason, Integer targetLines, Integer measuredLines) {
    }
}
