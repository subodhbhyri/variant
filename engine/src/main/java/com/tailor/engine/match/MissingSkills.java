package com.tailor.engine.match;

import com.tailor.engine.blocks.LibraryProject;
import com.tailor.engine.generate.ModelResponse.BulletCandidate;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.generate.TruthfulnessGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * PHASE5_SPEC.md section 8 (step 5.8): a faithful port of {@code reference/jd_ref.py}'s
 * {@code missing_skills} — required (weight >= 1.0) JD skills that no text in the user's material
 * names.
 */
public final class MissingSkills {

    private MissingSkills() {
    }

    public static List<String> compute(JobDescription jd, List<String> materialTexts, SkillsDictionary skills) {
        TreeSet<String> have = new TreeSet<>();
        for (String t : materialTexts) {
            have.addAll(TruthfulnessGuard.techs(t, skills));
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : jd.skills().entrySet()) {
            if (e.getValue() >= 1.0 && !have.contains(e.getKey())) {
                out.add(e.getKey());
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    /** The user's material: stored job-slot variants, library bullets, stacks and titles. */
    public static List<String> materialTexts(List<BulletCandidate> jobCandidates, List<LibraryProject> library) {
        List<String> out = new ArrayList<>();
        for (BulletCandidate c : jobCandidates) {
            out.addAll(c.variants().values());
        }
        for (LibraryProject p : library) {
            if (p.title() != null) {
                out.add(p.title());
            }
            if (p.detail() != null) {
                out.add(p.detail());
            }
            for (Map<String, String> bullet : p.bullets()) {
                out.addAll(bullet.values());
            }
        }
        return out;
    }
}
