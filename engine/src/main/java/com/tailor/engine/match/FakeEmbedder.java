package com.tailor.engine.match;

import com.tailor.engine.generate.TruthfulnessGuard;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PHASE5_SPEC.md section 2: {@code fake_similarity} from {@code reference/jd_ref.py} — cosine of
 * content-stem counts (Phase 4's {@code content_stems}), a fixture stand-in for MiniLM. Fixture
 * results must be reproduced exactly with this class.
 */
public final class FakeEmbedder implements Embedder {

    @Override
    public double similarity(String a, String b) {
        Map<String, Integer> ca = counts(TruthfulnessGuard.contentStems(a));
        Map<String, Integer> cb = counts(TruthfulnessGuard.contentStems(b));
        double dot = 0;
        for (Map.Entry<String, Integer> e : ca.entrySet()) {
            dot += e.getValue() * cb.getOrDefault(e.getKey(), 0);
        }
        double na = norm(ca.values());
        double nb = norm(cb.values());
        return (na == 0 || nb == 0) ? 0.0 : dot / (na * nb);
    }

    private static Map<String, Integer> counts(List<String> stems) {
        Map<String, Integer> m = new HashMap<>();
        for (String s : stems) {
            m.merge(s, 1, Integer::sum);
        }
        return m;
    }

    private static double norm(java.util.Collection<Integer> values) {
        double sumSquares = 0;
        for (int v : values) {
            sumSquares += (double) v * v;
        }
        return Math.sqrt(sumSquares);
    }
}
