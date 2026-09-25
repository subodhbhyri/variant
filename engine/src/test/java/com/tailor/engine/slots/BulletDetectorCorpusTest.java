package com.tailor.engine.slots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.fonts.FontNormalizer;
import com.tailor.engine.golden.GoldenBullet;
import com.tailor.engine.golden.GoldenResume;
import com.tailor.engine.golden.GoldenSpan;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Step 1.3 acceptance test T2 (spec sections 4, 11). Detection runs on the
 * font-normalized output (pipeline order: normalize -> detect), matching how
 * golden/*.json's bullets were produced.
 */
class BulletDetectorCorpusTest {

    @Test
    void allNineMatchGoldenBullets() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        FontNormalizer normalizer = new FontNormalizer(FontMap.loadDefault(), renderer);

        List<Path> docs = CorpusPaths.corpusDocx();
        assertEquals(9, docs.size(), "expected 9 corpus files");

        Path workDir = Files.createTempDirectory("detect-corpus-test");
        StringBuilder failures = new StringBuilder();

        for (Path docx : docs) {
            String base = stripExt(docx.getFileName().toString());
            GoldenResume golden = GoldenResume.load(CorpusPaths.goldenDir().resolve(base + ".json"));

            Path normalized = workDir.resolve(base + "-normalized.docx");
            normalizer.normalize(docx, normalized, 1, workDir);

            List<Slot> slots = DocxBulletDetection.detect(normalized);
            System.out.println(base + ": detected " + slots.size() + " slots, golden has " + golden.bullets().size());

            if (slots.size() != golden.bullets().size()) {
                failures.append(base).append(": slot count=").append(slots.size())
                        .append(" golden=").append(golden.bullets().size()).append('\n');
                continue; // index-by-index comparison below would be meaningless
            }

            for (int i = 0; i < slots.size(); i++) {
                Slot s = slots.get(i);
                GoldenBullet g = golden.bullets().get(i);
                String tag = base + "[" + i + "]";

                if (!s.locator().equals(g.locator())) {
                    failures.append(tag).append(": locator=").append(s.locator())
                            .append(" golden=").append(g.locator()).append('\n');
                }
                if (!s.text().equals(g.text())) {
                    failures.append(tag).append(": text mismatch\n  got=")
                            .append(s.text()).append("\n  golden=").append(g.text()).append('\n');
                }
                if (s.chars() != g.chars()) {
                    failures.append(tag).append(": chars=").append(s.chars())
                            .append(" golden=").append(g.chars()).append('\n');
                }
                if (s.supported() != g.supported()) {
                    failures.append(tag).append(": supported=").append(s.supported())
                            .append(" golden=").append(g.supported()).append('\n');
                }
                if (!s.unsupportedReasons().equals(g.unsupportedReasons())) {
                    failures.append(tag).append(": unsupported_reasons=").append(s.unsupportedReasons())
                            .append(" golden=").append(g.unsupportedReasons()).append('\n');
                }
                if (!emphasisMatches(s.emphasis(), g.emphasis())) {
                    failures.append(tag).append(": emphasis=").append(s.emphasis())
                            .append(" golden=").append(g.emphasis()).append('\n');
                }
            }
        }

        System.out.println("FAILURES:\n" + (failures.isEmpty() ? "(none)" : failures));
        assertTrue(failures.isEmpty(), "T2 failures:\n" + failures);
    }

    private static boolean emphasisMatches(List<EmphasisSpan> got, List<GoldenSpan> golden) {
        if (got.size() != golden.size()) {
            return false;
        }
        for (int i = 0; i < got.size(); i++) {
            EmphasisSpan a = got.get(i);
            GoldenSpan b = golden.get(i);
            if (a.start() != b.start() || a.end() != b.end()
                    || a.bold() != b.bold() || a.italic() != b.italic()) {
                return false;
            }
        }
        return true;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
