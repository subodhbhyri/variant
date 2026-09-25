package com.tailor.engine.layout;

import com.tailor.engine.measure.AnchorMeasurer;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.slots.Slot;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PHASE2_SPEC.md 4.2: a supported bullet is locked (visible, never edited) if
 * it can't be anchored, or its anchored lines carry text beyond its own
 * (side-by-side columns, a sidebar, a text box) — "shared_lines". An
 * unsupported bullet is locked for its own Phase 1 4.4 reason.
 */
public final class Locker {

    /**
     * How many characters an anchored span may exceed the slot's own (normalized,
     * whitespace-stripped) text by and still count as "just the bullet glyph".
     * A real glyph contributes one character; a neighboring column's text would
     * add many times that, so this stays strict without being fragile.
     */
    private static final int MAX_GLYPH_CHARS = 3;

    public record LockedSlot(Slot slot, boolean editable, String lockReason) {
    }

    private Locker() {
    }

    public static List<LockedSlot> lock(List<Slot> slots, List<PdfLines.Line> lines) {
        List<String> slotTexts = slots.stream().map(Slot::text).toList();
        Map<Integer, int[]> spans = AnchorMeasurer.measureSpans(lines, slotTexts);

        List<LockedSlot> out = new ArrayList<>();
        for (Slot s : slots) {
            if (!s.supported()) {
                out.add(new LockedSlot(s, false, s.unsupportedReasons().get(0)));
                continue;
            }
            int[] span = spans.get(s.index());
            if (span == null || hasForeignText(lines, span, s.text())) {
                out.add(new LockedSlot(s, false, "shared_lines"));
                continue;
            }
            out.add(new LockedSlot(s, true, null));
        }
        return out;
    }

    private static boolean hasForeignText(List<PdfLines.Line> lines, int[] span, String slotText) {
        StringBuilder combined = new StringBuilder();
        for (int k = span[0]; k <= span[1]; k++) {
            combined.append(lines.get(k).normalizedText());
        }
        String normalizedSlot = normalize(slotText);
        int extra = combined.length() - normalizedSlot.length();
        return extra < 0 || extra > MAX_GLYPH_CHARS || !combined.toString().contains(normalizedSlot);
    }

    /** Same normalization AnchorMeasurer uses: NFKC, whitespace stripped. */
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
