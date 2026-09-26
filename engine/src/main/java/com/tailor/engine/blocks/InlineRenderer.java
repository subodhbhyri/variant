package com.tailor.engine.blocks;

import com.tailor.engine.docx.XmlBuild;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Element;

/**
 * PHASE3_SPEC.md section 6: rewrites an inline block's paragraph from its
 * {@link InlineTemplate.Model}, replacing selected bullets' text (by group
 * index) and reproducing every other bullet — and the header segment —
 * unchanged, node for node.
 *
 * <p>Rule 1: each separating {@code <w:br/>} is appended inside the run that
 * ends the text before it (or, when that text ends in a link, a fresh run
 * copying the link's own run formatting) — never a standalone run with no
 * {@code rPr}, which takes the document's default size and grows the line
 * (measured 3.9pt on Subodh's resume; with this rule, 0.0pt).
 *
 * <p>Rule 2: a rewritten bullet's formatting comes from its first real text
 * run, not the glyph run or a break run ({@link InlineTemplate#firstRealTextRpr}).
 *
 * <p>Rule 3: a rewritten bullet is {@code "• "} + text, one run per word,
 * dropping any manual breaks inside it (its continuation segments).
 */
public final class InlineRenderer {

    private static final Pattern WORD_TOKEN = Pattern.compile("\\S+|\\s+");
    private static final String BULLET_GLYPH = "• ";

    /** A new title (and, if the header segment has a link and the project supplies one, a new
     * link) for an inline block's header segment. {@code link} null means: drop any link the
     * header segment had (the project has none to show). */
    public record HeaderRewrite(String title, HeaderRenderer.NewLink link) {
    }

    private InlineRenderer() {
    }

    /** @param bulletRewrites group index -&gt; new text (no leading glyph); a group not present is kept unchanged. */
    public static void render(Element paragraph, InlineTemplate.Model model, Map<Integer, String> bulletRewrites) {
        render(paragraph, model, null, bulletRewrites, null, false);
    }

    /** As above, also rewriting the header segment. {@code headerRewrite} null keeps it unchanged. */
    public static void render(Element paragraph, InlineTemplate.Model model, HeaderRewrite headerRewrite,
            Map<Integer, String> bulletRewrites, HeaderRenderer.LinkRelWriter linkRelWriter) {
        render(paragraph, model, headerRewrite, bulletRewrites, linkRelWriter, false);
    }

    /**
     * @param injectRuleViolation test-only (package-visible): places separator breaks in fresh,
     *                            unformatted runs (rule 1) and uses the wrong formatting for a
     *                            rewritten bullet's own text (rule 2) instead of following either
     *                            rule, to prove P3-T6's methodology can detect the regressions it
     *                            guards against.
     */
    static void render(Element paragraph, InlineTemplate.Model model, Map<Integer, String> bulletRewrites,
            boolean injectRuleViolation) {
        render(paragraph, model, null, bulletRewrites, null, injectRuleViolation);
    }

    private static void render(Element paragraph, InlineTemplate.Model model, HeaderRewrite headerRewrite,
            Map<Integer, String> bulletRewrites, HeaderRenderer.LinkRelWriter linkRelWriter,
            boolean injectRuleViolation) {
        if (headerRewrite != null && headerRewrite.link() != null) {
            LinkValidator.validate(headerRewrite.link().url());
        }

        XmlBuild.removeAllExceptPPr(paragraph);
        Element[] lastNode = {null};

        if (headerRewrite == null) {
            for (Element node : model.headerNodes()) {
                Element clone = (Element) node.cloneNode(true);
                paragraph.appendChild(clone);
                lastNode[0] = clone;
            }
        } else {
            Element rPr = InlineTemplate.firstRealTextRpr(model.headerNodes());
            Matcher m = WORD_TOKEN.matcher(headerRewrite.title());
            while (m.find()) {
                Element run = makeWordRun(paragraph, rPr, m.group());
                paragraph.appendChild(run);
                lastNode[0] = run;
            }
            Element originalLink = null;
            for (Element node : model.headerNodes()) {
                if ("hyperlink".equals(node.getLocalName())) {
                    originalLink = node;
                    break;
                }
            }
            if (headerRewrite.link() != null && originalLink != null) {
                Element rendered = HeaderRenderer.renderLink(originalLink, headerRewrite.link(), linkRelWriter);
                paragraph.appendChild(rendered);
                lastNode[0] = rendered;
            }
        }

        for (int g = 0; g < model.groups().size(); g++) {
            appendBreak(paragraph, lastNode, injectRuleViolation);

            InlineTemplate.BulletGroup group = model.groups().get(g);
            String rewrite = bulletRewrites.get(g);
            if (rewrite == null) {
                for (int s = 0; s < group.segments().size(); s++) {
                    if (s > 0) {
                        appendBreak(paragraph, lastNode, injectRuleViolation);
                    }
                    for (Element original : group.segments().get(s)) {
                        Element clone = (Element) original.cloneNode(true);
                        paragraph.appendChild(clone);
                        lastNode[0] = clone;
                    }
                }
            } else {
                // Rule 2 (package-visible test violation): use the wrong, tiny formatting instead
                // of the bullet's own first real text run — this environment's PDF layout doesn't
                // measurably react to an empty break run's own font metrics (tried and confirmed:
                // neither an absent rPr nor a grossly oversized one on the break run itself shifts
                // any glyph), but wrong formatting on the bullet's *visible* text reliably does,
                // since it changes where the reconstructed text wraps.
                Element rPr = injectRuleViolation ? wrongTinyRpr(paragraph) : InlineTemplate.firstRealTextRpr(group);
                Matcher m = WORD_TOKEN.matcher(BULLET_GLYPH + rewrite);
                while (m.find()) {
                    Element run = makeWordRun(paragraph, rPr, m.group());
                    paragraph.appendChild(run);
                    lastNode[0] = run;
                }
            }
        }
    }

    private static Element wrongTinyRpr(Element paragraph) {
        Element rPr = XmlBuild.createChild(paragraph, "rPr");
        Element sz = XmlBuild.createChild(rPr, "sz");
        XmlBuild.setAttr(sz, "val", "8"); // 4pt — deliberately wrong, not this bullet's real size
        rPr.appendChild(sz);
        return rPr;
    }

    private static void appendBreak(Element paragraph, Element[] lastNode, boolean unformatted) {
        if (!unformatted && lastNode[0] != null && "r".equals(lastNode[0].getLocalName())) {
            lastNode[0].appendChild(XmlBuild.createChild(lastNode[0], "br"));
            return;
        }
        Element rPr = null;
        if (!unformatted && lastNode[0] != null && "hyperlink".equals(lastNode[0].getLocalName())) {
            rPr = InlineTemplate.rprOfLastInnerRun(lastNode[0]);
        }
        Element run = XmlBuild.createChild(paragraph, "r");
        if (rPr != null) {
            run.appendChild(rPr.cloneNode(true));
        }
        run.appendChild(XmlBuild.createChild(run, "br"));
        paragraph.appendChild(run);
        lastNode[0] = run;
    }

    private static Element makeWordRun(Element paragraph, Element rPr, String text) {
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
