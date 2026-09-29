package com.tailor.engine.match;

import com.tailor.engine.blocks.LibraryProject;
import com.tailor.engine.generate.ModelResponse.BulletCandidate;
import com.tailor.engine.generate.SkillsDictionary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * PHASE5_SPEC.md section 4 (step 5.5): a faithful port of {@code reference/jd_ref.py}'s
 * {@code top3}. Resume #1 = {@link Assembler#assemble}; each alternative is the best resume that
 * includes one item resume #1 left out — an unused library project, or an unused job achievement
 * group — with everything else re-optimized. Ranked by total score; at most 2 offered.
 */
public final class Alternatives {

    private record Option(double total, String label, AssembledResume resume) {
    }

    private Alternatives() {
    }

    public static List<AssembledResume> top3(Shapes shapes, List<BulletCandidate> jobCands,
            List<LibraryProject> library, JobDescription jd, SkillsDictionary skills, Embedder embedder) {
        Set<String> noPenalty = Set.of();
        AssembledResume first = Assembler.assemble(shapes, jobCands, library, jd, skills, embedder, noPenalty);
        Map<String, String> groups = Assembler.achievementGroups(jobCands);

        Set<String> usedGroups = new LinkedHashSet<>();
        for (String c : first.job()) {
            if (c != null) {
                usedGroups.add(groups.get(c));
            }
        }
        Set<String> usedProjects = new LinkedHashSet<>();
        for (AssembledResume.ProjectAssignment d : first.projects()) {
            usedProjects.add(d.project());
        }

        List<Option> options = new ArrayList<>();

        // force an unused project in
        for (LibraryProject p : library) {
            if (usedProjects.contains(p.id())) {
                continue;
            }
            Double bestTotal = null;
            int bestPosI = -1;
            AssembledResume bestResume = null;
            for (int posI = 0; posI < shapes.positions().size(); posI++) {
                List<LibraryProject> others = new ArrayList<>();
                for (LibraryProject q : library) {
                    if (!q.id().equals(p.id())) {
                        others.add(q);
                    }
                }
                Assembler.ProjectScoreResult fixed =
                        Assembler.projectScore(p, shapes.positions().get(posI), jd, skills, embedder);
                if (fixed == null) {
                    continue;
                }
                List<Shapes.PositionShape> restPositions = new ArrayList<>();
                for (int i = 0; i < shapes.positions().size(); i++) {
                    if (i != posI) {
                        restPositions.add(shapes.positions().get(i));
                    }
                }
                List<AssembledResume.ProjectAssignment> rest =
                        Assembler.assignProjects(restPositions, others, jd, skills, embedder, Set.of());
                if (rest.size() != restPositions.size()) {
                    continue;
                }
                List<AssembledResume.ProjectAssignment> merged = new ArrayList<>(rest);
                merged.add(new AssembledResume.ProjectAssignment(shapes.positions().get(posI).id(), p.id(),
                        fixed.bullets(), fixed.score(), null));
                List<String> positionOrder = shapes.positions().stream().map(Shapes.PositionShape::id).toList();
                merged.sort(Comparator.comparingInt(d -> positionOrder.indexOf(d.position())));

                AssembledResume cand = new AssembledResume(first.job(), merged, null, null);
                double t = Assembler.totalScore(cand, shapes, jobCands, jd, skills, embedder);
                if (bestTotal == null || t > bestTotal || (t == bestTotal && posI < bestPosI)) {
                    bestTotal = t;
                    bestPosI = posI;
                    bestResume = cand;
                }
            }
            if (bestResume != null) {
                Set<String> keptProjects = new LinkedHashSet<>();
                for (AssembledResume.ProjectAssignment d : bestResume.projects()) {
                    keptProjects.add(d.project());
                }
                List<String> dropped = new ArrayList<>(new TreeSet<>(diff(usedProjects, keptProjects)));
                String label = "includes " + p.id()
                        + (dropped.isEmpty() ? "" : " instead of " + String.join(", ", dropped));
                options.add(new Option(bestTotal, label, bestResume));
            }
        }

        // force an unused achievement in
        Set<String> allGroups = new TreeSet<>(groups.values());
        allGroups.removeAll(usedGroups);
        for (String g : allGroups) {
            List<BulletCandidate> members = new ArrayList<>();
            for (BulletCandidate c : jobCands) {
                if (g.equals(groups.get(c.id()))) {
                    members.add(c);
                }
            }
            List<Integer> slots = shapes.job().slots();
            Double bestTotal = null;
            AssembledResume bestResume = null;
            for (int i = 0; i < slots.size(); i++) {
                int length = slots.get(i);
                List<BulletCandidate> m = new ArrayList<>();
                for (BulletCandidate c : members) {
                    if (c.variants().containsKey(String.valueOf(length))) {
                        m.add(c);
                    }
                }
                if (m.isEmpty()) {
                    continue;
                }
                final int lengthFinal = length;
                m.sort(Comparator
                        .comparingDouble((BulletCandidate c) -> -JdScorer.score(
                                c.variants().get(String.valueOf(lengthFinal)), jd, skills, embedder))
                        .thenComparing(BulletCandidate::id));

                List<String> job = new ArrayList<>(first.job());
                job.set(i, m.get(0).id());
                AssembledResume cand = new AssembledResume(job, first.projects(), null, null);
                double t = Assembler.totalScore(cand, shapes, jobCands, jd, skills, embedder);
                if (bestTotal == null || t > bestTotal) {
                    bestTotal = t;
                    bestResume = cand;
                }
            }
            if (bestResume != null) {
                options.add(new Option(bestTotal, "includes achievement " + g, bestResume));
            }
        }

        options.sort(Comparator.comparingDouble((Option o) -> -o.total()).thenComparing(Option::label));

        List<AssembledResume> out = new ArrayList<>();
        out.add(new AssembledResume(first.job(), first.projects(), "best match",
                Assembler.totalScore(first, shapes, jobCands, jd, skills, embedder)));
        for (int i = 0; i < Math.min(2, options.size()); i++) {
            Option o = options.get(i);
            out.add(new AssembledResume(o.resume().job(), o.resume().projects(), o.label(), o.total()));
        }
        return out;
    }

    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return out;
    }
}
