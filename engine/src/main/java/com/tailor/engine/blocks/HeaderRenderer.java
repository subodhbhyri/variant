package com.tailor.engine.blocks;

import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.XmlBuild;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;

/**
 * PHASE3_SPEC.md section 4 (reference: {@code render_header}): rewrites a
 * header paragraph from its template with a new project's fields.
 */
public final class HeaderRenderer {

    /** Same token pattern as Phase 1's Substituter: one run per word, and per whitespace run
     * between words — measured to give 0.0pt drift where a single long run doesn't. */
    private static final Pattern WORD_TOKEN = Pattern.compile("\\S+|\\s+");
    private static final Pattern SPACE_RUN = Pattern.compile("\\s+");
    private static final String NO_BREAK_SPACE = " ";
    private static final String R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    public interface LinkRelWriter {
        String addHyperlink(String url);
    }

    public record NewLink(String label, String url) {
    }

    public record NewFields(String title, String detail, List<NewLink> links, String date) {
    }

    private HeaderRenderer() {
    }

    public static void render(Element headerParagraph, List<HeaderToken> template, NewFields fields,
            LinkRelWriter linkRelWriter) {
        List<NewLink> links = fields.links() != null ? fields.links() : List.of();
        for (NewLink link : links) {
            LinkValidator.validate(link.url());
        }

        XmlBuild.removeAllExceptPPr(headerParagraph);

        Deque<NewLink> remainingLinks = new ArrayDeque<>(links);
        boolean hasDetail = fields.detail() != null && !fields.detail().isEmpty();

        for (int k = 0; k < template.size(); k++) {
            HeaderToken t = template.get(k);
            switch (t.kind()) {
                case TITLE -> appendWordRuns(headerParagraph, t.rPr(), fields.title());
                case DETAIL -> {
                    if (hasDetail) {
                        appendItemRuns(headerParagraph, t.rPr(), fields.detail());
                    }
                }
                case SEP -> {
                    HeaderToken.Kind next = k + 1 < template.size() ? template.get(k + 1).kind() : null;
                    if (next == HeaderToken.Kind.DETAIL && !hasDetail) {
                        continue;
                    }
                    if (next == HeaderToken.Kind.LINK && remainingLinks.isEmpty()) {
                        continue;
                    }
                    headerParagraph.appendChild(makeRun(headerParagraph, t.rPr(), t.text()));
                }
                case LIT -> headerParagraph.appendChild(makeRun(headerParagraph, t.rPr(), t.text()));
                case LINK -> {
                    if (remainingLinks.isEmpty()) {
                        continue;
                    }
                    headerParagraph.appendChild(
                            renderLink(t.element(), remainingLinks.poll(), linkRelWriter));
                }
                case TAB -> headerParagraph.appendChild(makeTabRun(headerParagraph, t.element()));
                case DATE -> {
                    String d = fields.date() == null ? "" : fields.date();
                    if (!d.isEmpty() && t.parenthesized() && !d.startsWith("(")) {
                        d = "(" + d + ")";
                    }
                    if (!d.isEmpty()) {
                        appendWordRuns(headerParagraph, t.rPr(), d);
                    }
                }
                default -> throw new IllegalStateException("unexpected header token kind: " + t.kind());
            }
        }
    }

    /** Package-visible: {@link InlineRenderer} reuses this for an inline block's header-segment link. */
    static Element renderLink(Element original, NewLink link, LinkRelWriter linkRelWriter) {
        Element copy = (Element) original.cloneNode(true);
        copy.removeAttributeNS(W_NS, "anchor");

        String newRid = linkRelWriter.addHyperlink(link.url());
        Attr idAttr = copy.getAttributeNodeNS(R_NS, "id");
        if (idAttr != null) {
            idAttr.setValue(newRid);
        } else {
            copy.setAttributeNS(R_NS, "r:id", newRid);
        }

        List<Element> textRuns = new ArrayList<>();
        for (Element r : DomUtil.descendants(copy, "r")) {
            if (!DomUtil.allText(r).strip().isEmpty()) {
                textRuns.add(r);
            }
        }
        boolean urlish = DatePattern.URLISH.matcher(DomUtil.allText(original).strip()).matches();
        String label = urlish ? link.url() : link.label();

        if (!textRuns.isEmpty()) {
            List<Element> ts = new ArrayList<>();
            for (Element c : DomUtil.elementChildren(textRuns.get(0))) {
                if ("t".equals(c.getLocalName())) {
                    ts.add(c);
                }
            }
            if (!ts.isEmpty()) {
                // A link label is one item: no-break spaces, and w:noBreakHyphen for its hyphens.
                Element first = textRuns.get(0);
                for (Element c : ts) {
                    first.removeChild(c);
                }
                appendTextWithNoBreakHyphens(copy, first, noBreakSpaces(label));
            }
            for (int i = 1; i < textRuns.size(); i++) {
                Element extra = textRuns.get(i);
                extra.getParentNode().removeChild(extra);
            }
        }
        return copy;
    }

    private static Element makeTabRun(Element paragraph, Element originalTabRun) {
        Element run = XmlBuild.createChild(paragraph, "r");
        Element rPr = originalTabRun != null ? DomUtil.firstChild(originalTabRun, "rPr") : null;
        if (rPr != null) {
            run.appendChild(rPr.cloneNode(true));
        }
        run.appendChild(XmlBuild.createChild(run, "tab"));
        return run;
    }

    /** Plain word runs: one run per word and per whitespace run (Substituter's token pattern). */
    private static void appendWordRuns(Element paragraph, Element rPr, String text) {
        Matcher m = WORD_TOKEN.matcher(text);
        while (m.find()) {
            paragraph.appendChild(makeRun(paragraph, rPr, m.group()));
        }
    }

    /**
     * A DETAIL list (or a link label, below) — written so it can't wrap inside an item
     * (PHASE3_SPEC.md section 4): its own spaces are no-break (U+00A0), and so are its hyphens (w:noBreakHyphen).
     * The only breakable spaces left are the ones after a ", " or beside a "|" separator.
     */
    private static void appendItemRuns(Element paragraph, Element rPr, String text) {
        for (Element run : itemRuns(paragraph, rPr, text)) {
            paragraph.appendChild(run);
        }
    }

    /**
     * The runs for one header item, unattached: the same runs {@link #render} writes for a DETAIL
     * list or a link label.
     */
    private static List<Element> itemRuns(Element template, Element rPr, String text) {
        List<Element> runs = new ArrayList<>();
        Matcher m = WORD_TOKEN.matcher(noBreakSpaces(text));
        while (m.find()) {
            String token = m.group();
            if (Character.isWhitespace(token.charAt(0))) {
                runs.add(makeRun(template, rPr, token));
            } else {
                Element run = XmlBuild.createChild(template, "r");
                if (rPr != null) {
                    run.appendChild(rPr.cloneNode(true));
                }
                appendTextWithNoBreakHyphens(template, run, token);
                runs.add(run);
            }
        }
        return runs;
    }

    /** {@code text} with every space that isn't after a comma or beside a "|" made no-break. */
    static String noBreakSpaces(String text) {
        Matcher m = SPACE_RUN.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            boolean afterComma = m.start() > 0 && text.charAt(m.start() - 1) == ',';
            boolean besidePipe = (m.start() > 0 && text.charAt(m.start() - 1) == '|')
                    || (m.end() < text.length() && text.charAt(m.end()) == '|');
            out.append(afterComma || besidePipe ? m.group() : NO_BREAK_SPACE);
            last = m.end();
        }
        out.append(text.substring(last));
        return out.toString();
    }

    /** Adds {@code text} to {@code run} as w:t elements, each hyphen as a w:noBreakHyphen. */
    private static void appendTextWithNoBreakHyphens(Element template, Element run, String text) {
        String[] parts = text.split("-", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                run.appendChild(XmlBuild.createChild(template, "noBreakHyphen"));
            }
            if (!parts[i].isEmpty() || parts.length == 1) {
                Element t = XmlBuild.createChild(template, "t");
                XmlBuild.setXmlSpacePreserve(t);
                t.setTextContent(parts[i]);
                run.appendChild(t);
            }
        }
    }

    private static Element makeRun(Element paragraph, Element rPr, String text) {
        Element run = XmlBuild.createChild(paragraph, "r");
        if (rPr != null) {
            run.appendChild(rPr.cloneNode(true));
        }
        Element t = XmlBuild.createChild(paragraph, "t");
        XmlBuild.setXmlSpacePreserve(t);
        t.setTextContent(text);
        run.appendChild(t);
        return run;
    }
}
