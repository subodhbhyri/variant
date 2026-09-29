package com.tailor.engine.golden;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.generate.ModelResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** fixtures/phase5/expected_selection.json and job_variants.json (PHASE5_SPEC.md, produced by
 * reference/jd_ref.py with fake_similarity standing in for MiniLM — tests P5-T1 through P5-T5,
 * P5-T9). */
public final class Phase5Fixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /** job_variants.json uses plain field names ("id", "variants"), not snake_case. */
    private static final ObjectMapper PLAIN_MAPPER = new ObjectMapper();

    private Phase5Fixtures() {
    }

    public static ExpectedSelection loadExpectedSelection(Path expectedSelectionJson) throws IOException {
        return MAPPER.readValue(requireFile(expectedSelectionJson).toFile(), ExpectedSelection.class);
    }

    /** -> job id (e.g. "northwind") -> its stored bullet candidates. */
    public static Map<String, List<ModelResponse.BulletCandidate>> loadJobVariants(Path jobVariantsJson)
            throws IOException {
        var types = PLAIN_MAPPER.getTypeFactory();
        var listType = types.constructCollectionType(List.class, ModelResponse.BulletCandidate.class);
        var mapType = types.constructMapType(Map.class, types.constructType(String.class), listType);
        return PLAIN_MAPPER.readValue(requireFile(jobVariantsJson).toFile(), mapType);
    }

    private static Path requireFile(Path p) throws IOException {
        if (!Files.isRegularFile(p)) {
            throw new IOException("phase5 fixture not found: " + p.toAbsolutePath());
        }
        return p;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExpectedSelection(
            Map<String, String> achievementGroups, Map<String, ExpectedJd> jds, List<CacheCase> cache) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExpectedJd(
            String title, Map<String, Double> skills, List<ExpectedResume> resumes, List<String> missing,
            Map<String, Double> scoresJob) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExpectedResume(String label, List<String> job, List<ExpectedProjectAssignment> projects, Double total) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExpectedProjectAssignment(
            String position, String project, List<Integer> bullets, Double score, List<String> stack) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CacheCase(String a, String b, String decision, Double weightedJaccard) {
    }
}
