package com.tailor.engine.slots;

import com.tailor.engine.docx.DomUtil;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;

/**
 * Spec section 4.4: reasons a bullet paragraph can't be safely edited.
 * Checked in this order to match golden/*.json.
 */
public final class UnsupportedReasons {

    private UnsupportedReasons() {
    }

    public static List<String> of(Element p) {
        List<String> reasons = new ArrayList<>();

        if (DomUtil.containsDescendant(p, "hyperlink")) {
            reasons.add("hyperlink");
        }

        boolean hasBr = false;
        boolean hasTab = false;
        for (Element r : DomUtil.descendants(p, "r")) {
            if (DomUtil.containsDescendant(r, "br")) {
                hasBr = true;
            }
            if (DomUtil.containsDescendant(r, "tab")) {
                hasTab = true;
            }
        }
        if (hasBr) {
            reasons.add("line_break");
        }
        if (hasTab) {
            reasons.add("tab_in_text");
        }

        if (DomUtil.containsDescendant(p, "fldChar") || DomUtil.containsDescendant(p, "fldSimple")) {
            reasons.add("field");
        }
        if (DomUtil.containsDescendant(p, "ins") || DomUtil.containsDescendant(p, "del")) {
            reasons.add("tracked_change");
        }

        return reasons;
    }
}
