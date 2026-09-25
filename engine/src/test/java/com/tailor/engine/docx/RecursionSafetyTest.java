package com.tailor.engine.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * P2-T6 (PHASE2_SPEC.md section 6): {@link BodyWalker} and
 * {@link DomUtil#descendants} must handle a 5,000-deep in-memory DOM without
 * a stack overflow. This builds the DOM directly (bypassing SafeXml's depth
 * gate) to prove the walkers themselves are safe, per PHASE2_SPEC.md 2.1.1:
 * "this makes them safe if someone later bypasses the gate."
 */
class RecursionSafetyTest {

    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final int DEPTH = 5000;

    @Test
    void bodyWalkerHandlesDeepNestingWithoutStackOverflow() throws Exception {
        Document doc = deepDocument(DEPTH);

        List<ParagraphRef> paragraphs = BodyWalker.walk(doc);

        assertEquals(1, paragraphs.size());
        ParagraphRef p = paragraphs.get(0);
        assertEquals(DEPTH + 1, p.locator().size());
        assertFalse(p.inTable());
        for (int index : p.locator()) {
            assertEquals(0, index);
        }
    }

    @Test
    void domUtilDescendantsHandlesDeepNestingWithoutStackOverflow() throws Exception {
        Document doc = deepDocument(DEPTH);
        Element body = DomUtil.firstChild(doc.getDocumentElement(), "body");

        List<Element> found = DomUtil.descendants(body, "p");

        assertEquals(1, found.size());
        assertTrue(DomUtil.containsDescendant(body, "p"));
        assertFalse(DomUtil.containsDescendant(body, "tbl"));
    }

    /**
     * Builds {@code <w:document><w:body><w:x>...<w:x><w:p/></w:x>...</w:body></w:document>}
     * with {@code depth} levels of {@code w:x} wrapping a single paragraph. BodyWalker
     * recurses into every element unconditionally (not just tbl/sdt/customXml), so a
     * generic wrapper tag exercises the same code path as real structural nesting.
     */
    private static Document deepDocument(int depth) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.newDocument();

        Element documentEl = doc.createElementNS(W_NS, "w:document");
        doc.appendChild(documentEl);
        Element body = doc.createElementNS(W_NS, "w:body");
        documentEl.appendChild(body);

        Element cursor = body;
        for (int i = 0; i < depth; i++) {
            Element wrapper = doc.createElementNS(W_NS, "w:x");
            cursor.appendChild(wrapper);
            cursor = wrapper;
        }
        Element p = doc.createElementNS(W_NS, "w:p");
        cursor.appendChild(p);

        return doc;
    }
}
