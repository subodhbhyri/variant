package com.tailor.engine.verify;

import com.tailor.engine.fonts.FontAudit;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.measure.AnchorMeasurer;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.measure.PdfPageCounter;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.slots.DocxBulletDetection;
import com.tailor.engine.slots.Slot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The final gate for an assembled output (spec section 8). Renders the
 * pre-edit source and the assembled result and checks:
 *
 * <ol>
 *   <li>page count is unchanged;
 *   <li>every substituted or unchanged slot keeps its line count (blanked and
 *       padded slots hold their height by construction and are covered by 3);
 *   <li><b>layout</b>: every line that isn't part of an edited slot — headings,
 *       job titles, dates, unchanged bullets — is at the same position (within
 *       0.5pt). An edited slot's first line must also stay put, and a
 *       substituted slot that has no fixed content after it on its page must
 *       keep its last line too;
 *   <li>the font audit passes.
 * </ol>
 *
 * <p>Why not "the last line on the page", as first designed: a blanked or
 * padded line has no visible characters, so when the page ends with one, the
 * last visible line is a different line and the check reports a false shift.
 * Measured: the old check failed T9/T10 on 4 resumes by exactly one line
 * height, while every fixed line had moved 0.0pt. The new check still catches
 * real drift — it rejects the pre-fix substitution on Durga (3.35pt) and
 * Rakesh (3.15pt).
 *
 * <p>Inside a substituted bullet, spacing between its own lines may shift a
 * little when the original text contained a symbol set in a fallback font
 * (Mohan: 0.52pt). That is only flagged when nothing below the bullet on the
 * page would reveal a real height change.
 */
public final class Verifier {

    private static final double LAYOUT_TOLERANCE_PT = 0.5;

    private Verifier() {
    }

    /**
     * @param targetLineCounts   slot index -> line count it must keep (measured on the source)
     * @param assembledSlotTexts the output's slot texts in order: new text for edited slots,
     *                           original text otherwise; a BLANKED slot's entry is ignored
     * @param edits              which slots were edited and how; absent means unchanged
     */
    public static VerifyReport verify(
            Path originalSource,
            Path assembledOutput,
            Renderer renderer,
            FontMap fontMap,
            Map<Integer, Integer> targetLineCounts,
            List<String> assembledSlotTexts,
            Map<Integer, SlotEdit> edits,
            Path workDir) throws Exception {

        List<String> sourceTexts = DocxBulletDetection.detect(originalSource).stream().map(Slot::text).toList();

        Path pdf0 = renderer.render(originalSource, workDir);
        int pagesBefore = PdfPageCounter.count(pdf0);
        List<PdfLines.Line> lines0 = PdfLines.extract(pdf0);

        Path pdf1 = renderer.render(assembledOutput, workDir);
        int pagesAfter = PdfPageCounter.count(pdf1);
        List<PdfLines.Line> lines1 = PdfLines.extract(pdf1);

        List<String> anchorTexts1 = new ArrayList<>();
        for (int i = 0; i < assembledSlotTexts.size(); i++) {
            anchorTexts1.add(edits.get(i) == SlotEdit.BLANKED ? null : assembledSlotTexts.get(i));
        }
        Map<Integer, int[]> spans0 = AnchorMeasurer.measureSpans(lines0, sourceTexts);
        Map<Integer, int[]> spans1 = AnchorMeasurer.measureSpans(lines1, anchorTexts1);

        Map<Integer, LineCheck> lineChecks = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> e : targetLineCounts.entrySet()) {
            int i = e.getKey();
            int target = e.getValue();
            SlotEdit edit = edits.get(i);
            if (edit == SlotEdit.BLANKED || edit == SlotEdit.PADDED) {
                lineChecks.put(i, new LineCheck(target, target, true));
                continue;
            }
            int[] span = spans1.get(i);
            Integer got = span == null ? null : span[1] - span[0] + 1;
            lineChecks.put(i, new LineCheck(target, got, got != null && got == target));
        }

        LayoutResult layout = checkLayout(lines0, spans0, lines1, spans1, edits);
        List<FontAudit.Violation> violations = new FontAudit(fontMap).audit(pdf1);

        boolean pagesMatch = pagesBefore == pagesAfter;
        boolean layoutOk = layout.problem() == null && layout.shift() <= LAYOUT_TOLERANCE_PT;
        boolean linesOk = lineChecks.values().stream().allMatch(LineCheck::ok);
        boolean ok = pagesMatch && layoutOk && linesOk && violations.isEmpty();

        return new VerifyReport(pagesMatch, pagesBefore, pagesAfter, lineChecks,
                layout.shift(), layoutOk, layout.problem(), violations, ok);
    }

    private record LayoutResult(double shift, String problem) {
    }

    private static LayoutResult checkLayout(
            List<PdfLines.Line> lines0, Map<Integer, int[]> spans0,
            List<PdfLines.Line> lines1, Map<Integer, int[]> spans1,
            Map<Integer, SlotEdit> edits) {

        Map<Integer, Integer> slotOfLine0 = slotOfLine(spans0);
        Map<Integer, Integer> slotOfLine1 = slotOfLine(spans1);

        List<Integer> fixed0 = fixedLines(lines0.size(), slotOfLine0, edits);
        List<Integer> fixed1 = fixedLines(lines1.size(), slotOfLine1, edits);

        // 1. Every fixed line must still exist, in order, on the same page, at the same position.
        double worst = 0.0;
        int cursor = 0;
        for (int k0 : fixed0) {
            PdfLines.Line want = lines0.get(k0);
            int match = -1;
            for (int j = cursor; j < fixed1.size(); j++) {
                if (lines1.get(fixed1.get(j)).normalizedText().equals(want.normalizedText())) {
                    match = j;
                    break;
                }
            }
            if (match < 0) {
                return new LayoutResult(Double.NaN, "line missing after edit: " + preview(want));
            }
            PdfLines.Line got = lines1.get(fixed1.get(match));
            if (got.pageIndex() != want.pageIndex()) {
                return new LayoutResult(Double.NaN, "line moved to another page: " + preview(want));
            }
            worst = Math.max(worst, Math.abs(got.y() - want.y()));
            cursor = match + 1;
        }

        // 2. Edited slots: first line stays put; a substituted slot with nothing fixed
        //    after it on its page must also keep its last line.
        for (Map.Entry<Integer, SlotEdit> e : edits.entrySet()) {
            int i = e.getKey();
            if (e.getValue() == SlotEdit.BLANKED) {
                continue;
            }
            int[] s0 = spans0.get(i);
            int[] s1 = spans1.get(i);
            if (s0 == null || s1 == null) {
                return new LayoutResult(Double.NaN, "slot " + i + " could not be found for the layout check");
            }
            worst = Math.max(worst, Math.abs(lines1.get(s1[0]).y() - lines0.get(s0[0]).y()));

            if (e.getValue() == SlotEdit.SUBSTITUTED) {
                int lastPage = lines0.get(s0[1]).pageIndex();
                boolean followed = false;
                for (int k0 : fixed0) {
                    if (k0 > s0[1] && lines0.get(k0).pageIndex() == lastPage) {
                        followed = true;
                        break;
                    }
                }
                if (!followed) {
                    worst = Math.max(worst, Math.abs(lines1.get(s1[1]).y() - lines0.get(s0[1]).y()));
                }
            }
        }
        return new LayoutResult(worst, null);
    }

    private static Map<Integer, Integer> slotOfLine(Map<Integer, int[]> spans) {
        Map<Integer, Integer> m = new HashMap<>();
        for (Map.Entry<Integer, int[]> e : spans.entrySet()) {
            for (int k = e.getValue()[0]; k <= e.getValue()[1]; k++) {
                m.put(k, e.getKey());
            }
        }
        return m;
    }

    /** Lines that don't belong to an edited slot: locked content plus unchanged bullets. */
    private static List<Integer> fixedLines(int lineCount, Map<Integer, Integer> slotOfLine,
                                            Map<Integer, SlotEdit> edits) {
        List<Integer> out = new ArrayList<>();
        for (int k = 0; k < lineCount; k++) {
            Integer slot = slotOfLine.get(k);
            if (slot == null || !edits.containsKey(slot)) {
                out.add(k);
            }
        }
        return out;
    }

    private static String preview(PdfLines.Line line) {
        String t = line.normalizedText();
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }
}