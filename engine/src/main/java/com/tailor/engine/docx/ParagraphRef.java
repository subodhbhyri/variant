package com.tailor.engine.docx;

import java.util.List;
import org.w3c.dom.Element;

/**
 * One {@code <w:p>} found while walking the document body (spec section 4.1).
 *
 * @param element  the paragraph DOM element
 * @param locator  element-child indices from {@code <w:body>} down to this
 *                 paragraph, counting every element child regardless of tag
 * @param inTable  true if any ancestor is {@code <w:tbl>}
 */
public record ParagraphRef(Element element, List<Integer> locator, boolean inTable) {
}
