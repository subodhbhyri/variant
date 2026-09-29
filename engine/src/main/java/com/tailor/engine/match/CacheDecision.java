package com.tailor.engine.match;

/** PHASE5_SPEC.md section 6: cache lookup outcome for a parsed job description against a
 * previously cached one — {@code decision} is {@code "HIT"} or {@code "MISS"}. */
public record CacheDecision(String decision, double weightedJaccard) {
}
