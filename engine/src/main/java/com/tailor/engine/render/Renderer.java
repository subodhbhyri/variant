package com.tailor.engine.render;

import java.nio.file.Path;

/**
 * Converts a .docx to PDF.
 *
 * This is an interface (spec section 9) so Phase 2 can substitute a sandboxed
 * worker pool without changing any caller. Phase 1 has exactly one
 * implementation: {@link LibreOfficeRenderer}.
 */
public interface Renderer {

    /**
     * Renders {@code docxPath} to a PDF and returns the path to that PDF.
     *
     * @param docxPath path to a .docx file that already exists
     * @param outDir   directory to write the PDF into (must exist)
     * @return the path to the produced PDF
     * @throws RenderException if the conversion fails, times out, or the
     *                          expected output file does not appear
     */
    Path render(Path docxPath, Path outDir) throws RenderException;

    /** The renderer's version string, recorded with every calibration result. */
    String version();
}
