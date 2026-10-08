package com.tailor.web.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Where the repository's fixtures live in the test image (the Docker layout is /app). */
public final class Fixtures {

    private Fixtures() {
    }

    private static Path dir(String envName, String fallback) {
        String env = System.getenv(envName);
        return Path.of(env == null || env.isBlank() ? fallback : env);
    }

    public static Path phase2() {
        return dir("TAILOR_PHASE2_FIXTURES_DIR", "/app/fixtures/phase2");
    }

    public static Path phase4() {
        return dir("TAILOR_PHASE4_FIXTURES_DIR", "/app/fixtures/phase4");
    }

    public static Path phase5() {
        return dir("TAILOR_PHASE5_FIXTURES_DIR", "/app/fixtures/phase5");
    }

    public static Path corpus() {
        return dir("TAILOR_CORPUS_DIR", "/app/corpus");
    }

    public static byte[] phase2(String name) {
        try {
            return Files.readAllBytes(phase2().resolve(name));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<Path> corpusDocx() {
        try (Stream<Path> files = Files.list(corpus())) {
            return files.filter(p -> p.toString().endsWith(".docx")).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The Phase 2 fixtures' expected outcomes ({@code accept}, {@code reason}). */
    public static JsonNode phase2Expected() {
        try {
            return new ObjectMapper().readTree(phase2().resolve("expected.json").toFile());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
