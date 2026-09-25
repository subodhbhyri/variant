package com.tailor.engine.golden;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** One resume's expected answer, from golden/*.json (produced by reference/make_golden.py). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoldenResume(
        String file,
        int squeezeRemoved,
        double shrinkPt,
        int pages,
        Map<String, Object> environment,
        List<GoldenBullet> bullets) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    public static GoldenResume load(Path jsonPath) throws IOException {
        if (!Files.isRegularFile(jsonPath)) {
            throw new IOException("golden file not found: " + jsonPath.toAbsolutePath());
        }
        return MAPPER.readValue(jsonPath.toFile(), GoldenResume.class);
    }
}
