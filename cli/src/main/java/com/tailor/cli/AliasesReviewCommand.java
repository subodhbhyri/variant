package com.tailor.cli;

import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.match.AliasQueue;
import com.tailor.engine.match.AliasRules;
import com.tailor.engine.match.Embedder;
import com.tailor.engine.match.FakeEmbedder;
import com.tailor.engine.match.MiniLmEmbedder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code tailor aliases review [--embedder fake|minilm]} — PHASE5_SPEC.md section 7.1
 * (revision 2). For every unknown term in the queue ({@link AliasQueue}), shows two suggestion
 * tiers: high-confidence spelling rules ({@link AliasRules}) and low-confidence MiniLM nearest
 * neighbours. Read-only — nothing is applied automatically; the operator edits the dictionary
 * file themselves once they've decided.
 */
@Command(name = "review", description = "Shows queued unknown terms with both alias-suggestion tiers.")
public final class AliasesReviewCommand implements Callable<Integer> {

    @Option(names = "--embedder", description = "fake (tests) or minilm (production)", defaultValue = "minilm")
    private String embedderName;

    private record Neighbor(String canonical, double cosine) {
    }

    @Override
    public Integer call() {
        try {
            Path queuePath = AliasQueue.resolvePath();
            Set<String> queued = AliasQueue.load(queuePath);
            if (queued.isEmpty()) {
                System.out.println("no unknown terms queued (" + queuePath.toAbsolutePath() + ")");
                return 0;
            }

            SkillsDictionary skills = SkillsDictionary.loadDefault();
            MiniLmEmbedder miniLm = null;
            Embedder embedder;
            if ("minilm".equals(embedderName)) {
                miniLm = MiniLmEmbedder.loadFromEnv();
                embedder = miniLm;
            } else if ("fake".equals(embedderName)) {
                embedder = new FakeEmbedder();
            } else {
                System.err.println("aliases review failed: --embedder must be fake or minilm (got " + embedderName + ")");
                return 1;
            }

            try {
                List<String> canonicalTerms = new ArrayList<>(new TreeSet<>(skills.canonicalTerms()));
                for (String term : queued) {
                    System.out.println("Unknown term: " + term);

                    List<String> highConfidence = new ArrayList<>();
                    for (String canonical : canonicalTerms) {
                        List<String> rules = AliasRules.rules(term, canonical);
                        if (!rules.isEmpty()) {
                            highConfidence.add(canonical + " (" + String.join(",", rules) + ")");
                        }
                    }
                    System.out.println("  high-confidence (spelling rules): "
                            + (highConfidence.isEmpty() ? "(none)" : String.join(", ", highConfidence)));

                    List<Neighbor> neighbors = new ArrayList<>();
                    for (String canonical : canonicalTerms) {
                        neighbors.add(new Neighbor(canonical, embedder.similarity(term, canonical)));
                    }
                    neighbors.sort(Comparator.comparingDouble(Neighbor::cosine).reversed());
                    String hints = neighbors.stream().limit(3)
                            .map(n -> String.format("%s (%.4f)", n.canonical(), n.cosine()))
                            .collect(Collectors.joining(", "));
                    System.out.println("  low-confidence (MiniLM nearest 3): " + hints);
                }
                return 0;
            } finally {
                if (miniLm != null) {
                    miniLm.close();
                }
            }
        } catch (Exception e) {
            System.err.println("aliases review failed: " + e);
            return 1;
        }
    }
}
