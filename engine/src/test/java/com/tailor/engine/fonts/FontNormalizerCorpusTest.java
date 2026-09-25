package com.tailor.engine.fonts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.golden.GoldenResume;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Step 1.2 acceptance test T1 (spec sections 3, 11). */
class FontNormalizerCorpusTest {

    @Test
    void allNineMatchGoldenAfterNormalization() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        FontMap fontMap = FontMap.loadDefault();
        FontNormalizer normalizer = new FontNormalizer(fontMap, renderer);
        FontAudit audit = new FontAudit(fontMap);

        Path corpusDir = CorpusPaths.corpusDir();
        Path goldenDir = CorpusPaths.goldenDir();
        System.out.println("corpusDir=" + corpusDir);
        System.out.println("goldenDir=" + goldenDir);

        List<Path> docs = CorpusPaths.corpusDocx();
        assertEquals(9, docs.size(), "expected 9 corpus files, found " + docs.size() + " in " + corpusDir);

        Path outDir = Files.createTempDirectory("normalize-corpus-test");
        StringBuilder failures = new StringBuilder();

        for (Path docx : docs) {
            String base = stripExt(docx.getFileName().toString());
            Path goldenPath = goldenDir.resolve(base + ".json");
            System.out.println("bullet source=" + docx.getFileName()
                    + " -> golden=" + goldenPath + " exists=" + Files.exists(goldenPath));

            GoldenResume golden = GoldenResume.load(goldenPath);
            Path outDocx = outDir.resolve(base + "-normalized.docx");

            NormalizeResult result = normalizer.normalize(docx, outDocx, 1, outDir);
            System.out.printf("  result: squeeze_removed=%d shrink_pt=%.1f pages=%d%n",
                    result.squeezeRemoved(), result.shrinkPt(), result.pages());
            System.out.printf("  golden: squeeze_removed=%d shrink_pt=%.1f pages=%d%n",
                    golden.squeezeRemoved(), golden.shrinkPt(), golden.pages());

            if (result.pages() != golden.pages()) {
                failures.append(base).append(": pages=").append(result.pages())
                        .append(" golden=").append(golden.pages()).append('\n');
            }
            if (Math.abs(result.shrinkPt() - golden.shrinkPt()) > 1e-9) {
                failures.append(base).append(": shrink_pt=").append(result.shrinkPt())
                        .append(" golden=").append(golden.shrinkPt()).append('\n');
            }
            if (result.squeezeRemoved() != golden.squeezeRemoved()) {
                failures.append(base).append(": squeeze_removed=").append(result.squeezeRemoved())
                        .append(" golden=").append(golden.squeezeRemoved()).append('\n');
            }

            Path pdf = renderer.render(outDocx, outDir);
            var violations = audit.audit(pdf);
            if (!violations.isEmpty()) {
                failures.append(base).append(": font audit violations ").append(violations).append('\n');
            }
        }

        System.out.println("FAILURES:\n" + (failures.isEmpty() ? "(none)" : failures));
        assertTrue(failures.isEmpty(), "T1 failures:\n" + failures);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
