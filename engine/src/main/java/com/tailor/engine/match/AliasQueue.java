package com.tailor.engine.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * PHASE5_SPEC.md section 7.1 / D5: unknown terms (section 1.1) queued for {@code tailor aliases
 * review} — never applied automatically, only ever reviewed by the operator. A flat JSON array of
 * term strings, deduplicated on add. Location is {@code VARIANT_ALIAS_QUEUE} if set, else a fixed
 * relative default (mirrors {@code VARIANT_MODEL_DIR}/{@code VARIANT_SKILLS_DICT}'s naming).
 */
public final class AliasQueue {

    private static final String PATH_ENV_VAR = "VARIANT_ALIAS_QUEUE";
    private static final String DEFAULT_PATH = "alias-queue.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AliasQueue() {
    }

    public static Path resolvePath() {
        String override = System.getenv(PATH_ENV_VAR);
        return Path.of(override != null && !override.isBlank() ? override : DEFAULT_PATH);
    }

    public static Set<String> load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return new LinkedHashSet<>();
        }
        List<String> terms = MAPPER.readValue(path.toFile(),
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        return new LinkedHashSet<>(terms);
    }

    /** Merges {@code newTerms} into the queue at {@code path}, deduplicated; a no-op if empty. */
    public static void addAll(Path path, Set<String> newTerms) throws IOException {
        if (newTerms.isEmpty()) {
            return;
        }
        Set<String> current = load(path);
        current.addAll(newTerms);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), current);
    }
}
