package com.tailor.engine.blocks;

import org.w3c.dom.Element;

/**
 * One token of a header template (PHASE3_SPEC.md section 4). Which fields are
 * populated depends on {@link #kind}: TITLE/DETAIL carry {@code rPr} only (the
 * text comes from the new project at render time); SEP/LIT carry literal
 * {@code text} + {@code rPr}; LINK carries the original {@code element}
 * (deep-copied at render time); TAB carries the original run {@code element}
 * (to copy its rPr); DATE carries {@code rPr} + {@code parenthesized}.
 */
public record HeaderToken(Kind kind, String text, Element rPr, Element element, boolean parenthesized) {

    public enum Kind { TITLE, DETAIL, SEP, LIT, LINK, TAB, DATE }

    static HeaderToken title(Element rPr) {
        return new HeaderToken(Kind.TITLE, null, rPr, null, false);
    }

    static HeaderToken detail(Element rPr) {
        return new HeaderToken(Kind.DETAIL, null, rPr, null, false);
    }

    static HeaderToken date(Element rPr, boolean parenthesized) {
        return new HeaderToken(Kind.DATE, null, rPr, null, parenthesized);
    }

    static HeaderToken sep(String text, Element rPr) {
        return new HeaderToken(Kind.SEP, text, rPr, null, false);
    }

    static HeaderToken lit(String text, Element rPr) {
        return new HeaderToken(Kind.LIT, text, rPr, null, false);
    }

    static HeaderToken link(Element linkElement) {
        return new HeaderToken(Kind.LINK, null, null, linkElement, false);
    }

    static HeaderToken tab(Element runElement) {
        return new HeaderToken(Kind.TAB, null, null, runElement, false);
    }
}
