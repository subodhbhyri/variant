package com.tailor.engine.match;

import com.tailor.engine.blocks.LibraryProject;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.generate.ModelResponse.BulletCandidate;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.generate.TruthfulnessGuard;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.w3c.dom.Document;

/**
 * PHASE5_SPEC.md section 8 (step 5.8) / section 8.1 (revision 4): a faithful port of {@code
 * reference/jd_ref.py}'s {@code missing_skills_v4} — required (weight >= 1.0) JD skills that
 * nothing in the user's material names or implies.
 */
public final class MissingSkills {

    private MissingSkills() {
    }

    public static List<String> compute(JobDescription jd, List<String> materialTexts, SkillsDictionary skills) {
        TreeSet<String> have = new TreeSet<>();
        for (String t : materialTexts) {
            have.addAll(TruthfulnessGuard.techsImplied(t, skills));
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

    /** The user's material (PHASE5_SPEC.md section 8.1): the whole onboarded resume text (every
     * paragraph, including the Skills section and locked text — see {@link #wholeResumeText}),
     * plus stored job-slot variants, library bullets, stacks and titles. {@code wholeResume} is
     * null-safe (omitted) so unit tests can pass just the stored/library material without
     * onboarding a real document. Measured on a real run: without the whole-resume text,
     * missing-skills claimed Git/SQL/CI-CD/REST/MySQL/AWS were missing from a resume whose own
     * Skills section lists them. */
    public static List<String> materialTexts(String wholeResume, List<BulletCandidate> jobCandidates,
            List<LibraryProject> library) {
        List<String> out = new ArrayList<>();
        if (wholeResume != null) {
            out.add(wholeResume);
        }
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

    /** Every paragraph's text in the onboarded document, concatenated — including the Skills
     * section and any locked (non-editable) text, neither of which is captured by a job-slot's
     * own stored variants. */
    public static String wholeResumeText(Path onboardedDocx) throws Exception {
        DocxPackage pkg = DocxPackage.open(onboardedDocx);
        Document doc = SafeXml.parse(pkg.readPart("word/document.xml"));
        return DomUtil.allText(doc.getDocumentElement());
    }
}
