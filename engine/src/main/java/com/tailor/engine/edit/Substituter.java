package com.tailor.engine.edit;

import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.RunFlags;
import com.tailor.engine.docx.XmlBuild;
import com.tailor.engine.slots.BulletText;
import com.tailor.engine.slots.EmphasisSpan;
import com.tailor.engine.slots.UnsupportedReasons;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Element;

/**
 * Rewrites a bullet's text while keeping its marker and formatting (spec
 * section 5.2, revised after the step 1.5 Verifier findings).
 *
 * <p>Formatting is taken per <em>emphasis class</em>, not from a single base
 * run: plain words copy the full run properties of the bullet's longest plain
 * run, bold words copy those of its longest bold run, and so on. That keeps
 * class-specific choices such as bold terms set in a different font. If the
 * new text needs a class the original bullet never had, the plain template
 * is used with bold/italic switched on.
 *
 * <p>Text is written as one run per word and per whitespace token. Measured:
 * LibreOffice lays out one long run slightly taller than the same text split
 * per word (1.9pt on a Rakesh bullet with identical properties), while
 * per-word runs gave 0.0pt drift on all 9 corpus resumes.
 *
 * <p>Never touches {@code w:numPr}, so the bullet dot survives.
 */
public final class Substituter {

    private static final Pattern TOKEN = Pattern.compile("\\S+|\\s+");

    private Substituter() {
    }

    private record StyleClass(boolean bold, boolean italic) {
    }

    private record Segment(String text, StyleClass style) {
    }

    public static void substitute(Element paragraph, BulletText bulletText) {
        List<String> reasons = UnsupportedReasons.of(paragraph);
        if (!reasons.isEmpty()) {
            throw new UnsupportedBulletException(reasons);
        }

        Map<StyleClass, Element> templates = classTemplates(paragraph);
        XmlBuild.removeAllExceptPPr(paragraph);

        for (Segment seg : segments(bulletText.text(), bulletText.emphasis())) {
            Element rPr = rPrFor(seg.style(), templates, paragraph);
            Matcher m = TOKEN.matcher(seg.text());
            while (m.find()) {
                Element run = XmlBuild.createChild(paragraph, "r");
                if (rPr != null) {
                    run.appendChild(rPr.cloneNode(true));
                }
                Element t = XmlBuild.createChild(paragraph, "t");
                XmlBuild.setXmlSpacePreserve(t);
                t.setTextContent(m.group());
                run.appendChild(t);
                paragraph.appendChild(run);
            }
        }
    }

    /**
     * Longest run per emphasis class. The map value may be null: that class
     * exists, but its template run has no rPr (so new runs get none either).
     */
    private static Map<StyleClass, Element> classTemplates(Element paragraph) {
        Map<StyleClass, Element> templates = new HashMap<>();
        Map<StyleClass, Integer> bestLength = new HashMap<>();
        for (Element r : DomUtil.descendants(paragraph, "r")) {
            String text = DomUtil.allText(r);
            if (text.isEmpty()) {
                continue;
            }
            Element rPr = DomUtil.firstChild(r, "rPr");
            StyleClass cls = new StyleClass(RunFlags.isOn(rPr, "b"), RunFlags.isOn(rPr, "i"));
            if (text.length() > bestLength.getOrDefault(cls, -1)) {
                bestLength.put(cls, text.length());
                templates.put(cls, rPr == null ? null : (Element) rPr.cloneNode(true));
            }
        }
        return templates;
    }

    /** rPr to use for a segment of this class; a fresh clone is made per run by the caller. */
    private static Element rPrFor(StyleClass cls, Map<StyleClass, Element> templates, Element paragraph) {
        if (templates.containsKey(cls)) {
            return templates.get(cls);
        }
        StyleClass plain = new StyleClass(false, false);
        Element base = templates.containsKey(plain)
                ? templates.get(plain)
                : templates.values().stream().filter(e -> e != null).findFirst().orElse(null);

        Element rPr = base != null
                ? (Element) base.cloneNode(true)
                : XmlBuild.createChild(paragraph, "rPr");
        for (String tag : List.of("b", "bCs", "i", "iCs")) {
            Element el = DomUtil.firstChild(rPr, tag);
            if (el != null) {
                rPr.removeChild(el);
            }
        }
        if (cls.bold()) {
            XmlBuild.insertInRPrOrder(rPr, "b");
        }
        if (cls.italic()) {
            XmlBuild.insertInRPrOrder(rPr, "i");
        }
        templates.put(cls, rPr); // reuse for later segments of the same class
        return rPr;
    }

    private static List<Segment> segments(String text, List<EmphasisSpan> spans) {
        List<Segment> out = new ArrayList<>();
        List<EmphasisSpan> sorted = spans.stream()
                .sorted(Comparator.comparingInt(EmphasisSpan::start))
                .toList();
        StyleClass plain = new StyleClass(false, false);

        int pos = 0;
        for (EmphasisSpan span : sorted) {
            if (span.start() > pos) {
                out.add(new Segment(text.substring(pos, span.start()), plain));
            }
            out.add(new Segment(text.substring(span.start(), span.end()),
                    new StyleClass(span.bold(), span.italic())));
            pos = span.end();
        }
        if (pos < text.length()) {
            out.add(new Segment(text.substring(pos), plain));
        }
        return out;
    }
}