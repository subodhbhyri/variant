package com.tailor.engine.edit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.docx.XmlSerialize;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.fonts.FontNormalizer;
import com.tailor.engine.golden.GoldenResume;
import com.tailor.engine.measure.AnchorMeasurer;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.measure.PdfPageCounter;
import com.tailor.engine.numbering.NumberingResolver;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.slots.BulletDetector;
import com.tailor.engine.slots.BulletText;
import com.tailor.engine.slots.Slot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * The real T4 (spec section 11): substituting every supported bullet with its
 * own text+emphasis, all at once, must leave every slot's line count and the
 * page count unchanged — checked by actually rendering and measuring, not
 * just inspecting the XML (which step 1.4's own tests did, before this
 * measurement code existed).
 */
class SubstituteRenderCorpusTest {

    @Test
    void allNineUnchangedAfterRoundTripSubstitution() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        FontNormalizer normalizer = new FontNormalizer(FontMap.loadDefault(), renderer);
        Path workDir = Files.createTempDirectory("substitute-render-test");
        StringBuilder failures = new StringBuilder();

        for (Path docx : CorpusPaths.corpusDocx()) {
            String base = stripExt(docx.getFileName().toString());
            GoldenResume golden = GoldenResume.load(CorpusPaths.goldenDir().resolve(base + ".json"));

            Path normalized = workDir.resolve(base + "-normalized.docx");
            normalizer.normalize(docx, normalized, 1, workDir);

            DocxPackage pkg = DocxPackage.open(normalized);
            Document documentXml = SafeXml.parse(pkg.readPart("word/document.xml"));
            Element numberingRoot = pkg.hasPart("word/numbering.xml")
                    ? SafeXml.parse(pkg.readPart("word/numbering.xml")).getDocumentElement() : null;
            Element stylesRoot = pkg.hasPart("word/styles.xml")
                    ? SafeXml.parse(pkg.readPart("word/styles.xml")).getDocumentElement() : null;
            NumberingResolver resolver = new NumberingResolver(numberingRoot, stylesRoot);

            List<Slot> slots = BulletDetector.detect(documentXml, resolver);
            List<String> texts = slots.stream().map(Slot::text).toList();

            for (Slot slot : slots) {
                if (slot.supported()) {
                    Substituter.substitute(slot.element(), new BulletText(slot.text(), slot.emphasis()));
                }
            }

            pkg.writePart("word/document.xml", XmlSerialize.toBytes(documentXml));
            Path edited = workDir.resolve(base + "-roundtrip.docx");
            pkg.save(edited);

            Path pdf = renderer.render(edited, workDir);
            int pages = PdfPageCounter.count(pdf);
            List<PdfLines.Line> lines = PdfLines.extract(pdf);
            Map<Integer, Integer> measured = AnchorMeasurer.measure(lines, texts);

            if (pages != golden.pages()) {
                failures.append(base).append(": pages=").append(pages)
                        .append(" golden=").append(golden.pages()).append('\n');
            }
            for (int i = 0; i < slots.size(); i++) {
                Integer got = measured.get(i);
                int expected = golden.bullets().get(i).lines();
                if (got == null) {
                    failures.append(base).append('[').append(i).append("]: FAILED TO ANCHOR after substitution\n");
                } else if (!got.equals(expected)) {
                    failures.append(base).append('[').append(i).append("]: lines=").append(got)
                            .append(" golden=").append(expected).append(" (after round-trip substitution)\n");
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
