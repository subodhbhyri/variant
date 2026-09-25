package com.tailor.engine.edit;

import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.XmlBuild;
import com.tailor.engine.slots.UnsupportedReasons;
import java.util.List;
import org.w3c.dom.Element;

/**
 * Deletes a bullet's content while holding its exact vertical space (spec
 * section 5.3): the numbering is kept, the dot is painted white rather than
 * removed (removing it shifts the line height), and the content becomes
 * non-breaking spaces with hard breaks between them so the paragraph still
 * occupies {@code lineCount} lines.
 */
public final class Blanker {

    private Blanker() {
    }

    public static void blank(Element paragraph, int lineCount) {
        if (lineCount < 1) {
            throw new IllegalArgumentException("lineCount must be >= 1, got " + lineCount);
        }
        List<String> reasons = UnsupportedReasons.of(paragraph);
        if (!reasons.isEmpty()) {
            throw new UnsupportedBulletException(reasons);
        }

        Element baseRun = BaseRunSelector.pick(paragraph);
        Element baseRPr = baseRun != null ? DomUtil.firstChild(baseRun, "rPr") : null;

        XmlBuild.removeAllExceptPPr(paragraph);
        paintParagraphMarkWhite(paragraph);

        Element run = XmlBuild.createChild(paragraph, "r");
        if (baseRPr != null) {
            run.appendChild(baseRPr.cloneNode(true));
        }
        run.appendChild(nbspText(paragraph));
        for (int i = 1; i < lineCount; i++) {
            run.appendChild(XmlBuild.createChild(paragraph, "br"));
            run.appendChild(nbspText(paragraph));
        }
        paragraph.appendChild(run);
    }

    private static Element nbspText(Element template) {
        Element t = XmlBuild.createChild(template, "t");
        XmlBuild.setXmlSpacePreserve(t);
        t.setTextContent("\u00A0");
        return t;
    }

    private static void paintParagraphMarkWhite(Element paragraph) {
        Element pPr = DomUtil.firstChild(paragraph, "pPr");
        if (pPr == null) {
            pPr = XmlBuild.createChild(paragraph, "pPr");
            paragraph.insertBefore(pPr, paragraph.getFirstChild());
        }
        Element markRPr = DomUtil.firstChild(pPr, "rPr");
        if (markRPr == null) {
            markRPr = XmlBuild.createChild(pPr, "rPr");
            pPr.appendChild(markRPr);
        }
        Element color = DomUtil.firstChild(markRPr, "color");
        if (color == null) {
            color = XmlBuild.insertInRPrOrder(markRPr, "color");
        }
        XmlBuild.setAttr(color, "val", "FFFFFF");
    }
}
