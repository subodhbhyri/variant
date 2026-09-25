package com.tailor.engine.docx;

import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Small DOM-construction helpers shared by the edit primitives (spec section 5). */
public final class XmlBuild {

    private XmlBuild() {
    }

    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";

    /** New element in the same namespace/prefix as {@code template} (any existing w: element works). */
    public static Element createChild(Element template, String localName) {
        Document doc = template.getOwnerDocument();
        String ns = template.getNamespaceURI();
        String prefix = template.getPrefix();
        return doc.createElementNS(ns, (prefix != null && !prefix.isEmpty() ? prefix + ":" : "") + localName);
    }

    /** Sets an attribute in the same namespace as {@code el} (e.g. w:val). */
    public static void setAttr(Element el, String localName, String value) {
        String ns = el.getNamespaceURI();
        String prefix = el.getPrefix();
        el.setAttributeNS(ns, (prefix != null && !prefix.isEmpty() ? prefix + ":" : "") + localName, value);
    }

    public static void setXmlSpacePreserve(Element t) {
        t.setAttributeNS(XML_NS, "xml:space", "preserve");
    }

    /** Removes every child of {@code paragraph} except {@code w:pPr} (spec 5.2 step 2). */
    public static void removeAllExceptPPr(Element paragraph) {
        for (Element child : DomUtil.elementChildren(paragraph)) {
            if (!"pPr".equals(child.getLocalName())) {
                paragraph.removeChild(child);
            }
        }
    }

    // CT_RPr's element sequence, as far as we need it to insert w:b/w:i/w:color correctly.
    private static final List<String> RPR_ORDER = List.of(
            "rStyle", "rFonts", "b", "bCs", "i", "iCs", "caps", "smallCaps", "strike", "dstrike",
            "outline", "shadow", "emboss", "imprint", "noProof", "snapToGrid", "vanish", "webHidden",
            "color", "spacing", "w", "kern", "position", "sz", "szCs", "highlight", "u", "effect",
            "bdr", "shd", "fitText", "vertAlign", "rtl", "cs", "em", "lang", "eastAsianLayout",
            "specVanish", "oMath");

    /** Inserts a new child of {@code localName} into {@code rPr} at its correct CT_RPr schema position. */
    public static Element insertInRPrOrder(Element rPr, String localName) {
        Element newEl = createChild(rPr, localName);
        int newRank = RPR_ORDER.indexOf(localName);
        Element insertBefore = null;
        for (Element child : DomUtil.elementChildren(rPr)) {
            int rank = RPR_ORDER.indexOf(child.getLocalName());
            if (rank < 0) {
                continue; // unrecognized child (e.g. an extension); ignore it for ordering
            }
            if (rank > newRank) {
                insertBefore = child;
                break;
            }
        }
        if (insertBefore != null) {
            rPr.insertBefore(newEl, insertBefore);
        } else {
            rPr.appendChild(newEl);
        }
        return newEl;
    }
}
