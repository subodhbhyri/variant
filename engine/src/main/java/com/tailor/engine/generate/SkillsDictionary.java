package com.tailor.engine.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * PHASE4_SPEC.md section 5 / PHASE5_SPEC.md section 1.1: the skills dictionary the truthfulness
 * guard and job-description matcher check technology mentions against — canonical term -> alias
 * patterns. This is data shipped with the product: bundled into the jar as a versioned resource
 * ({@link #loadDefault}), never located by walking up from the working directory (the runtime
 * sandbox ships no fixtures). {@code VARIANT_SKILLS_DICT}, if set, overrides the bundled resource
 * with an external file — for the operator's own growing dictionary, or fixtures/tests.
 */
public final class SkillsDictionary {

    private static final String BUNDLED_RESOURCE = "/skills/skills_dictionary.json";
    private static final String OVERRIDE_ENV_VAR = "VARIANT_SKILLS_DICT";

    private final Map<String, List<Pattern>> patternsByCanonical;
    private final String version;
    private final Map<String, List<String>> implies;
    private final Map<String, Set<String>> techsByText = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> techsImpliedByText = new ConcurrentHashMap<>();

    private SkillsDictionary(Map<String, List<Pattern>> patternsByCanonical, String version,
            Map<String, List<String>> implies) {
        this.patternsByCanonical = patternsByCanonical;
        this.version = version;
        this.implies = implies;
    }

    /** {@code VARIANT_SKILLS_DICT} if set, else the bundled, versioned resource
     * (PHASE5_SPEC.md section 1.1). */
    public static SkillsDictionary loadDefault() throws IOException {
        String override = System.getenv(OVERRIDE_ENV_VAR);
        if (override != null && !override.isBlank()) {
            return load(Path.of(override));
        }
        return loadBundled();
    }

    public static SkillsDictionary loadBundled() throws IOException {
        try (InputStream in = SkillsDictionary.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(BUNDLED_RESOURCE + " not on classpath");
            }
            return parse(new ObjectMapper().readTree(in));
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    public static SkillsDictionary load(Path skillsJson) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(Files.readAllBytes(skillsJson));
        return parse(root);
    }

    private static SkillsDictionary parse(JsonNode root) {
        Set<String> caseSensitive = new HashSet<>();
        if (root.has("_case_sensitive")) {
            for (JsonNode alias : root.get("_case_sensitive")) {
                caseSensitive.add(alias.asText());
            }
        }

        Map<String, List<String>> implies = new LinkedHashMap<>();
        if (root.has("_implies")) {
            Iterator<Map.Entry<String, JsonNode>> impliesIt = root.get("_implies").fields();
            while (impliesIt.hasNext()) {
                Map.Entry<String, JsonNode> e = impliesIt.next();
                List<String> targets = new ArrayList<>();
                for (JsonNode t : e.getValue()) {
                    targets.add(t.asText());
                }
                implies.put(e.getKey(), targets);
            }
        }

        Map<String, List<Pattern>> out = new LinkedHashMap<>();
        String version = null;
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().startsWith("_")) {
                if ("_version".equals(e.getKey())) {
                    version = e.getValue().asText();
                }
                continue;
            }
            List<Pattern> patterns = new ArrayList<>();
            for (JsonNode alias : e.getValue()) {
                String a = alias.asText();
                patterns.add(aliasPattern(a, caseSensitive.contains(a)));
            }
            out.put(e.getKey(), patterns);
        }
        return new SkillsDictionary(out, version, implies);
    }

    Map<String, List<Pattern>> patternsByCanonical() {
        return patternsByCanonical;
    }

    /** Every canonical term name, for alias-suggestion nearest-neighbour search (section 7.1). */
    public java.util.Set<String> canonicalTerms() {
        return patternsByCanonical.keySet();
    }

    /** The dictionary's {@code _version} field, or null if the loaded file didn't set one (an
     * operator override, or an older fixture). */
    public String version() {
        return version;
    }

    /** PHASE5_SPEC.md section 8.1 (dictionary v2.1, reference {@code _implies}): a skill that
     * unambiguously implies broader ones (PostgreSQL implies SQL) -> canonical term -> the terms
     * it implies directly (applied transitively by {@link
     * TruthfulnessGuard#techsImplied}). Empty (never null) for a dictionary with no such key. */
    Map<String, Set<String>> techsByText() {
        return techsByText;
    }

    Map<String, Set<String>> techsImpliedByText() {
        return techsImpliedByText;
    }

    public Map<String, List<String>> implies() {
        return implies;
    }

    /** Aliases of <=2 characters (Go, JS, S3, ...) are always case-sensitive; so is any alias
     * listed in {@code _case_sensitive} (PHASE5_SPEC.md section 1.1) — one that doubles as
     * ordinary English (React, Swift, Spring, Go, Express, Node, Lambda, ...), where a
     * case-insensitive match would fire on the English word instead of the skill. Everything
     * else is case-insensitive and word-bounded ("JavaScript" != "Java"). */
    private static Pattern aliasPattern(String alias, boolean forceCaseSensitive) {
        int flags = (forceCaseSensitive || alias.length() <= 2) ? 0 : Pattern.CASE_INSENSITIVE;
        return Pattern.compile("(?<![\\w+#.])" + Pattern.quote(alias) + "(?![\\w+#])", flags);
    }
}
