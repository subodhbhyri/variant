package com.tailor.engine.layout;

import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.docx.XmlSerialize;
import java.io.IOException;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * PHASE2_SPEC.md 4.1 step 0: removes trailing empty paragraphs at the end of
 * the body, before the page rule renders anything. A resume with 30 trailing
 * empty paragraphs renders a blank page 2 (measured); removing them gives 1.
 */
public final class TrailingEmptyParagraphs {

    private static final List<String> DISQUALIFYING_DESCENDANTS =
            List.of("drawing", "pict", "object", "br", "sym", "tab");

    private TrailingEmptyParagraphs() {
    }

    /** Strips qualifying trailing paragraphs from {@code pkg}'s word/document.xml in place. */
    public static int stripFromPackage(DocxPackage pkg) throws IOException {
        byte[] documentXml = pkg.readPart("word/document.xml");
        if (documentXml == null) {
            return 0;
        }
        Document doc = SafeXml.parse(documentXml);
        int removed = strip(doc);
        if (removed > 0) {
            pkg.writePart("word/document.xml", XmlSerialize.toBytes(doc));
        }
        return removed;
    }

    /** Strips qualifying trailing paragraphs from {@code doc}'s body in place; returns how many. */
    static int strip(Document doc) {
        Element body = DomUtil.firstChild(doc.getDocumentElement(), "body");
        if (body == null) {
            return 0;
        }
        List<Element> children = DomUtil.elementChildren(body);
        int end = children.size();
        // The body's own final section properties are its last child, not a paragraph.
        if (end > 0 && "sectPr".equals(children.get(end - 1).getLocalName())) {
            end--;
        }

        int removed = 0;
        int i = end - 1;
        while (i >= 0) {
            Element el = children.get(i);
            if (!"p".equals(el.getLocalName()) || !qualifies(el)) {
                break;
            }
            // Word requires a paragraph directly after a table; never remove that one.
            if (i > 0 && "tbl".equals(children.get(i - 1).getLocalName())) {
                break;
            }
            body.removeChild(el);
            removed++;
            i--;
        }
        return removed;
    }

    private static boolean qualifies(Element p) {
        if (!DomUtil.allText(p).strip().isEmpty()) {
            return false;
        }
        for (String tag : DISQUALIFYING_DESCENDANTS) {
            if (DomUtil.containsDescendant(p, tag)) {
                return false;
            }
        }
        Element pPr = DomUtil.firstChild(p, "pPr");
        return pPr == null || DomUtil.firstChild(pPr, "sectPr") == null;
    }
}
