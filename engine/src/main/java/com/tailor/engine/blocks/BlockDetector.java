package com.tailor.engine.blocks;

import com.tailor.engine.docx.DomUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * PHASE3_SPEC.md section 3 (reference: {@code detect_blocks}): splits a section's
 * paragraphs into paragraph-kind blocks (header paragraph(s) + bullets) and
 * inline-kind blocks (one paragraph, bullets separated by run-level {@code <w:br/>}).
 */
public final class BlockDetector {

    /** Bullet glyph characters a block-detected paragraph segment may start with, including the
     * two private-use-area codepoints old PDF/Word-to-docx converters emit for Wingdings bullets. */
    private static final String GLYPHS = "•●▪◦‣➢❖■□►✓";

    private BlockDetector() {
    }

    public static List<Block> detect(Section section, Predicate<Element> isBullet) {
        List<Element> items = new ArrayList<>();
        for (Element p : section.paragraphs()) {
            if (!DomUtil.allText(p).strip().isEmpty()) {
                items.add(p);
            }
        }

        List<Block> blocks = new ArrayList<>();
        Block cur = null;
        for (Element p : items) {
            if (isBullet.test(p)) {
                if (cur == null || cur.kind == Block.Kind.INLINE) {
                    cur = new Block(Block.Kind.PARAGRAPH);
                    cur.reason = "no_header";
                    blocks.add(cur);
                }
                cur.bulletParas.add(p);
                continue;
            }

            List<String> segs = inlineSegments(p);
            boolean anyGlyphAfterFirst = false;
            for (int i = 1; i < segs.size(); i++) {
                if (startsWithGlyph(segs.get(i))) {
                    anyGlyphAfterFirst = true;
                    break;
                }
            }
            if (segs.size() > 1 && anyGlyphAfterFirst) {
                Block inlineBlock = new Block(Block.Kind.INLINE);
                inlineBlock.inlineHeaderParagraph = p;
                inlineBlock.inlineHeaderSegment = segs.get(0);
                List<List<String>> groups = new ArrayList<>();
                for (int i = 1; i < segs.size(); i++) {
                    String s = segs.get(i);
                    if (startsWithGlyph(s) || groups.isEmpty()) {
                        List<String> g = new ArrayList<>();
                        g.add(s);
                        groups.add(g);
                    } else {
                        groups.get(groups.size() - 1).add(s);
                    }
                }
                inlineBlock.inlineBulletGroups = groups;
                blocks.add(inlineBlock);
                cur = null;
                continue;
            }

            if (cur != null && cur.kind == Block.Kind.PARAGRAPH && !cur.bulletParas.isEmpty()) {
                // A non-bullet paragraph after bullets: a new header, or a stray (split bullet tail).
                if (looksLikeContinuation(p)) {
                    cur.strayParas.add(p);
                    continue;
                }
                cur = null;
            }
            if (cur == null) {
                cur = new Block(Block.Kind.PARAGRAPH);
                blocks.add(cur);
            }
            cur.headerParas.add(p);
        }

        for (Block b : blocks) {
            if (b.kind == Block.Kind.INLINE) {
                continue;
            }
            if (b.headerParas.isEmpty()) {
                b.reason = b.reason != null ? b.reason : "no_header";
            } else if (b.headerParas.size() > 1) {
                b.reason = "multi_paragraph_header";
            } else if (!b.strayParas.isEmpty()) {
                b.reason = "non_bullet_paragraph_in_block";
            } else if (b.bulletParas.isEmpty()) {
                b.reason = "no_bullets";
            }
        }
        return blocks;
    }

    private static boolean looksLikeContinuation(Element p) {
        String t = DomUtil.allText(p).strip();
        if (t.isEmpty()) {
            return false;
        }
        char c = t.charAt(0);
        return Character.isLowerCase(c) || "(,;–-".indexOf(c) >= 0;
    }

    /** Splits a paragraph's text at run-level {@code <w:br/>}: direct children only, matching the reference. */
    private static List<String> inlineSegments(Element p) {
        List<StringBuilder> segs = new ArrayList<>();
        segs.add(new StringBuilder());
        for (Element child : DomUtil.elementChildren(p)) {
            String ln = child.getLocalName();
            if ("r".equals(ln)) {
                Node n = child.getFirstChild();
                while (n != null) {
                    if (n.getNodeType() == Node.ELEMENT_NODE) {
                        Element c = (Element) n;
                        String cln = c.getLocalName();
                        if ("br".equals(cln)) {
                            segs.add(new StringBuilder());
                        } else if ("t".equals(cln)) {
                            segs.get(segs.size() - 1).append(DomUtil.textContent(c));
                        } else if ("tab".equals(cln)) {
                            segs.get(segs.size() - 1).append('\t');
                        }
                    }
                    n = n.getNextSibling();
                }
            } else if ("hyperlink".equals(ln)) {
                segs.get(segs.size() - 1).append(DomUtil.allText(child));
            }
        }
        List<String> out = new ArrayList<>(segs.size());
        for (StringBuilder sb : segs) {
            out.add(sb.toString());
        }
        return out;
    }

    /** Python's {@code s.lstrip()[:1] in GLYPHS}: true (vacuously) for a wholly-blank segment too. */
    private static boolean startsWithGlyph(String s) {
        String stripped = lstrip(s);
        String prefix = stripped.isEmpty() ? "" : stripped.substring(0, 1);
        return GLYPHS.contains(prefix);
    }

    private static String lstrip(String s) {
        int start = 0;
        while (start < s.length() && Character.isWhitespace(s.charAt(start))) {
            start++;
        }
        return s.substring(start);
    }
}
