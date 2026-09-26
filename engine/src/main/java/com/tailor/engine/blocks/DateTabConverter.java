package com.tailor.engine.blocks;

import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.XmlBuild;
import java.util.List;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * PHASE3_SPEC.md 4.1 (reference: {@code right_align_date_tab}/{@code text_width_twips}):
 * when a header with {@code TAB ... DATE} is rewritten, replaces its tab stops with a
 * single right tab at the date's measured right edge, clamped to the text width.
 */
public final class DateTabConverter {

    /** CT_PPr's element sequence from {@code w:tabs} onward — where a new {@code w:tabs} must
     * be inserted before, to stay schema-valid. Order among these doesn't matter here; only
     * "is this the first existing child that must come after tabs" does. */
    private static final Set<String> AFTER_TABS = Set.of(
            "suppressAutoHyphens", "kinsoku", "wordWrap", "overflowPunct", "topLinePunct", "autoSpaceDE",
            "autoSpaceDN", "bidi", "adjustRightInd", "snapToGrid", "spacing", "ind", "contextualSpacing",
            "mirrorIndents", "suppressOverlap", "jc", "textDirection", "textAlignment", "textboxTightWrap",
            "outlineLvl", "divId", "cnfStyle", "rPr", "sectPr", "pPrChange");

    private DateTabConverter() {
    }

    /** The section's left margin, in twips — tab positions are measured from it. */
    public static int leftMarginTwips(Document doc) {
        Element body = DomUtil.firstChild(doc.getDocumentElement(), "body");
        Element sectPr = DomUtil.firstChild(body, "sectPr");
        Element pgMar = DomUtil.firstChild(sectPr, "pgMar");
        return Integer.parseInt(DomUtil.attr(pgMar, "left"));
    }

    /** Page text width in twips: page width minus left/right margins minus the paragraph's own right indent. */
    public static int textWidthTwips(Document doc, Element paragraph) {
        Element body = DomUtil.firstChild(doc.getDocumentElement(), "body");
        Element sectPr = DomUtil.firstChild(body, "sectPr");
        Element pgSz = DomUtil.firstChild(sectPr, "pgSz");
        Element pgMar = DomUtil.firstChild(sectPr, "pgMar");
        int pageWidth = Integer.parseInt(DomUtil.attr(pgSz, "w"));
        int left = Integer.parseInt(DomUtil.attr(pgMar, "left"));
        int right = Integer.parseInt(DomUtil.attr(pgMar, "right"));
        int width = pageWidth - left - right;

        Element pPr = DomUtil.firstChild(paragraph, "pPr");
        Element ind = pPr != null ? DomUtil.firstChild(pPr, "ind") : null;
        int rightIndent = 0;
        if (ind != null) {
            String r = DomUtil.attr(ind, "right");
            if (r == null) {
                r = DomUtil.attr(ind, "end");
            }
            rightIndent = r != null ? Integer.parseInt(r) : 0;
        }
        return width - rightIndent;
    }

    /**
     * Only call on a header being rewritten. Recomputes the template from {@code paragraph}'s
     * current content (matching the reference exactly), so call this after
     * {@link HeaderRenderer#render} has already run.
     *
     * @param measuredEdgeTwips the raw (untruncated) measured edge — truncated to an int here,
     *                          at the same point the reference's {@code int(measured_edge_twips)}
     *                          does, not earlier when measuring
     * @return true if the tab stops were replaced (the header has TAB before DATE)
     */
    public static boolean rightAlignDateTab(Document doc, Element paragraph, double measuredEdgeTwips) {
        List<HeaderToken> template = HeaderTemplate.build(paragraph);
        int tabIndex = -1;
        int dateIndex = -1;
        for (int i = 0; i < template.size(); i++) {
            HeaderToken.Kind kind = template.get(i).kind();
            if (kind == HeaderToken.Kind.TAB && tabIndex < 0) {
                tabIndex = i;
            }
            if (kind == HeaderToken.Kind.DATE && dateIndex < 0) {
                dateIndex = i;
            }
        }
        if (tabIndex < 0 || dateIndex < 0 || tabIndex > dateIndex) {
            return false;
        }

        int pos = Math.min((int) measuredEdgeTwips, textWidthTwips(doc, paragraph));

        Element pPr = DomUtil.firstChild(paragraph, "pPr");
        if (pPr == null) {
            pPr = XmlBuild.createChild(paragraph, "pPr");
            paragraph.insertBefore(pPr, paragraph.getFirstChild());
        }
        Element existingTabs = DomUtil.firstChild(pPr, "tabs");
        if (existingTabs != null) {
            pPr.removeChild(existingTabs);
        }

        Element tabs = XmlBuild.createChild(pPr, "tabs");
        Element tab = XmlBuild.createChild(tabs, "tab");
        XmlBuild.setAttr(tab, "val", "right");
        XmlBuild.setAttr(tab, "pos", String.valueOf(pos));
        tabs.appendChild(tab);

        Element anchor = null;
        for (Element c : DomUtil.elementChildren(pPr)) {
            if (AFTER_TABS.contains(c.getLocalName())) {
                anchor = c;
                break;
            }
        }
        if (anchor != null) {
            pPr.insertBefore(tabs, anchor);
        } else {
            pPr.appendChild(tabs);
        }
        return true;
    }
}
