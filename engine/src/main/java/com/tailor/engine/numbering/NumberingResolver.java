package com.tailor.engine.numbering;

import com.tailor.engine.docx.DomUtil;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.w3c.dom.Element;

/**
 * Resolves a paragraph's effective numbering format (spec section 4.2):
 * direct {@code w:numPr}, or the first {@code numPr} found walking up the
 * {@code w:pStyle -> w:basedOn} chain; then {@code numId -> abstractNumId ->
 * w:lvl/w:numFmt}, honoring a {@code w:lvlOverride} where one exists for
 * that level.
 */
public final class NumberingResolver {

    private final Map<String, String> numToAbstract = new HashMap<>();
    private final Map<String, Map<String, String>> abstractLevelFmt = new HashMap<>();
    private final Map<String, Map<String, String>> numLevelOverrideFmt = new HashMap<>();
    private final Map<String, String> styleNumId = new HashMap<>();
    private final Map<String, String> styleBasedOn = new HashMap<>();

    public NumberingResolver(Element numberingRoot, Element stylesRoot) {
        if (numberingRoot != null) {
            parseNumbering(numberingRoot);
        }
        if (stylesRoot != null) {
            parseStyles(stylesRoot);
        }
    }

    private void parseNumbering(Element root) {
        for (Element abstractNum : DomUtil.elementChildren(root)) {
            if (!"abstractNum".equals(abstractNum.getLocalName())) {
                continue;
            }
            String abstractId = DomUtil.attr(abstractNum, "abstractNumId");
            Map<String, String> levels = new HashMap<>();
            for (Element lvl : DomUtil.elementChildren(abstractNum)) {
                if (!"lvl".equals(lvl.getLocalName())) {
                    continue;
                }
                String ilvl = DomUtil.attr(lvl, "ilvl");
                Element numFmt = DomUtil.firstChild(lvl, "numFmt");
                if (numFmt != null) {
                    levels.put(ilvl, DomUtil.attr(numFmt, "val"));
                }
            }
            abstractLevelFmt.put(abstractId, levels);
        }

        for (Element num : DomUtil.elementChildren(root)) {
            if (!"num".equals(num.getLocalName())) {
                continue;
            }
            String numId = DomUtil.attr(num, "numId");
            Element abstractRef = DomUtil.firstChild(num, "abstractNumId");
            if (abstractRef != null) {
                numToAbstract.put(numId, DomUtil.attr(abstractRef, "val"));
            }
            Map<String, String> overrides = new HashMap<>();
            for (Element lvlOverride : DomUtil.elementChildren(num)) {
                if (!"lvlOverride".equals(lvlOverride.getLocalName())) {
                    continue;
                }
                String ilvl = DomUtil.attr(lvlOverride, "ilvl");
                Element lvl = DomUtil.firstChild(lvlOverride, "lvl");
                if (lvl == null) {
                    continue; // e.g. startOverride only, no format change
                }
                Element numFmt = DomUtil.firstChild(lvl, "numFmt");
                if (numFmt != null) {
                    overrides.put(ilvl, DomUtil.attr(numFmt, "val"));
                }
            }
            if (!overrides.isEmpty()) {
                numLevelOverrideFmt.put(numId, overrides);
            }
        }
    }

    private void parseStyles(Element root) {
        for (Element style : DomUtil.elementChildren(root)) {
            if (!"style".equals(style.getLocalName())) {
                continue;
            }
            String styleId = DomUtil.attr(style, "styleId");
            if (styleId == null) {
                continue;
            }
            Element basedOn = DomUtil.firstChild(style, "basedOn");
            if (basedOn != null) {
                styleBasedOn.put(styleId, DomUtil.attr(basedOn, "val"));
            }
            Element pPr = DomUtil.firstChild(style, "pPr");
            if (pPr != null) {
                Element numPr = DomUtil.firstChild(pPr, "numPr");
                if (numPr != null) {
                    Element numIdEl = DomUtil.firstChild(numPr, "numId");
                    if (numIdEl != null) {
                        styleNumId.put(styleId, DomUtil.attr(numIdEl, "val"));
                    }
                }
            }
        }
    }

    /** Effective {@code numFmt} for this paragraph (e.g. "bullet", "decimal"), or empty if not numbered. */
    public Optional<String> resolveNumFmt(Element paragraph) {
        Element pPr = DomUtil.firstChild(paragraph, "pPr");
        Element numPr = pPr != null ? DomUtil.firstChild(pPr, "numPr") : null;

        String numId;
        String ilvl;
        if (numPr != null) {
            Element numIdEl = DomUtil.firstChild(numPr, "numId");
            numId = numIdEl != null ? DomUtil.attr(numIdEl, "val") : null;
            Element ilvlEl = DomUtil.firstChild(numPr, "ilvl");
            ilvl = ilvlEl != null ? DomUtil.attr(ilvlEl, "val") : "0";
        } else {
            Element pStyleEl = pPr != null ? DomUtil.firstChild(pPr, "pStyle") : null;
            String styleId = pStyleEl != null ? DomUtil.attr(pStyleEl, "val") : null;
            numId = styleId != null ? resolveStyleNumId(styleId) : null;
            ilvl = "0";
        }

        if (numId == null || "0".equals(numId)) {
            return Optional.empty();
        }

        Map<String, String> overrides = numLevelOverrideFmt.get(numId);
        if (overrides != null && overrides.containsKey(ilvl)) {
            return Optional.ofNullable(overrides.get(ilvl));
        }
        String abstractId = numToAbstract.get(numId);
        if (abstractId == null) {
            return Optional.empty();
        }
        Map<String, String> levels = abstractLevelFmt.get(abstractId);
        return levels == null ? Optional.empty() : Optional.ofNullable(levels.get(ilvl));
    }

    /** Walks the w:pStyle -> w:basedOn chain (max 10 hops) for the first style that carries a numId. */
    private String resolveStyleNumId(String styleId) {
        String id = styleId;
        for (int hop = 0; id != null && hop < 10; hop++) {
            String numId = styleNumId.get(id);
            if (numId != null) {
                return numId;
            }
            id = styleBasedOn.get(id);
        }
        return null;
    }
}
