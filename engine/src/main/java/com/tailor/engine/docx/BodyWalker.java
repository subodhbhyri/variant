package com.tailor.engine.docx;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Walks {@code <w:body>} in true document order, recursing into tables
 * ({@code w:tbl -> w:tr -> w:tc}), content controls ({@code w:sdt ->
 * w:sdtContent}), and {@code w:customXml}, yielding every {@code <w:p>}
 * found with its locator (spec section 4.1).
 *
 * <p>Explicit-stack DFS (PHASE2_SPEC.md 2.1.1), not recursion: a crafted part
 * can nest deep enough to overflow the call stack. The depth gate (SafeXml)
 * keeps real input shallow; this is the fallback if something ever reaches
 * here without going through it. The locator path is tracked as a persistent
 * linked chain (one node per stack frame) rather than a copied list per
 * frame, so cost stays linear in the number of elements instead of
 * quadratic in depth.
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
        walkIterative(body, out);
        return out;
    }

    private record PathNode(PathNode parent, int index) {
    }

    private record Frame(Element element, PathNode path, boolean inTable) {
    }

    private static void walkIterative(Element body, List<ParagraphRef> out) {
        Deque<Frame> stack = new ArrayDeque<>();
        pushChildrenReversed(stack, body, null, false);

        while (!stack.isEmpty()) {
            Frame frame = stack.pop();

            if ("p".equals(frame.element().getLocalName())) {
                out.add(new ParagraphRef(frame.element(), toList(frame.path()), frame.inTable()));
            }

            pushChildrenReversed(stack, frame.element(), frame.path(), frame.inTable());
        }
    }

    /** Pushes parent's children (as frames) right-to-left, so popping yields document order. */
    private static void pushChildrenReversed(
            Deque<Frame> stack, Element parent, PathNode parentPath, boolean parentInTable) {
        List<Element> children = DomUtil.elementChildren(parent);
        for (int i = children.size() - 1; i >= 0; i--) {
            Element child = children.get(i);
            boolean childInTable = parentInTable || "tbl".equals(child.getLocalName());
            stack.push(new Frame(child, new PathNode(parentPath, i), childInTable));
        }
    }

    private static List<Integer> toList(PathNode node) {
        List<Integer> reversed = new ArrayList<>();
        for (PathNode n = node; n != null; n = n.parent()) {
            reversed.add(n.index());
        }
        List<Integer> out = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            out.add(reversed.get(i));
        }
        return List.copyOf(out);
    }
}
