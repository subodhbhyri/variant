package com.tailor.engine.match;

import com.tailor.engine.render.RenderException;
import com.tailor.engine.render.Renderer;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/** Delegates every render and counts them, so {@link PipelineTiming} can report renders per stage. */
public final class CountingRenderer implements Renderer {

    private final Renderer delegate;
    private final AtomicLong renders = new AtomicLong();

    public CountingRenderer(Renderer delegate) {
        this.delegate = delegate;
    }

    @Override
    public Path render(Path docxPath, Path outDir) throws RenderException {
        renders.incrementAndGet();
        return delegate.render(docxPath, outDir);
    }

    @Override
    public String version() {
        return delegate.version();
    }

    public long count() {
        return renders.get();
    }
}
