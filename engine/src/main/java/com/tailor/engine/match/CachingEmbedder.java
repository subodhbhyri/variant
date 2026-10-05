package com.tailor.engine.match;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * PHASE5_SPEC.md section 5.1 (step A): one posting's scoring session. Every answer it returns is
 * exactly the wrapped embedder's (or the uncached computation's) — caching only skips repeated
 * work, so output is unchanged. Holds per-posting memos for pairwise similarity and for {@link
 * Assembler}'s placement and project-score results, keyed by the inputs they depend on. Not
 * thread-safe: one session serves one posting, on one thread.
 */
public final class CachingEmbedder implements Embedder {

    private record SimilarityKey(String a, String b) {
    }

    private final Embedder delegate;
    private final Map<SimilarityKey, Double> similarities = new HashMap<>();
    private final Map<Object, Optional<?>> memos = new HashMap<>();

    public CachingEmbedder(Embedder delegate) {
        this.delegate = delegate;
    }

    @Override
    public double similarity(String a, String b) {
        SimilarityKey key = new SimilarityKey(a, b);
        Double cached = similarities.get(key);
        if (cached != null) {
            return cached;
        }
        double value = delegate.similarity(a, b);
        similarities.put(key, value);
        return value;
    }

    /** Returns the memoized result for {@code key}, computing it on first use. Null results are
     * memoized too (an infeasible placement is an answer, not a miss). Not reentrant-unsafe: the
     * supplier may call {@code memo} for other keys. */
    @SuppressWarnings("unchecked")
    <T> T memo(Object key, Supplier<T> compute) {
        Optional<?> cached = memos.get(key);
        if (cached != null) {
            return (T) cached.orElse(null);
        }
        T value = compute.get();
        memos.put(key, Optional.ofNullable(value));
        return value;
    }
}
