package com.tailor.engine.measure;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps each slot's line count by finding its own known text in the rendered
 * lines — no sentinel text is inserted, so the measured document is the
 * actual deliverable (spec section 6.2).
 */
public final class AnchorMeasurer {

    private static final int PREFIX_LEN = 14;
    private static final int MAX_LINE_SPAN = 8;

    private AnchorMeasurer() {
    }

    /**
     * @param lines     extracted from the render, in document order
     * @param slotTexts every slot's own text, in document order (include
     *                  unsupported slots too, so the cursor advances correctly)
     * @return slot index -> line count; a missing entry means that slot's text
     *         could not be found (treated as a failure, never as a pass)
     */
    public static Map<Integer, Integer> measure(List<PdfLines.Line> lines, List<String> slotTexts) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<Integer, int[]> e : measureSpans(lines, slotTexts).entrySet()) {
            counts.put(e.getKey(), e.getValue()[1] - e.getValue()[0] + 1);
        }
        return counts;
    }

    /**
     * Like {@link #measure}, but returns each slot's line range: slot index ->
     * {first line index, last line index} into {@code lines}. The Verifier uses
     * the ranges to tell which lines belong to which bullet.
     */
    public static Map<Integer, int[]> measureSpans(List<PdfLines.Line> lines, List<String> slotTexts) {
        Map<Integer, int[]> result = new LinkedHashMap<>();
        int cursor = 0;

        for (int i = 0; i < slotTexts.size(); i++) {
            String raw = slotTexts.get(i);
            if (raw == null) {
                continue; // blanked slot (spec 6.2): no visible text to find
            }
            String t = normalize(raw);
            if (t.isEmpty()) {
                continue;
            }
            String prefix = t.substring(0, Math.min(PREFIX_LEN, t.length()));

            int a = -1;
            for (int k = cursor; k < lines.size(); k++) {
                if (lines.get(k).normalizedText().contains(prefix)) {
                    a = k;
                    break;
                }
            }
            if (a < 0) {
                continue;
            }

            int b = -1;
            StringBuilder acc = new StringBuilder();
            int limit = Math.min(a + MAX_LINE_SPAN, lines.size());
            for (int k = a; k < limit; k++) {
                acc.append(lines.get(k).normalizedText());
                if (acc.toString().contains(t)) {
                    b = k;
                    break;
                }
            }
            if (b < 0) {
                continue;
            }

            result.put(i, new int[] {a, b});
            // Next slot is a separate paragraph, so it starts on a later line.
            cursor = b + 1;
        }
        return result;
    }

    private static String normalize(String s) {
        String nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nfkc.length(); i++) {
            char c = nfkc.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}