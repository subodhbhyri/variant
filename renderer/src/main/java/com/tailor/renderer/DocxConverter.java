package com.tailor.renderer;

/** Turns .docx bytes into PDF bytes. The HTTP layer depends on this, not on LibreOffice. */
public interface DocxConverter extends AutoCloseable {

    /** @throws RenderFailure {@code RENDER_TIMEOUT} past the per-render limit, otherwise {@code RENDER_FAILED} */
    byte[] toPdf(byte[] docx) throws RenderFailure;

    /** LibreOffice's version string (e.g. {@code 24.2.7.2}); recorded with every onboarding. */
    String version();

    @Override
    void close();
}
