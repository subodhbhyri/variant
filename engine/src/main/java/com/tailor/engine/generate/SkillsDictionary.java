package com.tailor.engine.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * PHASE4_SPEC.md section 5: the skills dictionary the truthfulness guard checks technology
 * mentions against — canonical term -> alias patterns, from fixtures/phase4/skills_seed.json
 * (Phase 5 grows the dictionary; embeddings only ever suggest additions).
 */
public final class SkillsDictionary {

    private final Map<String, List<Pattern>> patternsByCanonical;

    private SkillsDictionary(Map<String, List<Pattern>> patternsByCanonical) {
        this.patternsByCanonical = patternsByCanonical;
    }

    public static SkillsDictionary load(Path skillsJson) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(Files.readAllBytes(skillsJson));
        Map<String, List<Pattern>> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if ("_note".equals(e.getKey())) {
                continue;
            }
            List<Pattern> patterns = new ArrayList<>();
            for (JsonNode alias : e.getValue()) {
                patterns.add(aliasPattern(alias.asText()));
            }
            out.put(e.getKey(), patterns);
        }
        return new SkillsDictionary(out);
    }

    Map<String, List<Pattern>> patternsByCanonical() {
        return patternsByCanonical;
    }

    /** Aliases of <=2 characters (Go, JS, S3, ...) are case-sensitive; longer ones are
     * case-insensitive and word-bounded ("JavaScript" != "Java"). */
    private static Pattern aliasPattern(String alias) {
        int flags = alias.length() <= 2 ? 0 : Pattern.CASE_INSENSITIVE;
        return Pattern.compile("(?<![\\w+#.])" + Pattern.quote(alias) + "(?![\\w+#])", flags);
    }
}
