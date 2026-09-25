package com.tailor.engine.docx;

import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Walks {@code <w:body>} in true document order, recursing into tables
 * ({@code w:tbl -> w:tr -> w:tc}), content controls ({@code w:sdt ->
 * w:sdtContent}), and {@code w:customXml}, yielding every {@code <w:p>}
 * found with its locator (spec section 4.1).
 */
public final class BodyWalker {

    private BodyWalker() {
    }

    public static List<ParagraphRef> walk(Document doc) {
        Element documentEl = doc.getDocumentElement(); // <w:document>
        Element body = DomUtil.firstChild(documentEl, "body");
        if (body == null) {
            throw new IllegalArgumentException("no <w:body> found");
        }
        List<ParagraphRef> out = new ArrayList<>();
        walkRecursive(body, new ArrayList<>(), false, out);
        return out;
    }

    private static void walkRecursive(
            Element parent, List<Integer> pathSoFar, boolean inTable, List<ParagraphRef> out) {
        List<Element> children = DomUtil.elementChildren(parent);
        for (int i = 0; i < children.size(); i++) {
            Element child = children.get(i);
            List<Integer> childPath = new ArrayList<>(pathSoFar);
            childPath.add(i);

            boolean childInTable = inTable || "tbl".equals(child.getLocalName());

            if ("p".equals(child.getLocalName())) {
                out.add(new ParagraphRef(child, List.copyOf(childPath), childInTable));
            }

            walkRecursive(child, childPath, childInTable, out);
        }
    }
}
