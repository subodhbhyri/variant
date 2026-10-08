package com.tailor.renderer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Finds the 9-resume corpus: TAILOR_CORPUS_DIR, else the Docker layout's /app/corpus. */
final class Corpus {

    private Corpus() {
    }

    static List<Path> docx() throws IOException {
        String env = System.getenv("TAILOR_CORPUS_DIR");
        Path dir = Path.of(env == null || env.isBlank() ? "/app/corpus" : env);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("corpus directory not found: " + dir);
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".docx")).sorted().toList();
        }
    }
}
