package com.tailor.engine.blocks;

import com.tailor.engine.docx.DomUtil;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;

/**
 * PHASE3_SPEC.md section 6: flattens an inline block's paragraph into its header's
 * original nodes plus one {@link BulletGroup} per inline bullet, each keeping the
 * original DOM nodes of its segments (for verbatim reuse when a bullet isn't being
 * rewritten) split at the paragraph's run-level {@code <w:br/>} boundaries. Groups
 * segments into bullets the same way {@link BlockDetector} does (same {@link
 * BlockDetector#GLYPHS} set, same vacuous-true-for-a-blank-segment rule), so a
 * swap's rewrite always addresses the same bullets {@link Position} detected.
 */
public final class InlineTemplate {

    public record BulletGroup(List<List<Element>> segments) {
    }

    public record Model(List<Element> headerNodes, List<BulletGroup> groups) {
    }

    private InlineTemplate() {
    }

    public static Model build(Element paragraph) {
        List<List<Element>> segmentNodes = new ArrayList<>();
        segmentNodes.add(new ArrayList<>());
        for (Element child : DomUtil.elementChildren(paragraph)) {
            String ln = child.getLocalName();
            if ("r".equals(ln) && isBareBreakRun(child)) {
                segmentNodes.add(new ArrayList<>());
                continue;
            }
            if ("r".equals(ln) || "hyperlink".equals(ln)) {
                segmentNodes.get(segmentNodes.size() - 1).add(child);
            }
            // pPr/bookmarkStart/bookmarkEnd/proofErr and anything else: not part of any segment.
        }

        List<Element> headerNodes = segmentNodes.get(0);
        List<BulletGroup> groups = new ArrayList<>();
        for (int i = 1; i < segmentNodes.size(); i++) {
            List<Element> seg = segmentNodes.get(i);
            if (startsWithGlyph(seg) || groups.isEmpty()) {
                List<List<Element>> segs = new ArrayList<>();
                segs.add(seg);
                groups.add(new BulletGroup(segs));
            } else {
                groups.get(groups.size() - 1).segments().add(seg);
            }
        }
        return new Model(headerNodes, groups);
    }

    /** Concatenation of a bullet group's original text, across every segment, in order. */
    public static String ownText(BulletGroup group) {
        StringBuilder sb = new StringBuilder();
        for (List<Element> seg : group.segments()) {
            for (Element n : seg) {
                sb.append(DomUtil.allText(n));
            }
        }
        return sb.toString();
    }

    /** The rPr of the group's first run/hyperlink whose text isn't purely glyph/whitespace
     * (PHASE3_SPEC.md section 6 rule 2: not the glyph run, not a break run). Null if none found. */
    static Element firstRealTextRpr(BulletGroup group) {
        for (List<Element> seg : group.segments()) {
            for (Element node : seg) {
                if (!isGlyphOrBlank(DomUtil.allText(node))) {
                    return rprOf(node);
                }
            }
        }
        return null;
    }

    /** The rPr of the header segment's first run/hyperlink with visible text (no glyph to skip). */
    static Element firstRealTextRpr(List<Element> headerNodes) {
        for (Element node : headerNodes) {
            if (!DomUtil.allText(node).isBlank()) {
                return rprOf(node);
            }
        }
        return null;
    }

    private static Element rprOf(Element node) {
        return "hyperlink".equals(node.getLocalName()) ? rprOfLastInnerRun(node) : DomUtil.firstChild(node, "rPr");
    }

    static Element rprOfLastInnerRun(Element hyperlink) {
        Element rpr = null;
        for (Element r : DomUtil.elementChildren(hyperlink)) {
            if ("r".equals(r.getLocalName())) {
                rpr = DomUtil.firstChild(r, "rPr");
            }
        }
        return rpr;
    }

    private static boolean isGlyphOrBlank(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c) && BlockDetector.GLYPHS.indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /** Same rule as BlockDetector's own (private) startsWithGlyph: Python's
     * {@code s.lstrip()[:1] in GLYPHS} is vacuously true for a blank segment too. */
    private static boolean startsWithGlyph(List<Element> segmentNodes) {
        StringBuilder sb = new StringBuilder();
        for (Element n : segmentNodes) {
            sb.append(DomUtil.allText(n));
        }
        String stripped = lstrip(sb.toString());
        String prefix = stripped.isEmpty() ? "" : stripped.substring(0, 1);
        return prefix.isEmpty() || BlockDetector.GLYPHS.indexOf(prefix.charAt(0)) >= 0;
    }

    private static String lstrip(String s) {
        int start = 0;
        while (start < s.length() && Character.isWhitespace(s.charAt(start))) {
            start++;
        }
        return s.substring(start);
    }

    /** A run whose only meaningful (non-rPr) child is {@code <w:br/>}. */
    private static boolean isBareBreakRun(Element run) {
        Element only = null;
        int count = 0;
        for (Element c : DomUtil.elementChildren(run)) {
            if ("rPr".equals(c.getLocalName())) {
                continue;
            }
            count++;
            only = c;
        }
        return count == 1 && "br".equals(only.getLocalName());
    }
}
