package com.tailor.engine.match;

import com.tailor.engine.blocks.LibraryProject;
import com.tailor.engine.generate.ModelResponse.BulletCandidate;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.generate.TruthfulnessGuard;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PHASE5_SPEC.md sections 2-4 (steps 5.3-5.5): a faithful port of {@code reference/jd_ref.py}'s
 * {@code fill_job}, {@code place_project}, {@code project_score}, {@code assign_projects},
 * {@code order_stack}, {@code assemble}, {@code achievement_groups} and {@code total_score}.
 */
public final class Assembler {

    // alternatives: a reused item scores half, so relevance order survives (defined for fidelity
    // with the reference's fill_job/assign_projects signatures; PHASE5_SPEC.md section 4 notes a
    // fixed reuse penalty was tried for alternatives and rejected, so top3() never passes a
    // non-empty penalty set — REUSE_FACTOR is exercised only if a future caller does).
    static final double REUSE_FACTOR = 0.5;
    // two job candidates sharing >= 50% content words (either direction) are one achievement.
    static final double SAME_ACHIEVEMENT = 0.5;
    // project score = mean bullet score + 0.3 x keyword coverage of its stack.
    static final double DETAIL_WEIGHT = 0.3;

    record PlaceResult(List<Integer> bullets, double meanScore) {
    }

    record ProjectScoreResult(double score, List<Integer> bullets) {
    }

    private Assembler() {
    }

    // --- 5.4 fill_job -------------------------------------------------------------------------

    public static List<String> fillJob(List<Integer> slots, List<BulletCandidate> candidates, JobDescription jd,
            SkillsDictionary skills, Embedder embedder, Set<String> penalty) {
        String[] chosen = new String[slots.size()];
        List<BulletCandidate> taken = new ArrayList<>();
        List<Integer> distinctLengths = slots.stream().distinct().sorted().toList();
        for (int length : distinctLengths) {
            for (int i = 0; i < slots.size(); i++) {
                if (slots.get(i) != length) {
                    continue;
                }
                List<BulletCandidate> eligible = new ArrayList<>();
                for (BulletCandidate c : candidates) {
                    if (!c.variants().containsKey(String.valueOf(length))) {
                        continue;
                    }
                    boolean sameAsTaken = false;
                    for (BulletCandidate t : taken) {
                        if (sameAchievement(c, t)) {
                            sameAsTaken = true;
                            break;
                        }
                    }
                    if (!sameAsTaken) {
                        eligible.add(c);
                    }
                }
                final int lengthFinal = length;
                eligible.sort(Comparator
                        .comparingDouble((BulletCandidate c) -> -val(c, lengthFinal, jd, skills, embedder, penalty))
                        .thenComparing(BulletCandidate::id));
                if (!eligible.isEmpty()) {
                    BulletCandidate best = eligible.get(0);
                    chosen[i] = best.id();
                    taken.add(best);
                }
            }
        }
        List<String> out = new ArrayList<>(slots.size());
        for (String s : chosen) {
            out.add(s);
        }
        return out;
    }

    private static double val(BulletCandidate c, int length, JobDescription jd, SkillsDictionary skills,
            Embedder embedder, Set<String> penalty) {
        double v = JdScorer.score(c.variants().get(String.valueOf(length)), jd, skills, embedder);
        double factor = penalty.contains(c.id()) ? REUSE_FACTOR : 1.0;
        return Round.to4(v * factor);
    }

    /** One achievement written two ways (Phase 4 allows restating). */
    static boolean sameAchievement(BulletCandidate a, BulletCandidate b) {
        String ta = String.join(" ", a.variants().values());
        String tb = String.join(" ", b.variants().values());
        return TruthfulnessGuard.grounding(ta, List.of(tb)) >= SAME_ACHIEVEMENT
                || TruthfulnessGuard.grounding(tb, List.of(ta)) >= SAME_ACHIEVEMENT;
    }

    /** Cluster job candidates that restate one achievement -> {candidate id: group id}. */
    public static Map<String, String> achievementGroups(List<BulletCandidate> candidates) {
        List<BulletCandidate> sortedById = new ArrayList<>(candidates);
        sortedById.sort(Comparator.comparing(BulletCandidate::id));
        Map<String, String> group = new LinkedHashMap<>();
        for (BulletCandidate c : sortedById) {
            String g = c.id();
            for (BulletCandidate o : candidates) {
                if (group.containsKey(o.id()) && sameAchievement(c, o)) {
                    g = group.get(o.id());
                    break;
                }
            }
            group.put(c.id(), g);
        }
        return group;
    }

    // --- 5.4 place_project / project_score / assign_projects ----------------------------------

    static PlaceResult placeProject(LibraryProject project, List<Integer> shape, JobDescription jd,
            SkillsDictionary skills, Embedder embedder) {
        List<Map<String, String>> bullets = project.bullets();
        int n = bullets.size();
        int r = shape.size();
        if (n < r) {
            return null;
        }
        int[] bestPerm = null;
        double bestNegTotal = Double.POSITIVE_INFINITY;
        double bestTotal = Double.NaN;
        for (int[] perm : Permutations.of(n, r)) {
            boolean skip = false;
            double total = 0;
            for (int i = 0; i < r; i++) {
                String v = bullets.get(perm[i]).get(String.valueOf(shape.get(i)));
                if (v == null) {
                    skip = true;
                    break;
                }
                total += JdScorer.score(v, jd, skills, embedder);
            }
            if (skip) {
                continue;
            }
            double negTotal = -Round.to4(total);
            if (bestPerm == null || negTotal < bestNegTotal) {
                bestPerm = perm.clone();
                bestNegTotal = negTotal;
                bestTotal = total;
            }
        }
        if (bestPerm == null) {
            return null;
        }
        List<Integer> bulletsOut = new ArrayList<>(r);
        for (int b : bestPerm) {
            bulletsOut.add(b);
        }
        return new PlaceResult(bulletsOut, Round.to4(bestTotal / r));
    }

    static ProjectScoreResult projectScore(LibraryProject project, Shapes.PositionShape position, JobDescription jd,
            SkillsDictionary skills, Embedder embedder) {
        PlaceResult placed = placeProject(project, position.shape(), jd, skills, embedder);
        if (placed == null) {
            return null;
        }
        double s = placed.meanScore();
        if (position.showsDetail()) {
            String detail = project.detail() == null ? "" : project.detail();
            s += DETAIL_WEIGHT * JdScorer.keywordCoverage(detail, jd, skills);
        }
        return new ProjectScoreResult(Round.to4(s), placed.bullets());
    }

    public static List<AssembledResume.ProjectAssignment> assignProjects(List<Shapes.PositionShape> positions,
            List<LibraryProject> library, JobDescription jd, SkillsDictionary skills, Embedder embedder,
            Set<String> penalty) {
        List<String> ids = library.stream().map(LibraryProject::id).toList();
        int n = library.size();
        int k = positions.size();

        List<Double> bestNegScores = null;
        List<String> bestIds = null;
        List<AssembledResume.ProjectAssignment> bestDetail = null;

        for (int[] combo : Permutations.of(n, k)) {
            List<Double> effScores = new ArrayList<>();
            List<String> comboIds = new ArrayList<>();
            List<AssembledResume.ProjectAssignment> detail = new ArrayList<>();
            boolean ok = true;
            for (int i = 0; i < k; i++) {
                Shapes.PositionShape pos = positions.get(i);
                int libI = combo[i];
                ProjectScoreResult r = projectScore(library.get(libI), pos, jd, skills, embedder);
                if (r == null) {
                    ok = false;
                    break;
                }
                double eff = Round.to4(r.score() * (penalty.contains(ids.get(libI)) ? REUSE_FACTOR : 1.0));
                effScores.add(-eff);
                comboIds.add(ids.get(libI));
                detail.add(new AssembledResume.ProjectAssignment(pos.id(), ids.get(libI), r.bullets(), r.score(),
                        null));
            }
            if (!ok) {
                continue;
            }
            if (bestNegScores == null || isBetterKey(effScores, comboIds, bestNegScores, bestIds)) {
                bestNegScores = effScores;
                bestIds = comboIds;
                bestDetail = detail;
            }
        }
        return bestDetail == null ? List.of() : bestDetail;
    }

    private static boolean isBetterKey(List<Double> negScores, List<String> ids, List<Double> bestNegScores,
            List<String> bestIds) {
        int cmp = compareLexDouble(negScores, bestNegScores);
        if (cmp != 0) {
            return cmp < 0;
        }
        return compareLexString(ids, bestIds) < 0;
    }

    static int compareLexDouble(List<Double> a, List<Double> b) {
        for (int i = 0; i < a.size(); i++) {
            int c = Double.compare(a.get(i), b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    static int compareLexString(List<String> a, List<String> b) {
        for (int i = 0; i < a.size(); i++) {
            int c = a.get(i).compareTo(b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    // --- order_stack ----------------------------------------------------------------------------

    static List<String> orderStack(String detail, JobDescription jd, SkillsDictionary skills) {
        List<String> items = new ArrayList<>();
        if (detail != null) {
            for (String x : detail.split(",", -1)) {
                String t = x.strip();
                if (!t.isEmpty()) {
                    items.add(t);
                }
            }
        }
        List<String> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingDouble((String it) -> -maxWeight(it, jd, skills)));
        return sorted;
    }

    private static double maxWeight(String item, JobDescription jd, SkillsDictionary skills) {
        double max = 0;
        for (String t : TruthfulnessGuard.techs(item, skills)) {
            max = Math.max(max, jd.skills().getOrDefault(t, 0.0));
        }
        return max;
    }

    // --- 5.4 assemble ---------------------------------------------------------------------------

    public static AssembledResume assemble(Shapes shapes, List<BulletCandidate> jobCands,
            List<LibraryProject> library, JobDescription jd, SkillsDictionary skills, Embedder embedder,
            Set<String> penalty) {
        List<String> job = fillJob(shapes.job().slots(), jobCands, jd, skills, embedder, penalty);
        List<AssembledResume.ProjectAssignment> projects =
                assignProjects(shapes.positions(), library, jd, skills, embedder, penalty);
        Map<String, LibraryProject> byId = new LinkedHashMap<>();
        for (LibraryProject p : library) {
            byId.put(p.id(), p);
        }
        List<AssembledResume.ProjectAssignment> withStack = new ArrayList<>(projects.size());
        for (AssembledResume.ProjectAssignment d : projects) {
            List<String> stack = orderStack(byId.get(d.project()).detail(), jd, skills);
            withStack.add(new AssembledResume.ProjectAssignment(d.position(), d.project(), d.bullets(), d.score(),
                    stack));
        }
        return new AssembledResume(job, withStack, null, null);
    }

    // --- total_score ------------------------------------------------------------------------------

    public static double totalScore(AssembledResume resume, Shapes shapes, List<BulletCandidate> jobCands,
            JobDescription jd, SkillsDictionary skills, Embedder embedder) {
        Map<String, BulletCandidate> byId = new LinkedHashMap<>();
        for (BulletCandidate c : jobCands) {
            byId.put(c.id(), c);
        }
        double js = 0;
        List<String> job = resume.job();
        List<Integer> slots = shapes.job().slots();
        for (int i = 0; i < job.size(); i++) {
            String c = job.get(i);
            if (c == null) {
                continue;
            }
            int length = slots.get(i);
            js += JdScorer.score(byId.get(c).variants().get(String.valueOf(length)), jd, skills, embedder);
        }
        double projectsSum = 0;
        for (AssembledResume.ProjectAssignment d : resume.projects()) {
            projectsSum += d.score();
        }
        return Round.to4(js + projectsSum);
    }
}
