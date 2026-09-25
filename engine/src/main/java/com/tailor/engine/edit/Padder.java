package com.tailor.engine.edit;

import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.XmlBuild;
import java.util.List;
import org.w3c.dom.Element;

/**
 * Pads a paragraph whose new text renders in fewer lines than its slot: hard
 * breaks plus non-breaking spaces appended to the last run (spec section
 * 5.4). Called after a render shows the new text uses {@code k < L} lines.
 */
public final class Padder {

    private Padder() {
    }

    public static void pad(Element paragraph, int additionalLines) {
        if (additionalLines <= 0) {
            return;
        }
        List<Element> runs = DomUtil.descendants(paragraph, "r");
        if (runs.isEmpty()) {
            throw new IllegalStateException("paragraph has no runs to pad");
        }
        Element lastRun = runs.get(runs.size() - 1);

        for (int i = 0; i < additionalLines; i++) {
            lastRun.appendChild(XmlBuild.createChild(lastRun, "br"));
            Element t = XmlBuild.createChild(lastRun, "t");
            XmlBuild.setXmlSpacePreserve(t);
            t.setTextContent("\u00A0");
            lastRun.appendChild(t);
        }
    }
}
