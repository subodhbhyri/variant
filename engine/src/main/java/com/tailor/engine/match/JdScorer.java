package com.tailor.engine.match;

import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.generate.TruthfulnessGuard;
import java.util.Set;

/**
 * PHASE5_SPEC.md section 2 (step 5.3): a faithful port of {@code reference/jd_ref.py}'s
 * {@code keyword_coverage} and {@code score}.
 */
public final class JdScorer {

    static final double W_KEYWORD = 0.7;
    static final double W_EMBED = 0.3;

    private JdScorer() {
    }

    public static double keywordCoverage(String text, JobDescription jd, SkillsDictionary skills) {
        double total = jd.skills().values().stream().mapToDouble(Double::doubleValue).sum();
        if (total == 0) {
            return 0.0;
        }
        Set<String> present = TruthfulnessGuard.techs(text, skills);
        double sum = 0;
        for (var e : jd.skills().entrySet()) {
            if (present.contains(e.getKey())) {
                sum += e.getValue();
            }
        }
        return sum / total;
    }

    public static double score(String text, JobDescription jd, SkillsDictionary skills, Embedder embedder) {
        double kw = keywordCoverage(text, jd, skills);
        double sim = embedder.similarity(text, jd.requirementText());
        return Round.to4(W_KEYWORD * kw + W_EMBED * sim);
    }
}
