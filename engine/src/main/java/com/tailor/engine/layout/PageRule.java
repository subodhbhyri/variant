package com.tailor.engine.layout;

import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.fonts.FontNormalizer;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.measure.PdfPageCounter;
import com.tailor.engine.render.RenderException;
import com.tailor.engine.render.Renderer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * PHASE2_SPEC.md 4.1 (P6): replaces Phase 1's fixed {@code --target-pages 1}.
 * Target = the resume's own page count after normalization, capped at 2, with
 * a spill rule that tries to pull a barely-overflowing last page back by
 * shrinking, before accepting the extra page.
 */
public final class PageRule {

    private static final int MAX_PAGES = 2;
    private static final int SPILL_LINE_THRESHOLD = 5;
    private static final int MAX_SPILL_SHRINK_STEPS = 2; // 0.5pt, 1.0pt

    /**
     * @param normalizedDocx  where the accepted attempt was last saved (same path passed to {@link #apply})
     * @param pdf             the accepted attempt's render, reused by callers instead of re-rendering
     * @param spillNotPulledBack true if a spill was detected but no shrink step reached pages-1,
     *                           so this result is the unshrunk original (PHASE2_SPEC.md 4.1 step 4)
     */
    public record Result(Path normalizedDocx, Path pdf, int pages, double shrinkPt,
                          int squeezeRemoved, int positionRemoved, int trailingEmptyRemoved,
                          boolean spillNotPulledBack) {
    }

    private final Renderer renderer;

    public PageRule(Renderer renderer) {
        this.renderer = renderer;
    }

    public Result apply(Path src, FontMap fontMap, Path dst, Path workDir)
            throws IOException, RenderException, TooManyPagesException {
        // Step 0: strip trailing empty paragraphs before anything else sees this file, so a
        // blank final page never enters the spill calculation below (PHASE2_SPEC.md 4.1 step 0).
        // Kept out of FontNormalizer.prepare()/normalize(), which Phase 1's own `tailor normalize`
        // and its golden-matched tests also use — this stays scoped to the page rule alone.
        DocxPackage sourcePkg = DocxPackage.open(src);
        int trailingEmptyRemoved = TrailingEmptyParagraphs.stripFromPackage(sourcePkg);
        Path strippedSrc = src;
        if (trailingEmptyRemoved > 0) {
            strippedSrc = workDir.resolve("trailing-stripped-" + System.nanoTime() + ".docx");
            sourcePkg.save(strippedSrc);
        }

        FontNormalizer normalizer = new FontNormalizer(fontMap, renderer);
        FontNormalizer.PreparedBase prepared = normalizer.prepare(strippedSrc);

        DocxPackage attempt0 = normalizer.shrunkCopy(prepared, 0);
        attempt0.save(dst);
        Path pdf0 = renderer.render(dst, workDir);
        int p0 = PdfPageCounter.count(pdf0);

        int target = p0;
        double shrinkUsed = 0.0;
        Path finalPdf = pdf0;
        boolean spillNotPulledBack = false;

        if (p0 > 1 && lastPageLineCount(pdf0, p0) <= SPILL_LINE_THRESHOLD) {
            Integer achievedSteps = null;
            Path achievedPdf = null;
            for (int steps = 1; steps <= MAX_SPILL_SHRINK_STEPS; steps++) {
                DocxPackage attempt = normalizer.shrunkCopy(prepared, steps);
                attempt.save(dst);
                Path pdf = renderer.render(dst, workDir);
                int pages = PdfPageCounter.count(pdf);
                if (pages == p0 - 1) {
                    achievedSteps = steps;
                    achievedPdf = pdf;
                    break;
                }
            }
            if (achievedSteps != null) {
                target = p0 - 1;
                shrinkUsed = achievedSteps * 0.5;
                finalPdf = achievedPdf;
            } else {
                // Neither shrink step pulled the spill back: accept the unshrunk original.
                // A trial shrink above may have left dst on disk shrunk; restore it.
                attempt0.save(dst);
                spillNotPulledBack = true;
            }
        }

        if (target > MAX_PAGES) {
            throw new TooManyPagesException(target);
        }
        return new Result(dst, finalPdf, target, shrinkUsed,
                prepared.squeezeRemoved(), prepared.positionRemoved(), trailingEmptyRemoved, spillNotPulledBack);
    }

    /**
     * Lines on page {@code pageCount - 1} (0-indexed) — the PDF's actual last page, per
     * {@link PdfPageCounter}, not the highest page index that happens to have any extracted
     * text. A wholly blank last page has zero {@link PdfLines.Line} entries at all (extraction
     * only produces lines where there's text), so using "the highest page index seen in the
     * lines" would silently skip it and never see it as a spill (PHASE2_SPEC.md 4.1 step 2).
     */
    private static int lastPageLineCount(Path pdf, int pageCount) throws IOException {
        List<PdfLines.Line> lines = PdfLines.extract(pdf);
        int lastPageIndex = pageCount - 1;
        int count = 0;
        for (PdfLines.Line line : lines) {
            if (line.pageIndex() == lastPageIndex) {
                count++;
            }
        }
        return count;
    }
}
