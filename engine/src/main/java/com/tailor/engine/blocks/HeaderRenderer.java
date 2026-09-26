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
                        appendWordRuns(headerParagraph, t.rPr(), fields.detail());
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
                ts.get(0).setTextContent(label);
                for (int i = 1; i < ts.size(); i++) {
                    textRuns.get(0).removeChild(ts.get(i));
                }
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

    private static void appendWordRuns(Element paragraph, Element rPr, String text) {
        Matcher m = WORD_TOKEN.matcher(text);
        while (m.find()) {
            paragraph.appendChild(makeRun(paragraph, rPr, m.group()));
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
