package com.tailor.engine.verify;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.docx.XmlSerialize;
import com.tailor.engine.edit.Blanker;
import com.tailor.engine.edit.Padder;
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

/** Verifier end to end on all 9 resumes (spec sections 8, 11: T4, T9, T10). */
class VerifierCorpusTest {

    private static final String SHORT_SENTENCE = "Built a short one-line result.";

    /** An edit applied to one fresh slot; returns the slot's new visible text (null if blanked). */
    private interface SlotEditor {
        String apply(Slot fresh, int targetLines);
    }

    /** T4: every supported bullet re-written with its own text and emphasis. */
    @Test
    void roundTripSubstitutionVerifiesOkOnAllNine() throws Exception {
        runOnAllNine("roundtrip", (slots, lineCounts) -> {
            Map<Integer, SlotEdit> plan = new LinkedHashMap<>();
            for (Slot s : slots) {
                if (s.supported() && lineCounts.get(s.index()) != null) {
                    plan.put(s.index(), SlotEdit.SUBSTITUTED);
                }
            }
            return plan;
        }, (fresh, L) -> {
            Substituter.substitute(fresh.element(), new BulletText(fresh.text(), fresh.emphasis()));
            return fresh.text();
        });
    }

    /** T9: every second supported bullet deleted by the user (blank of the same height). */
    @Test
    void blankEverySecondBulletVerifiesOkOnAllNine() throws Exception {
        runOnAllNine("blank", (slots, lineCounts) -> {
            Map<Integer, SlotEdit> plan = new LinkedHashMap<>();
            int k = 0;
            for (Slot s : slots) {
                if (s.supported() && lineCounts.get(s.index()) != null) {
                    if (k++ % 2 == 1) {
                        plan.put(s.index(), SlotEdit.BLANKED);
                    }
                }
            }
            return plan;
        }, (fresh, L) -> {
            Blanker.blank(fresh.element(), L);
            return null;
        });
    }

    /** T10: every two-line bullet replaced with a one-line sentence, padded to hold its height. */
    @Test
    void padShortenedBulletsVerifiesOkOnAllNine() throws Exception {
        runOnAllNine("pad", (slots, lineCounts) -> {
            Map<Integer, SlotEdit> plan = new LinkedHashMap<>();
            for (Slot s : slots) {
                Integer L = lineCounts.get(s.index());
                if (s.supported() && L != null && L == 2) {
                    plan.put(s.index(), SlotEdit.PADDED);
                }
            }
            return plan;
        }, (fresh, L) -> {
            Substituter.substitute(fresh.element(), new BulletText(SHORT_SENTENCE, List.of()));
            Padder.pad(fresh.element(), L - 1);
            return SHORT_SENTENCE;
        });
    }

    // --- shared runner --------------------------------------------------------

    private interface Planner {
        Map<Integer, SlotEdit> plan(List<Slot> slots, Map<Integer, Integer> lineCounts);
    }

    private static void runOnAllNine(String label, Planner planner, SlotEditor editor) throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        FontMap fontMap = FontMap.loadDefault();
        FontNormalizer normalizer = new FontNormalizer(fontMap, renderer);
        Path workDir = Files.createTempDirectory("verifier-" + label);
        StringBuilder failures = new StringBuilder();

        for (Path docx : CorpusPaths.corpusDocx()) {
            String base = stripExt(docx.getFileName().toString());
            Path normalized = workDir.resolve(base + "-normalized.docx");
            normalizer.normalize(docx, normalized, 1, workDir);

            List<Slot> slots = DocxBulletDetection.detect(normalized);
            List<String> texts = slots.stream().map(Slot::text).toList();
            Path basePdf = renderer.render(normalized, workDir);
            Map<Integer, Integer> lineCounts =
                    new HashMap<>(AnchorMeasurer.measure(PdfLines.extract(basePdf), texts));

            Map<Integer, SlotEdit> plan = planner.plan(slots, lineCounts);

            DocxPackage pkg = DocxPackage.open(normalized);
            Document doc = SafeXml.parse(pkg.readPart("word/document.xml"));
            Element numberingRoot = pkg.hasPart("word/numbering.xml")
                    ? SafeXml.parse(pkg.readPart("word/numbering.xml")).getDocumentElement() : null;
            Element stylesRoot = pkg.hasPart("word/styles.xml")
                    ? SafeXml.parse(pkg.readPart("word/styles.xml")).getDocumentElement() : null;
            List<Slot> fresh = BulletDetector.detect(doc, new NumberingResolver(numberingRoot, stylesRoot));

            List<String> assembledTexts = new ArrayList<>();
            for (Slot s : fresh) {
                if (plan.containsKey(s.index())) {
                    assembledTexts.add(editor.apply(s, lineCounts.get(s.index())));
                } else {
                    assembledTexts.add(s.text());
                }
            }
            pkg.writePart("word/document.xml", XmlSerialize.toBytes(doc));
            Path assembled = workDir.resolve(base + "-" + label + ".docx");
            pkg.save(assembled);

            VerifyReport report = Verifier.verify(
                    normalized, assembled, renderer, fontMap, lineCounts, assembledTexts, plan, workDir);

            System.out.println(base + ": " + plan.size() + " slots edited, " + summary(report));
            if (!report.ok()) {
                failures.append(base).append(": ").append(summary(report)).append('\n');
            }
        }

        System.out.println(label + " FAILURES:\n" + (failures.isEmpty() ? "(none)" : failures));
        assertTrue(failures.isEmpty(), failures.toString());
    }

    static String summary(VerifyReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("ok=").append(r.ok())
                .append(" pages=").append(r.pagesBefore()).append("->").append(r.pagesAfter())
                .append(" layoutShift=").append(r.layoutShiftPt());
        if (r.layoutProblem() != null) {
            sb.append(" problem=\"").append(r.layoutProblem()).append('"');
        }
        if (!r.fontViolations().isEmpty()) {
            sb.append(" fontViolations=").append(r.fontViolations());
        }
        for (var e : r.lineChecks().entrySet()) {
            if (!e.getValue().ok()) {
                sb.append(" | slot[").append(e.getKey()).append("] target=").append(e.getValue().target())
                        .append(" measured=").append(e.getValue().measured());
            }
        }
        return sb.toString();
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}