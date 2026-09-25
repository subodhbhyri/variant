package com.tailor.engine.render;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.measure.PdfPageCounter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Step 1.1 acceptance test (spec section 12): "Done when all 9 produce PDFs
 * in the container." This does not check page counts against golden files —
 * that starts in step 1.2, after font normalization exists. It only checks
 * that rendering itself does not fail.
 */
class LibreOfficeRendererCorpusTest {

    @Test
    void allNineCorpusResumesRenderToPdf() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        List<Path> docs = CorpusPaths.corpusDocx();
        assertTrue(docs.size() == 9, "expected 9 corpus files, found " + docs.size());

        Path outDir = Files.createTempDirectory("render-corpus-test");
        for (Path docx : docs) {
            Path pdf = renderer.render(docx, outDir);
            assertTrue(Files.isRegularFile(pdf), "no PDF produced for " + docx.getFileName());
            int pages = PdfPageCounter.count(pdf);
            assertTrue(pages >= 1, docx.getFileName() + " reported " + pages + " pages");
        }
    }
}
