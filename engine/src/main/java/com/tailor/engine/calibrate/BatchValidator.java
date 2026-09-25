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
import com.tailor.engine.slots.Slot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Render-based validation (spec section 7.4) — the actual pass/fail gate for
 * a candidate text, unlike calibration's hint (section 7.1). A cheap
 * pre-filter (token length, gross length vs hint) runs first at zero render
 * cost; everything that survives is settled by rendering, one round per
 * position across every slot's surviving candidates at once (the batch
 * principle, section 7.2) — every candidate is tried, not just until the
 * first success, so the caller gets a complete picture to choose from.
 */
public final class BatchValidator {

    public enum Outcome { FITS, FITS_WITH_PADDING, TOO_LONG, UNBREAKABLE_TOKEN, FAR_TOO_LONG, ANCHOR_FAILED }

    public record CandidateResult(int slotIndex, int candidateIndex, Outcome outcome, Integer measuredLines) {
    }

    private static final int MAX_TOKEN_LEN = 25;
    private static final double FAR_TOO_LONG_RATIO = 1.15;

    private BatchValidator() {
    }

    public static List<CandidateResult> validate(
            Path normalizedSource,
            Renderer renderer,
            Map<Integer, Integer> lineCounts,
            Map<Integer, Integer> hints,
            Map<Integer, List<BulletText>> candidatesPerSlot,
            Path workDir) throws Exception {

        List<CandidateResult> results = new ArrayList<>();
        Map<Integer, List<Integer>> survivingOriginalIndex = new LinkedHashMap<>();
        Map<Integer, List<BulletText>> surviving = new LinkedHashMap<>();

        for (Map.Entry<Integer, List<BulletText>> e : candidatesPerSlot.entrySet()) {
            int slotIndex = e.getKey();
            List<BulletText> candidates = e.getValue();
            Integer hint = hints.get(slotIndex);
            List<Integer> keepOriginal = new ArrayList<>();
            List<BulletText> keep = new ArrayList<>();

            for (int j = 0; j < candidates.size(); j++) {
                BulletText c = candidates.get(j);
                if (longestToken(c.text()) > MAX_TOKEN_LEN) {
                    results.add(new CandidateResult(slotIndex, j, Outcome.UNBREAKABLE_TOKEN, null));
                    continue;
                }
                if (hint != null && c.text().length() > hint * FAR_TOO_LONG_RATIO) {
                    results.add(new CandidateResult(slotIndex, j, Outcome.FAR_TOO_LONG, null));
                    continue;
                }
                keepOriginal.add(j);
                keep.add(c);
            }
            survivingOriginalIndex.put(slotIndex, keepOriginal);
            surviving.put(slotIndex, keep);
        }

        int maxRounds = surviving.values().stream().mapToInt(List::size).max().orElse(0);

        for (int round = 0; round < maxRounds; round++) {
            Map<Integer, BulletText> roundCandidates = new LinkedHashMap<>();
            Map<Integer, Integer> roundOriginalIndex = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<BulletText>> e : surviving.entrySet()) {
                int slotIndex = e.getKey();
                List<BulletText> list = e.getValue();
                if (round < list.size()) {
                    roundCandidates.put(slotIndex, list.get(round));
                    roundOriginalIndex.put(slotIndex, survivingOriginalIndex.get(slotIndex).get(round));
                }
            }
            if (roundCandidates.isEmpty()) {
                continue;
            }

            Path probeDocx = workDir.resolve("validate-round-" + round + "-" + System.nanoTime() + ".docx");
            Map<Integer, Integer> measured =
                    renderRound(normalizedSource, roundCandidates, renderer, probeDocx, workDir);

            for (Map.Entry<Integer, BulletText> e : roundCandidates.entrySet()) {
                int slotIndex = e.getKey();
                int originalIndex = roundOriginalIndex.get(slotIndex);
                Integer measuredLines = measured.get(slotIndex);
                Integer targetL = lineCounts.get(slotIndex);
                Outcome outcome;
                if (measuredLines == null || targetL == null) {
                    outcome = Outcome.ANCHOR_FAILED;
                } else if (measuredLines.equals(targetL)) {
                    outcome = Outcome.FITS;
                } else if (measuredLines < targetL) {
                    outcome = Outcome.FITS_WITH_PADDING;
                } else {
                    outcome = Outcome.TOO_LONG;
                }
                results.add(new CandidateResult(slotIndex, originalIndex, outcome, measuredLines));
            }
        }

        return results;
    }

    private static Map<Integer, Integer> renderRound(
            Path source, Map<Integer, BulletText> roundCandidates, Renderer renderer, Path outDocx, Path workDir)
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
            BulletText candidate = roundCandidates.get(i);
            if (candidate != null && s.supported()) {
                Substituter.substitute(s.element(), candidate);
                textsForAnchor.add(candidate.text());
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

    /** Longest whitespace-delimited token; "/"- and "-"-joined strings are already one token this way. */
    private static int longestToken(String text) {
        int longest = 0;
        for (String tok : text.split(" ")) {
            longest = Math.max(longest, tok.length());
        }
        return longest;
    }
}