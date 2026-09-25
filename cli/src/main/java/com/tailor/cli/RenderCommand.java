package com.tailor.cli;

import com.tailor.engine.measure.PdfPageCounter;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.RenderException;
import com.tailor.engine.render.RenderResult;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code tailor render <in.docx> [outDir]} — converts to PDF and prints the
 * page count. Step 1.1's acceptance test: this must succeed for all 9 corpus
 * resumes (spec section 12, step 1.1).
 */
@Command(name = "render", description = "Render a .docx to PDF and print its page count.")
public final class RenderCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the .docx file.")
    private Path docxPath;

    @Parameters(index = "1", arity = "0..1", description = "Output directory (default: current directory).")
    private Path outDir;

    @Override
    public Integer call() {
        Path resolvedOutDir = outDir != null ? outDir : Path.of(".");
        try {
            Files.createDirectories(resolvedOutDir);
        } catch (Exception e) {
            System.err.println("could not create output directory: " + resolvedOutDir + " (" + e.getMessage() + ")");
            return 1;
        }

        Renderer renderer = new LibreOfficeRenderer();
        try {
            Path pdfPath = renderer.render(docxPath, resolvedOutDir);
            int pages = PdfPageCounter.count(pdfPath);
            RenderResult result = new RenderResult(pdfPath, pages, renderer.version());
            System.out.printf(
                    "pdf=%s pages=%d renderer=%s%n",
                    result.pdfPath(), result.pageCount(), result.rendererVersion());
            return 0;
        } catch (RenderException e) {
            System.err.println("render failed: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            System.err.println("unexpected error: " + e);
            return 1;
        }
    }
}
