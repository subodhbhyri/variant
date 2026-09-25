package com.tailor.engine.calibrate;

import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.docx.XmlSerialize;
import com.tailor.engine.edit.Substituter;
import com.tailor.engine.measure.AnchorMeasurer;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.numbering.NumberingResolver;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.slots.BulletDetector;
import com.tailor.engine.slots.BulletText;
import com.tailor.engine.slots.DocxBulletDetection;
import com.tailor.engine.slots.Slot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Binary-searches every slot's character-length hint in parallel — one
 * render probes every unfinished slot at its own midpoint (spec sections
 * 7.2, 7.3). The result is a hint for the editor's counter, not a pass/fail
 * gate; {@code BatchValidator} (a later delivery) is the actual gate, per
 * section 7.1.
 */
public final class BatchCalibrator {

    private static final int CALIBRATION_LO = 30;
    private static final int CALIBRATION_HI = 600;

    private BatchCalibrator() {
    }

    public record Result(Map<Integer, Integer> lineCounts, Map<Integer, Integer> hints) {
    }

    public static Result calibrate(Path normalizedSource, Renderer renderer, String prose, Path workDir)
            throws Exception {
        List<Slot> baseSlots = DocxBulletDetection.detect(normalizedSource);
        List<String> baseTexts = baseSlots.stream().map(Slot::text).toList();
        Path basePdf = renderer.render(normalizedSource, workDir);
        List<PdfLines.Line> baseLines = PdfLines.extract(basePdf);
        Map<Integer, Integer> lineCounts = AnchorMeasurer.measure(baseLines, baseTexts);

        int n = baseSlots.size();
        int[] lo = new int[n];
        int[] hi = new int[n];
        Integer[] best = new Integer[n];
        for (int i = 0; i < n; i++) {
            if (!baseSlots.get(i).supported() || lineCounts.get(i) == null) {
                lo[i] = 1;
                hi[i] = 0; // never active: unsupported, or its own text didn't anchor
            } else {
                lo[i] = CALIBRATION_LO;
                hi[i] = CALIBRATION_HI;
            }
        }

        int round = 0;
        while (anyActive(lo, hi)) {
            round++;
            Map<Integer, Integer> mids = new LinkedHashMap<>();
            Map<Integer, String> candidateText = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                if (lo[i] > hi[i]) {
                    continue;
                }
                int mid = (lo[i] + hi[i]) / 2;
                mids.put(i, mid);
                candidateText.put(i, wordPrefix(prose, mid, i));
            }
            if (mids.isEmpty()) {
                break;
            }

            Path probeDocx = workDir.resolve("calib-round-" + round + "-" + System.nanoTime() + ".docx");
            Map<Integer, Integer> measured =
                    renderProbe(normalizedSource, candidateText, renderer, probeDocx, workDir);

            for (Map.Entry<Integer, Integer> e : mids.entrySet()) {
                int i = e.getKey();
                int mid = e.getValue();
                Integer measuredLines = measured.get(i);
                int targetL = lineCounts.get(i);
                if (measuredLines != null && measuredLines <= targetL) {
                    best[i] = candidateText.get(i).length();
                    lo[i] = mid + 1;
                } else {
                    hi[i] = mid - 1;
                }
            }
        }

        Map<Integer, Integer> hints = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            if (best[i] != null) {
                hints.put(i, best[i]);
            }
        }
        return new Result(lineCounts, hints);
    }

    /** Renders one probe: every slot in {@code candidateText} substituted, everything else untouched. */
    private static Map<Integer, Integer> renderProbe(
            Path source, Map<Integer, String> candidateText, Renderer renderer, Path outDocx, Path workDir)
            throws Exception {
        DocxPackage pkg = DocxPackage.open(source);
        Document doc = SafeXml.parse(pkg.readPart("word/document.xml"));
        Element numberingRoot = pkg.hasPart("word/numbering.xml")
                ? SafeXml.parse(pkg.readPart("word/numbering.xml")).getDocumentElement() : null;
        Element stylesRoot = pkg.hasPart("word/styles.xml")
                ? SafeXml.parse(pkg.readPart("word/styles.xml")).getDocumentElement() : null;
        NumberingResolver resolver = new NumberingResolver(numberingRoot, stylesRoot);
        List<Slot> freshSlots = BulletDetector.detect(doc, resolver);

        List<String> textsForAnchor = new ArrayList<>();
        for (int i = 0; i < freshSlots.size(); i++) {
            Slot s = freshSlots.get(i);
            String candidate = candidateText.get(i);
            if (candidate != null && s.supported()) {
                Substituter.substitute(s.element(), new BulletText(candidate, List.of()));
                textsForAnchor.add(candidate);
            } else {
                textsForAnchor.add(s.text());
            }
        }

        pkg.writePart("word/document.xml", XmlSerialize.toBytes(doc));
        pkg.save(outDocx);

        Path pdf = renderer.render(outDocx, workDir);
        List<PdfLines.Line> lines = PdfLines.extract(pdf);
        return AnchorMeasurer.measure(lines, textsForAnchor);
    }

    private static boolean anyActive(int[] lo, int[] hi) {
        for (int i = 0; i < lo.length; i++) {
            if (lo[i] <= hi[i]) {
                return true;
            }
        }
        return false;
    }

    /** Builds the longest word-boundary prefix of a "Slot{i} "+src cycle-through that fits maxChars. */
    public static String wordPrefix(String src, int maxChars, int slotIndex) {
        String head = "Slot" + slotIndex + " ";
        String[] words = (head + src).split(" ");
        String out = "";
        for (String w : words) {
            String candidate = out.isEmpty() ? w : out + " " + w;
            if (candidate.length() > maxChars) {
                break;
            }
            out = candidate;
        }
        return out;
    }
}
