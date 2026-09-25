package com.tailor.engine.slots;

import java.util.List;
import org.w3c.dom.Element;

/**
 * A bullet found by {@link BulletDetector}, in document order.
 *
 * @param index               position among detected slots, 0-based
 * @param locator             element-child indices from body to this paragraph
 * @param element             the paragraph DOM element (not serialized to JSON)
 * @param text                concatenated text (spec 4.3)
 * @param emphasis            bold/italic spans over {@code text} (spec 4.5)
 * @param supported           false if the paragraph contains a hyperlink, run-level
 *                            break/tab, field, or tracked change (spec 4.4)
 * @param unsupportedReasons  which of those apply; empty if supported
 */
public record Slot(
        int index,
        List<Integer> locator,
        Element element,
        String text,
        List<EmphasisSpan> emphasis,
        boolean supported,
        List<String> unsupportedReasons) {

    public int chars() {
        return text.length();
    }
}
