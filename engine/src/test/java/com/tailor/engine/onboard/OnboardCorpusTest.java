package com.tailor.engine.onboard;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.golden.GoldenResume;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** P2-T4 (PHASE2_SPEC.md section 6): all 9 corpus resumes onboard, matching golden. */
class OnboardCorpusTest {

    private static final Map<String, Integer> EXPECTED_EDITABLE = Map.of(
            "Abhinav_SDE_Intern_Latest", 15,
            "DurgaSrithaDongla_Resume_New", 18,
            "Mohan_Resume_Final", 19,
            "My_resume1", 14,
            "Rakesh_Resume", 11,
            "Subodh_Ashok_Bhyri", 8,
            "Talakanti_Sravan_Kumar_Reddy_Resume", 14,
            "resume_EHR", 15,
            "resume_ajitesh__", 14);

    @Test
    void allNineOnboardAcceptedMatchingGolden() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        FontMap fontMap = FontMap.loadDefault();
        OnboardPipeline pipeline = new OnboardPipeline(renderer, fontMap);

        StringBuilder failures = new StringBuilder();
        for (Path docx : CorpusPaths.corpusDocx()) {
            String base = stripExt(docx.getFileName().toString());
            GoldenResume golden = GoldenResume.load(CorpusPaths.goldenDir().resolve(base + ".json"));

            Path outDir = Files.createTempDirectory("onboard-corpus-" + base);
            byte[] upload = Files.readAllBytes(docx);
            OnboardReport report = pipeline.run(upload, outDir);

            if (!report.accepted()) {
                failures.append(base).append(": rejected reason=").append(report.reason()).append('\n');
                continue;
            }
            if (report.pages() != golden.pages()) {
                failures.append(base).append(": pages=").append(report.pages())
                        .append(" golden=").append(golden.pages()).append('\n');
            }
            if (Math.abs(report.shrinkPt() - golden.shrinkPt()) > 1e-9) {
                failures.append(base).append(": shrinkPt=").append(report.shrinkPt())
                        .append(" golden=").append(golden.shrinkPt()).append('\n');
            }
            Integer wantEditable = EXPECTED_EDITABLE.get(base);
            if (wantEditable != null && !wantEditable.equals(report.editableCount())) {
                failures.append(base).append(": editable=").append(report.editableCount())
                        .append(" expected=").append(wantEditable).append('\n');
            }
            for (var s : report.slots()) {
                if (!s.editable() && "shared_lines".equals(s.lockReason())) {
                    failures.append(base).append(": unexpected shared_lines lock at slot ")
                            .append(s.index()).append('\n');
                }
            }
            System.out.println(base + ": pages=" + report.pages() + " shrinkPt=" + report.shrinkPt()
                    + " editable=" + report.editableCount() + "/" + report.slots().size());
        }

        System.out.println("FAILURES:\n" + (failures.isEmpty() ? "(none)" : failures));
        assertTrue(failures.isEmpty(), "P2-T4 failures:\n" + failures);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
