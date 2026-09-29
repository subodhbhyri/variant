package com.tailor.engine.match;

/**
 * PHASE5_SPEC.md section 7 (step 5.2): sentence-similarity, injected so fixtures can use
 * {@link FakeEmbedder} and production uses {@code MiniLmEmbedder}. Implementations return a
 * value in {@code [0, 1]}.
 */
public interface Embedder {

    double similarity(String a, String b);
}
