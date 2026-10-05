package com.tailor.engine.match;

import com.tailor.engine.render.RenderException;
import com.tailor.engine.render.Renderer;
import java.nio.file.Path;

/**
 * PHASE5_SPEC.md section 5.1 (step B1): the onboarded document is the baseline every verification
 * compares against, and its render is the same file every time, so it is rendered once per batch
 * and reused. Only renders of that one path are reused; everything else goes to the delegate. When
 * the onboarding already left that render's PDF beside the document, it is used as the first render,
 * so the baseline is never rendered again at all.
 */
public final class BaselineMemoRenderer implements Renderer {

    private final Renderer delegate;
    private final Path baseline;
    private Path baselinePdf;

    /** {@code baseline} must already be absolute and normalized. */
    public BaselineMemoRenderer(Renderer delegate, Path baseline) {
        this(delegate, baseline, null);
    }

    /** As above, with {@code storedPdf} (the baseline's own render, from onboarding) when there is one. */
    public BaselineMemoRenderer(Renderer delegate, Path baseline, Path storedPdf) {
        this.delegate = delegate;
        this.baseline = baseline;
        this.baselinePdf = storedPdf;
    }

    @Override
    public Path render(Path docxPath, Path outDir) throws RenderException {
        if (!baseline.equals(docxPath.toAbsolutePath().normalize())) {
            return delegate.render(docxPath, outDir);
        }
        if (baselinePdf == null) {
            baselinePdf = delegate.render(docxPath, outDir);
        }
        return baselinePdf;
    }

    @Override
    public String version() {
        return delegate.version();
    }
}
