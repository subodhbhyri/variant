package com.tailor.engine.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.fonts.FontNormalizer;
import com.tailor.engine.golden.GoldenResume;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.slots.DocxBulletDetection;
import com.tailor.engine.slots.Slot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Step 1.5 acceptance test T3 (spec sections 6, 11): every slot's measured
 * line count matches golden/*.json exactly, on the unedited normalized file.
 * All slots (supported and unsupported) are fed to the measurer together, in
 * document order, matching how golden's own "lines" values were produced —
 * this keeps the anchor cursor advancing correctly past the 2 unsupported
 * bullets in the corpus.
 */
class MeasurementCorpusTest {

    @Test
    void allNineMatchGoldenLineCounts() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        FontNormalizer normalizer = new FontNormalizer(FontMap.loadDefault(), renderer);
        Path workDir = Files.createTempDirectory("measure-corpus-test");
        StringBuilder failures = new StringBuilder();

        for (Path docx : CorpusPaths.corpusDocx()) {
            String base = stripExt(docx.getFileName().toString());
            GoldenResume golden = GoldenResume.load(CorpusPaths.goldenDir().resolve(base + ".json"));

            Path normalized = workDir.resolve(base + "-normalized.docx");
            normalizer.normalize(docx, normalized, 1, workDir);

            List<Slot> slots = DocxBulletDetection.detect(normalized);
            List<String> texts = slots.stream().map(Slot::text).toList();

            Path pdf = renderer.render(normalized, workDir);
            List<PdfLines.Line> lines = PdfLines.extract(pdf);
            Map<Integer, Integer> measured = AnchorMeasurer.measure(lines, texts);

            System.out.println(base + ": " + slots.size() + " slots, " + lines.size() + " lines extracted");

            if (slots.size() != golden.bullets().size()) {
                failures.append(base).append(": slot count mismatch, skipping line comparison\n");
                continue;
            }
            for (int i = 0; i < slots.size(); i++) {
                Integer got = measured.get(i);
                int expected = golden.bullets().get(i).lines();
                if (got == null) {
                    failures.append(base).append('[').append(i)
                            .append("]: FAILED TO ANCHOR (golden lines=").append(expected).append(")\n");
                } else if (!got.equals(expected)) {
                    failures.append(base).append('[').append(i).append("]: lines=").append(got)
                            .append(" golden=").append(expected).append('\n');
                }
            }
        }

        System.out.println("FAILURES:\n" + (failures.isEmpty() ? "(none)" : failures));
        assertTrue(failures.isEmpty(), failures.toString());
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
