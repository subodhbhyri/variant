package com.tailor.engine.match;

import java.util.Map;
import java.util.TreeSet;

/**
 * PHASE5_SPEC.md section 6 (step 5.7): a faithful port of {@code reference/jd_ref.py}'s
 * {@code weighted_jaccard} and {@code cache_decision}.
 */
public final class JdCache {

    private static final double JACCARD_HIT = 0.9;
    private static final double JACCARD_MAYBE = 0.8;
    private static final double SIMILARITY_HIT = 0.95;

    private JdCache() {
    }

    public static double weightedJaccard(Map<String, Double> a, Map<String, Double> b) {
        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(a.keySet());
        keys.addAll(b.keySet());
        double num = 0;
        double den = 0;
        for (String k : keys) {
            double av = a.getOrDefault(k, 0.0);
            double bv = b.getOrDefault(k, 0.0);
            num += Math.min(av, bv);
            den += Math.max(av, bv);
        }
        return den == 0 ? 1.0 : num / den;
    }

    public static CacheDecision decide(JobDescription a, JobDescription b, Embedder embedder) {
        double j = weightedJaccard(a.skills(), b.skills());
        if (a.skills().equals(b.skills()) || j >= JACCARD_HIT) {
            return new CacheDecision("HIT", Round.to4(j));
        }
        if (j >= JACCARD_MAYBE && embedder.similarity(a.requirementText(), b.requirementText()) >= SIMILARITY_HIT) {
            return new CacheDecision("HIT", Round.to4(j));
        }
        return new CacheDecision("MISS", Round.to4(j));
    }
}
