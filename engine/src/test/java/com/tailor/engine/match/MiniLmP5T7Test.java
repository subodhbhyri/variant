package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.golden.Phase5Fixtures;
import com.tailor.engine.golden.Phase5Fixtures.AliasPairs;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * P5-T7 (PHASE5_SPEC.md section 10, manual): with the real MiniLM model, report the cosine for
 * every pair in {@code alias_pairs.json}, propose an alias-suggestion threshold, and report
 * embedding time per 100 bullets. This is a measurement/report, not a fixed pass/fail — per
 * section 12, a hard negative scoring above the threshold the aliases need is an escalation
 * signal to report, not something to paper over by loosening the threshold after the fact.
 *
 * <p>Needs the real model ({@code VARIANT_MODEL_DIR}) — {@code @Tag("live")}, excluded from
 * {@code test} and {@code corpusTest}; run explicitly via {@code gradle :engine:liveTest}.
 */
@Tag("live")
class MiniLmP5T7Test {

    @Test
    void reportAliasPairCosinesAndEmbeddingThroughput() throws Exception {
        AliasPairs pairs = Phase5Fixtures.loadAliasPairs(CorpusPaths.phase5FixturesDir().resolve("alias_pairs.json"));

        try (MiniLmEmbedder embedder = MiniLmEmbedder.loadFromEnv()) {
            List<Double> aliasScores = new ArrayList<>();
            StringBuilder report = new StringBuilder();

            report.append("Aliases:\n");
            for (List<String> pair : pairs.aliases()) {
                double score = embedder.similarity(pair.get(0), pair.get(1));
                aliasScores.add(score);
                report.append(String.format("  %-16s ~ %-16s cosine=%.4f%n", pair.get(0), pair.get(1), score));
            }

            List<Double> hardNegativeScores = new ArrayList<>();
            report.append("Hard negatives:\n");
            for (List<String> pair : pairs.hardNegatives()) {
                double score = embedder.similarity(pair.get(0), pair.get(1));
                hardNegativeScores.add(score);
                report.append(String.format("  %-16s ~ %-16s cosine=%.4f%n", pair.get(0), pair.get(1), score));
            }

            double minAlias = aliasScores.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
            double maxHardNegative = hardNegativeScores.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
            boolean cleanSeparation = maxHardNegative < minAlias;
            double proposedThreshold = cleanSeparation ? (minAlias + maxHardNegative) / 2 : minAlias;
            long aliasesClearing = aliasScores.stream().filter(s -> s >= proposedThreshold).count();
            long hardNegativesAboveMinAlias = hardNegativeScores.stream().filter(s -> s >= minAlias).count();

            report.append(String.format("min alias cosine=%.4f, max hard-negative cosine=%.4f%n", minAlias, maxHardNegative));
            report.append(String.format("clean separation=%s, proposed threshold=%.4f%n", cleanSeparation, proposedThreshold));
            report.append(aliasesClearing).append(" of ").append(aliasScores.size()).append(" aliases clear the proposed threshold\n");
            if (hardNegativesAboveMinAlias > 0) {
                report.append("ESCALATION (PHASE5_SPEC.md section 12): ").append(hardNegativesAboveMinAlias)
                        .append(" hard negative(s) score at or above the lowest alias score (")
                        .append(String.format("%.4f", minAlias)).append(") — no threshold cleanly separates them.\n");
            }

            List<String> throughputTexts = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                throughputTexts.add("Bullet sentence number " + i
                        + " describing a representative piece of resume achievement text for timing purposes.");
            }
            long start = System.nanoTime();
            for (String t : throughputTexts) {
                embedder.embed(t);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            report.append("embedding time per 100 bullets: ").append(elapsedMs).append(" ms\n");

            System.out.println(report);

            assertFalse(aliasScores.isEmpty(), "no alias pairs computed");
            assertFalse(hardNegativeScores.isEmpty(), "no hard-negative pairs computed");
            assertTrue(elapsedMs >= 0);
        }
    }
}
