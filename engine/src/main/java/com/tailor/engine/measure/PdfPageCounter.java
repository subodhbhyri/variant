package com.tailor.engine.measure;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Page count only. Per-glyph line extraction ({@code PdfLines}) and slot
 * anchoring ({@code AnchorMeasurer}) are added in step 1.5 (spec section 6);
 * this class exists early because {@code tailor render} needs a page count in
 * step 1.1.
 */
public final class PdfPageCounter {

    private PdfPageCounter() {
    }

    public static int count(Path pdfPath) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            return doc.getNumberOfPages();
        }
    }
}
