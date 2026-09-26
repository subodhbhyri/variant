package com.tailor.engine.golden;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Expected positions/swaps, from fixtures/phase3/expected.json and golden/phase3_blocks.json (PHASE3_SPEC.md section 9). */
public final class Phase3Fixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private Phase3Fixtures() {
    }

    /** fixtures/phase3/expected.json: one resume's sections/positions/swaps. */
    public static Expected loadExpected(Path expectedJson) throws IOException {
        return MAPPER.readValue(requireFile(expectedJson).toFile(), Expected.class);
    }

    /** golden/phase3_blocks.json: one entry per corpus resume, plus a "_rotation" entry (read separately). */
    public static Map<String, Expected> loadGolden(Path goldenJson) throws IOException {
        JsonNode root = MAPPER.readTree(requireFile(goldenJson).toFile());
        Map<String, Expected> out = new java.util.LinkedHashMap<>();
        var it = root.fields();
        while (it.hasNext()) {
            var e = it.next();
            if ("_rotation".equals(e.getKey())) {
                continue;
            }
            out.put(e.getKey(), MAPPER.treeToValue(e.getValue(), Expected.class));
        }
        return out;
    }

    public static Rotation loadRotation(Path goldenJson) throws IOException {
        JsonNode root = MAPPER.readTree(requireFile(goldenJson).toFile());
        return MAPPER.treeToValue(root.get("_rotation"), Rotation.class);
    }

    private static Path requireFile(Path p) throws IOException {
        if (!Files.isRegularFile(p)) {
            throw new IOException("phase3 fixture not found: " + p.toAbsolutePath());
        }
        return p;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expected(
            List<ExpectedSection> sections,
            String projectsSection,
            List<ExpectedPosition> positions,
            Map<String, ExpectedSwap> swaps,
            String note) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExpectedSection(String heading, String role) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExpectedPosition(
            String kind,
            boolean swappable,
            Integer bullets,
            String reason,
            ExpectedHeader header,
            List<Integer> segmentsPerBullet) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExpectedHeader(List<String> fields, boolean detailIsList, Integer links, String dateMode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExpectedSwap(String outcome, JsonNode stackItemsKept, Integer paddedBullets) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rotation(String note, Map<String, List<Integer>> skipped) {
    }

    /** Deserializes one JSON value with the same mapper/naming strategy, for ad hoc lookups. */
    public static <T> T convert(JsonNode node, Class<T> type) throws IOException {
        return MAPPER.treeToValue(node, type);
    }
}
