package com.tailor.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds corpus/ and golden/. In the Docker image both live at /app/corpus and
 * /app/golden (Dockerfile: WORKDIR /app + COPY . /app, plus the user adding
 * corpus/ before building). Resolution order, most to least specific:
 *   1. system property (-Dtailor.corpusDir=..., -Dtailor.goldenDir=...)
 *   2. environment variable (TAILOR_CORPUS_DIR, TAILOR_GOLDEN_DIR)
 *   3. /app/<name> — the fixed Docker layout, checked directly rather than
 *      relying on the test JVM's working directory
 *   4. walking up from the working directory, as a last resort
 * If none of these find a real directory, the exception lists every path
 * tried and the actual working directory, so a failure is diagnosable from
 * Gradle's default (terse) test output instead of a bare exception with
 * nothing to go on.
 */
public final class CorpusPaths {

    private CorpusPaths() {
    }

    public static Path corpusDir() {
        return resolveDir("corpus", "tailor.corpusDir", "TAILOR_CORPUS_DIR");
    }

    public static Path goldenDir() {
        return resolveDir("golden", "tailor.goldenDir", "TAILOR_GOLDEN_DIR");
    }

    public static Path phase2FixturesDir() {
        return resolveDir("fixtures/phase2", "tailor.phase2FixturesDir", "TAILOR_PHASE2_FIXTURES_DIR");
    }

    public static Path phase3FixturesDir() {
        return resolveDir("fixtures/phase3", "tailor.phase3FixturesDir", "TAILOR_PHASE3_FIXTURES_DIR");
    }

    public static List<Path> corpusDocx() {
        try (Stream<Path> files = Files.list(corpusDir())) {
            return files.filter(p -> p.toString().endsWith(".docx"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Path resolveDir(String name, String sysProp, String envVar) {
        List<String> tried = new ArrayList<>();

        String fromSysProp = System.getProperty(sysProp);
        if (fromSysProp != null) {
            Path p = Path.of(fromSysProp);
            tried.add(p.toAbsolutePath() + " (from -D" + sysProp + ")");
            if (Files.isDirectory(p)) {
                return p;
            }
        }

        String fromEnv = System.getenv(envVar);
        if (fromEnv != null) {
            Path p = Path.of(fromEnv);
            tried.add(p.toAbsolutePath() + " (from $" + envVar + ")");
            if (Files.isDirectory(p)) {
                return p;
            }
        }

        Path dockerFixed = Path.of("/app", name);
        tried.add(dockerFixed + " (fixed Docker layout)");
        if (Files.isDirectory(dockerFixed)) {
            return dockerFixed;
        }

        Optional<Path> upwards = findUpwards(name, tried);
        if (upwards.isPresent()) {
            return upwards.get();
        }

        throw new IllegalStateException(
                name + "/ not found. Tried, in order:\n  " + String.join("\n  ", tried)
                        + "\nWorking directory was: " + Path.of("").toAbsolutePath());
    }

    private static Optional<Path> findUpwards(String dirName, List<String> tried) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(dirName);
            tried.add(candidate + " (walking up from working directory)");
            if (Files.isDirectory(candidate)) {
                return Optional.of(candidate);
            }
            dir = dir.getParent();
        }
        return Optional.empty();
    }
}
