package com.tailor.engine.generate;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * PHASE5_SPEC.md section 1.1: the skills dictionary is bundled into the jar as a versioned
 * resource, not located by walking up from the working directory — {@link
 * SkillsDictionary#loadDefault()} must work regardless of the process's current directory.
 */
class SkillsDictionaryLoadDefaultTest {

    @Test
    void loadDefaultFindsTheBundledVersionedResource() throws Exception {
        SkillsDictionary skills = SkillsDictionary.loadDefault();
        assertTrue(TruthfulnessGuard.techs("Built services with Kubernetes and Python.", skills)
                .containsAll(java.util.Set.of("Kubernetes", "Python")));
    }

    @Test
    void loadBundledSetsAVersion() throws Exception {
        SkillsDictionary skills = SkillsDictionary.loadBundled();
        assertTrue(skills.version() != null && !skills.version().isBlank(),
                "bundled dictionary must declare _version");
    }
}
