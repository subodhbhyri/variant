package com.tailor.engine.blocks;

import com.tailor.engine.docx.DomUtil;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * PHASE3_SPEC.md section 2 (reference: {@code detect_sections}): finds heading
 * paragraphs by vocabulary, then extends by matching formatting signature, and
 * splits the body into sections at those headings.
 */
public final class SectionDetector {

    private SectionDetector() {
    }

    /** A heading's formatting fingerprint: heading style name, all-caps, all-bold, bordered. */
    private record Signature(String headingStyle, boolean allCaps, boolean allBold, boolean border) {
        boolean any() {
            return !headingStyle.isEmpty() || allCaps || allBold || border;
        }
    }

    public static List<Section> detect(Document doc) {
        List<Element> paras = bodyParagraphs(doc);

        List<Element> vocabHits = new ArrayList<>();
        for (Element p : paras) {
            if (isShort(p) && Vocab.roleOf(text(p)).isPresent()) {
                vocabHits.add(p);
            }
        }

        Set<Signature> sigs = new LinkedHashSet<>();
        for (Element p : vocabHits) {
            Signature sig = signatureOf(p);
            if (sig.any()) {
                sigs.add(sig);
            }
        }

        List<Element> heads = new ArrayList<>();
        for (Element p : paras) {
            if (isShort(p) && sigs.contains(signatureOf(p))) {
                heads.add(p);
            }
        }
        // Never treat the first 3 paragraphs (the name/contact block) as a heading unless vocabulary.
        List<Element> filteredHeads = new ArrayList<>();
        for (Element p : heads) {
            if (Vocab.roleOf(text(p)).isPresent() || paras.indexOf(p) > 2) {
                filteredHeads.add(p);
            }
        }

        List<Integer> idx = new ArrayList<>();
        for (Element p : filteredHeads) {
            idx.add(paras.indexOf(p));
        }

        List<Section> sections = new ArrayList<>();
        for (int k = 0; k < idx.size(); k++) {
            int i = idx.get(k);
            int end = k + 1 < idx.size() ? idx.get(k + 1) : paras.size();
            String headingText = text(paras.get(i)).strip();
            String role = Vocab.roleOf(headingText).orElse("other");
            sections.add(new Section(headingText, role, List.copyOf(paras.subList(i + 1, end))));
        }
        return sections;
    }

    /** Body paragraphs outside tables and text boxes, in document order. */
    private static List<Element> bodyParagraphs(Document doc) {
        Element body = DomUtil.firstChild(doc.getDocumentElement(), "body");
        List<Element> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        for (Element p : DomUtil.descendants(body, "p")) {
            if (!hasBlockingAncestor(p, body)) {
                out.add(p);
            }
        }
        return out;
    }

    private static boolean hasBlockingAncestor(Element p, Element body) {
        Node n = p.getParentNode();
        while (n != null && n != body) {
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                String ln = ((Element) n).getLocalName();
                if ("tbl".equals(ln) || "txbxContent".equals(ln)) {
                    return true;
                }
            }
            n = n.getParentNode();
        }
        return false;
    }

    private static boolean isShort(Element p) {
        String t = text(p).strip();
        int len = t.length();
        if (len == 0 || len > 40) {
            return false;
        }
        return t.split("\\s+").length <= 5;
    }

    private static Signature signatureOf(Element p) {
        Element pPr = DomUtil.firstChild(p, "pPr");
        Element pStyle = pPr != null ? DomUtil.firstChild(pPr, "pStyle") : null;
        String style = pStyle != null ? DomUtil.attr(pStyle, "val") : null;
        String headingStyle = style != null && style.toLowerCase(Locale.ROOT).startsWith("heading") ? style : "";

        List<Element> runs = new ArrayList<>();
        for (Element r : DomUtil.descendants(p, "r")) {
            if (!DomUtil.allText(r).strip().isEmpty()) {
                runs.add(r);
            }
        }
        boolean allBold = !runs.isEmpty();
        for (Element r : runs) {
            Element rPr = DomUtil.firstChild(r, "rPr");
            if (rPr == null || DomUtil.firstChild(rPr, "b") == null) {
                allBold = false;
                break;
            }
        }

        String fullText = text(p);
        boolean anyLetter = false;
        boolean allCaps = true;
        for (int i = 0; i < fullText.length(); i++) {
            char c = fullText.charAt(i);
            if (Character.isLetter(c)) {
                anyLetter = true;
                if (!Character.isUpperCase(c)) {
                    allCaps = false;
                }
            }
        }
        allCaps = anyLetter && allCaps;

        boolean border = pPr != null && DomUtil.firstChild(pPr, "pBdr") != null;

        return new Signature(headingStyle, allCaps, allBold, border);
    }

    private static String text(Element p) {
        return DomUtil.allText(p);
    }
}
