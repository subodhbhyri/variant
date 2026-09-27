package com.tailor.engine.generate;

import java.util.List;
import java.util.Map;

/** PHASE4_SPEC.md section 3: one {@code submit_bullets} call's result. */
public record ModelResponse(List<BulletCandidate> bullets, Usage usage) {

    public record BulletCandidate(String id, Map<String, String> variants) {
    }

    /** PHASE4_SPEC.md section 7: the four usage fields cost accounting needs. */
    public record Usage(int inputTokens, int outputTokens, int cacheCreationInputTokens, int cacheReadInputTokens) {
    }
}
