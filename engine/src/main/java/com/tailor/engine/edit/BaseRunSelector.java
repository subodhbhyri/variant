package com.tailor.engine.edit;

import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.RunFlags;
import org.w3c.dom.Element;

/**
 * Picks the run whose properties template a rewritten bullet (spec section
 * 5.2 step 1): the run with the most characters that is NOT bold or italic;
 * if every run with text is emphasized, the longest overall.
 */
public final class BaseRunSelector {

    private BaseRunSelector() {
    }

    /** Returns the chosen run, or null if the paragraph has no run with non-empty text. */
    public static Element pick(Element paragraph) {
        Element bestPlain = null;
        int bestPlainLen = -1;
        Element bestAny = null;
        int bestAnyLen = -1;

        for (Element r : DomUtil.descendants(paragraph, "r")) {
            String text = DomUtil.allText(r);
            if (text.isEmpty()) {
                continue;
            }
            int len = text.length();
            if (len > bestAnyLen) {
                bestAnyLen = len;
                bestAny = r;
            }
            Element rPr = DomUtil.firstChild(r, "rPr");
            boolean emphasized = RunFlags.isOn(rPr, "b") || RunFlags.isOn(rPr, "i");
            if (!emphasized && len > bestPlainLen) {
                bestPlainLen = len;
                bestPlain = r;
            }
        }
        return bestPlain != null ? bestPlain : bestAny;
    }
}
