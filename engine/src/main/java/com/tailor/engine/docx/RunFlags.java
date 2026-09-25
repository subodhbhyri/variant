package com.tailor.engine.docx;

import org.w3c.dom.Element;

/** Reads OOXML on/off toggle properties like w:b, w:i (spec sections 4.5, 5.2). */
public final class RunFlags {

    private RunFlags() {
    }

    /** True if {@code rPr} has a {@code <w:tag>} present with no val, or a val other than "0"/"false". */
    public static boolean isOn(Element rPr, String tag) {
        if (rPr == null) {
            return false;
        }
        Element el = DomUtil.firstChild(rPr, tag);
        if (el == null) {
            return false;
        }
        String val = DomUtil.attr(el, "val");
        return val == null || !(val.equals("0") || val.equals("false"));
    }
}
