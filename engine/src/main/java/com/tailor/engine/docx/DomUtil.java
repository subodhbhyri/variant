package com.tailor.engine.docx;

import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Namespace-agnostic DOM helpers: match by local name only, ignore prefixes. */
public final class DomUtil {

    private DomUtil() {
    }

    /** Direct child Elements, in document order — filters out text/comment nodes. */
    public static List<Element> elementChildren(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) n);
            }
        }
        return out;
    }

    /** First direct child Element with the given local name, or null. */
    public static Element firstChild(Element parent, String localName) {
        for (Element c : elementChildren(parent)) {
            if (localName.equals(c.getLocalName())) {
                return c;
            }
        }
        return null;
    }

    /** All descendant Elements with the given local name, in document order. */
    public static List<Element> descendants(Element root, String localName) {
        List<Element> out = new ArrayList<>();
        collectDescendants(root, localName, out);
        return out;
    }

    private static void collectDescendants(Element node, String localName, List<Element> out) {
        for (Element child : elementChildren(node)) {
            if (localName.equals(child.getLocalName())) {
                out.add(child);
            }
            collectDescendants(child, localName, out);
        }
    }

    /** True if any element in root's subtree (root included) has the given local name. */
    public static boolean containsDescendant(Element root, String localName) {
        if (localName.equals(root.getLocalName())) {
            return true;
        }
        for (Element child : elementChildren(root)) {
            if (containsDescendant(child, localName)) {
                return true;
            }
        }
        return false;
    }

    /** Attribute value ignoring namespace prefix (matches by local attribute name "val" etc.), or null. */
    public static String attr(Element el, String localName) {
        NamedNodeMapWrapper attrs = new NamedNodeMapWrapper(el);
        return attrs.get(localName);
    }

    /** Concatenated text of all descendant w:t elements, in document order. */
    public static String allText(Element root) {
        StringBuilder sb = new StringBuilder();
        for (Element t : descendants(root, "t")) {
            sb.append(textContent(t));
        }
        return sb.toString();
    }

    public static String textContent(Element el) {
        StringBuilder sb = new StringBuilder();
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.TEXT_NODE) {
                sb.append(n.getNodeValue());
            }
        }
        return sb.toString();
    }

    /** Thin wrapper so attr() reads naturally; avoids importing org.w3c.dom.NamedNodeMap everywhere. */
    private record NamedNodeMapWrapper(Element el) {
        String get(String localName) {
            var attrs = el.getAttributes();
            if (attrs == null) {
                return null;
            }
            for (int i = 0; i < attrs.getLength(); i++) {
                Node a = attrs.item(i);
                if (localName.equals(a.getLocalName())) {
                    return a.getNodeValue();
                }
            }
            return null;
        }
    }
}
