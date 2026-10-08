package com.tailor.web.generation;

import com.tailor.engine.generate.ModelClient;

/**
 * Where generation gets its model. Production calls the Anthropic API with the key the worker
 * receives from Secrets Manager ({@code ANTHROPIC_API_KEY}); tests replay recorded responses.
 * Only the worker ever calls this (PHASE6_SPEC.md section 7).
 */
public interface ModelClientFactory {

    /** A client for one section's calls; the CLI makes one per section too. */
    ModelClient forSection(String sectionId);
}
