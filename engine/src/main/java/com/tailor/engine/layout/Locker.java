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

    /** Leftover (after removing the bullet's own text once) longer than this is foreign,
     * regardless of content — PHASE2_SPEC.md 4.2's "more than 3 characters". */
    private static final int MAX_LEFTOVER_CHARS = 3;

    public record LockedSlot(Slot slot, boolean editable, String lockReason) {
    }

    private Locker() {
    }

    /**
     * @param glyphBySlotIndex each slot's own numbering-level glyph (lvlText), e.g. "•" or
     *                         "o" — a letter/digit in that glyph is never treated as foreign.
     *                         A slot missing from this map (no numbering info resolved) gets no
     *                         glyph tolerance at all.
     */
    public static List<LockedSlot> lock(
            List<Slot> slots, List<PdfLines.Line> lines, Map<Integer, String> glyphBySlotIndex) {
        List<String> slotTexts = slots.stream().map(Slot::text).toList();
        Map<Integer, int[]> spans = AnchorMeasurer.measureSpans(lines, slotTexts);

        List<LockedSlot> out = new ArrayList<>();
        for (Slot s : slots) {
            if (!s.supported()) {
                out.add(new LockedSlot(s, false, s.unsupportedReasons().get(0)));
                continue;
            }
            int[] span = spans.get(s.index());
            String glyph = glyphBySlotIndex.getOrDefault(s.index(), "");
            if (span == null || hasForeignLeftover(lines, span, s.text(), glyph)) {
                out.add(new LockedSlot(s, false, "shared_lines"));
                continue;
            }
            out.add(new LockedSlot(s, true, null));
        }
        return out;
    }

    /**
     * PHASE2_SPEC.md 4.2: after removing the bullet's own text once, what remains is foreign
     * if it's longer than 3 characters, or contains a letter or digit that isn't part of the
     * bullet's own glyph. A neighbour's text almost always contains a letter or digit, however
     * short ("Go", "2024"); a bare length check lets those through.
     */
    private static boolean hasForeignLeftover(List<PdfLines.Line> lines, int[] span, String slotText, String glyph) {
        StringBuilder combined = new StringBuilder();
        for (int k = span[0]; k <= span[1]; k++) {
            combined.append(lines.get(k).normalizedText());
        }
        String normalizedSlot = normalize(slotText);
        int idx = combined.indexOf(normalizedSlot);
        if (idx < 0) {
            return true; // shouldn't happen: AnchorMeasurer already found this span by containment
        }
        String leftover = combined.substring(0, idx) + combined.substring(idx + normalizedSlot.length());
        if (leftover.length() > MAX_LEFTOVER_CHARS) {
            return true;
        }
        String normalizedGlyph = normalize(glyph);
        for (int i = 0; i < leftover.length(); i++) {
            char c = leftover.charAt(i);
            if (Character.isLetterOrDigit(c) && normalizedGlyph.indexOf(c) < 0) {
                return true;
            }
        }
        return false;
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
