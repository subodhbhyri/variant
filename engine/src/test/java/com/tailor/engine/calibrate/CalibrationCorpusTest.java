package com.tailor.engine.calibrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.docx.XmlSerialize;
import com.tailor.engine.edit.Substituter;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.fonts.FontNormalizer;
import com.tailor.engine.measure.AnchorMeasurer;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.numbering.NumberingResolver;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.slots.BulletDetector;
import com.tailor.engine.slots.BulletText;
import com.tailor.engine.slots.DocxBulletDetection;
import com.tailor.engine.slots.Slot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Step 1.5 calibration tests. Not compared against golden's max_chars_prose
 * numerically (see ProseSource's javadoc for why) — instead checked for
 * self-consistency: every returned hint, rendered on its own, actually keeps
 * its slot at the target line count. Also covers T11 (determinism).
 */
class CalibrationCorpusTest {

    private static final int PROSE_LENGTH = 900; // > CALIBRATION_HI so word-prefix never runs out

    @Test
    void everySupportedSlotGetsAPlausibleHintOnAllNine() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        FontNormalizer normalizer = new FontNormalizer(FontMap.loadDefault(), renderer);
        Path workDir = Files.createTempDirectory("calibration-corpus-test");
        StringBuilder failures = new StringBuilder();

        for (Path docx : CorpusPaths.corpusDocx()) {
            String base = stripExt(docx.getFileName().toString());
            Path normalized = workDir.resolve(base + "-normalized.docx");
            normalizer.normalize(docx, normalized, 1, workDir);

            List<Slot> slots = DocxBulletDetection.detect(normalized);
            String prose = ProseSource.fromSlots(slots, PROSE_LENGTH);

            BatchCalibrator.Result result = BatchCalibrator.calibrate(normalized, renderer, prose, workDir);
            System.out.println(base + ": " + result.hints().size() + " of " + slots.size()
                    + " slots got a hint");

            for (int i = 0; i < slots.size(); i++) {
                Slot s = slots.get(i);
                if (!s.supported() || result.lineCounts().get(i) == null) {
                    continue; // not eligible for calibration at all
                }
                Integer hint = result.hints().get(i);
                if (hint == null) {
                    failures.append(base).append('[').append(i).append("]: no hint returned\n");
                } else if (hint < 30 || hint > 600) {
                    failures.append(base).append('[').append(i).append("]: hint out of range: ")
                            .append(hint).append('\n');
                }
            }

            // Self-consistency: the first eligible slot's hint, rendered alone, must fit.
            for (int i = 0; i < slots.size(); i++) {
                Slot s = slots.get(i);
                Integer L = result.lineCounts().get(i);
                Integer hint = result.hints().get(i);
                if (!s.supported() || L == null || hint == null) {
                    continue;
                }
                String candidate = BatchCalibrator.wordPrefix(prose, hint, i);
                Integer measuredLines = renderSingleSubstitution(normalized, i, candidate, renderer, workDir);
                if (measuredLines == null || !measuredLines.equals(L)) {
                    failures.append(base).append('[').append(i).append("]: hint ").append(hint)
                            .append(" chars measured ").append(measuredLines)
                            .append(" lines, target was ").append(L).append('\n');
                }
                break; // one self-consistency check per resume keeps render cost bounded
            }
        }

        System.out.println("FAILURES:\n" + (failures.isEmpty() ? "(none)" : failures));
        assertTrue(failures.isEmpty(), failures.toString());
    }

    /** T11: calibrating the same file twice must produce identical hints. */
    @Test
    void calibratingTwiceGivesIdenticalHints() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        FontNormalizer normalizer = new FontNormalizer(FontMap.loadDefault(), renderer);
        Path workDir = Files.createTempDirectory("calibration-determinism-test");

        Path source = CorpusPaths.corpusDocx().stream()
                .filter(p -> p.getFileName().toString().equals("resume_EHR.docx"))
                .findFirst().orElseThrow();
        Path normalized = workDir.resolve("resume_EHR-normalized.docx");
        normalizer.normalize(source, normalized, 1, workDir);

        List<Slot> slots = DocxBulletDetection.detect(normalized);
        String prose = ProseSource.fromSlots(slots, PROSE_LENGTH);

        BatchCalibrator.Result first = BatchCalibrator.calibrate(normalized, renderer, prose, workDir);
        BatchCalibrator.Result second = BatchCalibrator.calibrate(normalized, renderer, prose, workDir);

        assertEquals(first.hints(), second.hints(), "calibrating twice should give identical hints");
    }

    /** Substitutes only slotIndex with candidateText, renders, and returns its measured line count. */
    private static Integer renderSingleSubstitution(
            Path normalizedSource, int slotIndex, String candidateText, Renderer renderer, Path workDir)
            throws Exception {
        DocxPackage pkg = DocxPackage.open(normalizedSource);
        Document doc = SafeXml.parse(pkg.readPart("word/document.xml"));
        Element numberingRoot = pkg.hasPart("word/numbering.xml")
                ? SafeXml.parse(pkg.readPart("word/numbering.xml")).getDocumentElement() : null;
        Element stylesRoot = pkg.hasPart("word/styles.xml")
                ? SafeXml.parse(pkg.readPart("word/styles.xml")).getDocumentElement() : null;
        NumberingResolver resolver = new NumberingResolver(numberingRoot, stylesRoot);
        List<Slot> slots = BulletDetector.detect(doc, resolver);

        List<String> textsForAnchor = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            if (i == slotIndex) {
                Substituter.substitute(slots.get(i).element(), new BulletText(candidateText, List.of()));
                textsForAnchor.add(candidateText);
            } else {
                textsForAnchor.add(slots.get(i).text());
            }
        }

        pkg.writePart("word/document.xml", XmlSerialize.toBytes(doc));
        Path out = workDir.resolve("self-consistency-" + slotIndex + "-" + System.nanoTime() + ".docx");
        pkg.save(out);

        Path pdf = renderer.render(out, workDir);
        List<PdfLines.Line> lines = PdfLines.extract(pdf);
        Map<Integer, Integer> measured = AnchorMeasurer.measure(lines, textsForAnchor);
        return measured.get(slotIndex);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
