package com.tailor.engine.blocks;

import com.tailor.engine.docx.DomUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * PHASE3_SPEC.md section 4 (reference: {@code header_template}): flattens a
 * header paragraph into an ordered list of typed tokens, each keeping its
 * original formatting, for later rendering with new project fields. Only
 * called on headers that already passed {@link HeaderParser} (no line
 * breaks or other reasons to reject).
 */
public final class HeaderTemplate {

    private record CharTok(char ch, Element rPr) {
    }

    private HeaderTemplate() {
    }

    public static List<HeaderToken> build(Element headerParagraph) {
        List<Object[]> toks = charsWithRpr(headerParagraph); // each: {"char", CharTok} | {"link", Element} | {"tab", Element}

        List<HeaderToken> out = new ArrayList<>();
        List<CharTok> buf = new ArrayList<>();
        boolean[] afterTab = {false};

        for (Object[] t : toks) {
            if ("char".equals(t[0])) {
                buf.add((CharTok) t[1]);
                continue;
            }
            flush(out, buf, afterTab[0]);
            buf.clear();
            if ("link".equals(t[0])) {
                out.add(HeaderToken.link((Element) t[1]));
            } else {
                out.add(HeaderToken.tab((Element) t[1]));
                afterTab[0] = true;
            }
        }
        flush(out, buf, afterTab[0]);
        return out;
    }

    private static void flush(List<HeaderToken> out, List<CharTok> buf, boolean afterTab) {
        if (buf.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (CharTok c : buf) {
            sb.append(c.ch());
        }
        String s = sb.toString();

        if (afterTab) {
            String stripped = s.strip();
            Matcher m = DatePattern.DATE.matcher(stripped);
            if (m.find() && !stripped.isEmpty()) {
                int i = s.indexOf(stripped);
                int j = i + stripped.length();
                if (i > 0) {
                    out.add(HeaderToken.lit(s.substring(0, i), buf.get(0).rPr()));
                }
                out.add(HeaderToken.date(buf.get(i).rPr(), stripped.startsWith("(")));
                if (j < s.length()) {
                    out.add(HeaderToken.lit(s.substring(j), buf.get(j).rPr()));
                }
            } else if (!s.isEmpty()) {
                out.add(HeaderToken.lit(s, buf.get(0).rPr()));
            }
            return;
        }

        int pos = 0;
        String[] pieces = s.split(" \\| ", -1);
        for (int k = 0; k < pieces.length; k++) {
            String piece = pieces[k];
            if (k > 0) {
                out.add(HeaderToken.sep(" | ", buf.get(pos).rPr()));
                pos += 3;
            }
            String core = Vocab.stripChars(piece, " [(");
            String lead = core.isEmpty() ? piece : piece.substring(0, piece.indexOf(core));
            String trail = piece.substring(lead.length() + core.length());
            if (!lead.isEmpty()) {
                out.add(HeaderToken.lit(lead, buf.get(pos).rPr()));
                pos += lead.length();
            }
            if (!core.isEmpty()) {
                boolean hasTitle = out.stream().anyMatch(t -> t.kind() == HeaderToken.Kind.TITLE);
                Element rPr = buf.get(pos).rPr();
                out.add(hasTitle ? HeaderToken.detail(rPr) : HeaderToken.title(rPr));
                pos += core.length();
            }
            if (!trail.isEmpty()) {
                Element rPr = pos < buf.size() ? buf.get(pos).rPr() : buf.get(buf.size() - 1).rPr();
                out.add(HeaderToken.lit(trail, rPr));
                pos += trail.length();
            }
        }
    }

    /** Flattens direct children into ("char", CharTok) / ("link", Element) / ("tab", Element) markers. */
    private static List<Object[]> charsWithRpr(Element p) {
        List<Object[]> toks = new ArrayList<>();
        for (Element el : DomUtil.elementChildren(p)) {
            String ln = el.getLocalName();
            if ("hyperlink".equals(ln)) {
                toks.add(new Object[] {"link", el});
            } else if ("r".equals(ln)) {
                Element rPr = DomUtil.firstChild(el, "rPr");
                Node n = el.getFirstChild();
                while (n != null) {
                    if (n.getNodeType() == Node.ELEMENT_NODE) {
                        Element c = (Element) n;
                        String cln = c.getLocalName();
                        if ("tab".equals(cln) || "ptab".equals(cln)) {
                            toks.add(new Object[] {"tab", el});
                        } else if ("t".equals(cln)) {
                            String text = DomUtil.textContent(c);
                            for (int i = 0; i < text.length(); i++) {
                                toks.add(new Object[] {"char", new CharTok(text.charAt(i), rPr)});
                            }
                        }
                    }
                    n = n.getNextSibling();
                }
            }
        }
        return toks;
    }
}
