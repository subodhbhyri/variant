package com.tailor.engine.render;

import java.nio.file.Path;

/**
 * A completed render: where the PDF landed and its page count.
 *
 * Font audit (spec section 3.4) is added in step 1.2 once {@code FontMap}
 * exists; it is not part of step 1.1.
 */
public record RenderResult(Path pdfPath, int pageCount, String rendererVersion) {
}
