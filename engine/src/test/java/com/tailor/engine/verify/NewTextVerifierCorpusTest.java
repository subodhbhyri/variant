package com.tailor.engine.verify;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.calibrate.BatchValidator;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * The product case: genuinely new text in bullets. For each supported slot the
 * candidate is other bullets' text from the same resume, cut at a word
 * boundary to the slot's own length. The validator decides which fit; every
 * FITS candidate is assembled at once, and the Verifier must pass — pages,
 * every line count, the 0.5pt layout check, and the font audit.
 */
class NewTextVerifierCorpusTest {

    @Test
    void fittingNewTextVerifiesOkOnAllNine() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        FontMap fontMap = FontMap.loadDefault();
        FontNormalizer normalizer = new FontNormalizer(fontMap, renderer);
        Path workDir = Files.createTempDirectory("newtext-verifier-test");
        StringBuilder failures = new StringBuilder();

        for (Path docx : CorpusPaths.corpusDocx()) {
            String base = stripExt(docx.getFileName().toString());
            Path normalized = workDir.resolve(base + "-normalized.docx");
            normalizer.normalize(docx, normalized, 1, workDir);

            List<Slot> slots = DocxBulletDetection.detect(normalized);
            List<String> originalTexts = slots.stream().map(Slot::text).toList();
            Path basePdf = renderer.render(normalized, workDir);
            Map<Integer, Integer> lineCounts =
                    new HashMap<>(AnchorMeasurer.measure(PdfLines.extract(basePdf), originalTexts));

            Map<Integer, List<BulletText>> candidates = new LinkedHashMap<>();
            for (int i = 0; i < slots.size(); i++) {
                if (slots.get(i).supported()) {
                    String text = rotatedText(originalTexts, i, originalTexts.get(i).length());
                    candidates.put(i, List.of(new BulletText(text, List.of())));
                }
            }

            List<BatchValidator.CandidateResult> results = BatchValidator.validate(
                    normalized, renderer, lineCounts, Map.of(), candidates, workDir);

            Map<Integer, BulletText> chosen = new LinkedHashMap<>();
            for (var r : results) {
                if (r.outcome() == BatchValidator.Outcome.FITS) {
                    chosen.put(r.slotIndex(), candidates.get(r.slotIndex()).get(r.candidateIndex()));
                }
            }

            DocxPackage pkg = DocxPackage.open(normalized);
            Document doc = SafeXml.parse(pkg.readPart("word/document.xml"));
            Element numberingRoot = pkg.hasPart("word/numbering.xml")
                    ? SafeXml.parse(pkg.readPart("word/numbering.xml")).getDocumentElement() : null;
            Element stylesRoot = pkg.hasPart("word/styles.xml")
                    ? SafeXml.parse(pkg.readPart("word/styles.xml")).getDocumentElement() : null;
            List<Slot> fresh = BulletDetector.detect(doc, new NumberingResolver(numberingRoot, stylesRoot));

            List<String> assembledTexts = new ArrayList<>();
            for (Slot s : fresh) {
                BulletText c = chosen.get(s.index());
                if (c != null) {
                    Substituter.substitute(s.element(), c);
                    assembledTexts.add(c.text());
                } else {
                    assembledTexts.add(s.text());
                }
            }
            pkg.writePart("word/document.xml", XmlSerialize.toBytes(doc));
            Path assembled = workDir.resolve(base + "-newtext.docx");
            pkg.save(assembled);

            Map<Integer, SlotEdit> edits = new LinkedHashMap<>();
            for (Integer i : chosen.keySet()) {
                edits.put(i, SlotEdit.SUBSTITUTED);
            }
            VerifyReport report = Verifier.verify(
                    normalized, assembled, renderer, fontMap, lineCounts, assembledTexts, edits, workDir);

            System.out.println(base + ": new text in " + chosen.size() + " of " + candidates.size()
                    + " supported slots, " + VerifierCorpusTest.summary(report));
            if (chosen.isEmpty()) {
                failures.append(base).append(": no candidate fit, nothing was tested\n");
            } else if (!report.ok()) {
                failures.append(base).append(": ").append(VerifierCorpusTest.summary(report)).append('\n');
            }
        }

        System.out.println("FAILURES:\n" + (failures.isEmpty() ? "(none)" : failures));
        assertTrue(failures.isEmpty(), failures.toString());
    }

    /** Other bullets' text starting after slot i, cut at a word boundary to at most maxChars. */
    private static String rotatedText(List<String> texts, int i, int maxChars) {
        StringBuilder src = new StringBuilder();
        for (int k = 1; k <= texts.size(); k++) {
            src.append(texts.get((i + k) % texts.size()).strip()).append(' ');
        }
        String out = "";
        for (String w : src.toString().split(" ")) {
            if (w.isEmpty()) {
                continue;
            }
            String next = out.isEmpty() ? w : out + " " + w;
            if (next.length() > maxChars) {
                break;
            }
            out = next;
        }
        return out;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}