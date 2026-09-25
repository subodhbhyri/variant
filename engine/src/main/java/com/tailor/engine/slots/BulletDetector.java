package com.tailor.engine.slots;

import com.tailor.engine.docx.BodyWalker;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.ParagraphRef;
import com.tailor.engine.docx.RunFlags;
import com.tailor.engine.numbering.NumberingResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Detects bullet slots: paragraphs whose effective numbering format is
 * "bullet", outside tables, with non-blank text (spec section 4.2 point 4).
 * Slots are numbered in document order.
 */
public final class BulletDetector {

    private BulletDetector() {
    }

    public static List<Slot> detect(Document documentXml, NumberingResolver numberingResolver) {
        List<ParagraphRef> paragraphs = BodyWalker.walk(documentXml);
        List<Slot> slots = new ArrayList<>();
        int index = 0;

        for (ParagraphRef ref : paragraphs) {
            if (ref.inTable()) {
                continue;
            }
            Optional<String> fmt = numberingResolver.resolveNumFmt(ref.element());
            if (fmt.isEmpty() || !"bullet".equals(fmt.get())) {
                continue;
            }
            String text = DomUtil.allText(ref.element());
            if (text.strip().isEmpty()) {
                continue;
            }

            List<EmphasisSpan> emphasis = extractEmphasis(ref.element());
            List<String> reasons = UnsupportedReasons.of(ref.element());

            slots.add(new Slot(index, ref.locator(), ref.element(), text, emphasis, reasons.isEmpty(), reasons));
            index++;
        }
        return slots;
    }

    /** Spec section 4.5: bold/italic spans over the paragraph's concatenated text. */
    private static List<EmphasisSpan> extractEmphasis(Element p) {
        List<EmphasisSpan> emphasis = new ArrayList<>();
        int pos = 0;
        for (Element r : DomUtil.descendants(p, "r")) {
            String t = DomUtil.allText(r);
            if (t.isEmpty()) {
                continue;
            }
            Element rPr = DomUtil.firstChild(r, "rPr");
            boolean bold = RunFlags.isOn(rPr, "b");
            boolean italic = RunFlags.isOn(rPr, "i");
            if (bold || italic) {
                emphasis.add(new EmphasisSpan(pos, pos + t.length(), bold, italic));
            }
            pos += t.length();
        }
        return emphasis;
    }
}
