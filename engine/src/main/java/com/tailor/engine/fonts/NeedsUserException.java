package com.tailor.engine.fonts;

import java.nio.file.Path;

/**
 * Thrown when font mapping, letter-squeeze removal, and the 0/0.5/1.0pt
 * shrink steps (spec section 3.3) still don't reach the target page count.
 * Phase 2's onboarding preview turns this into a question for the user.
 */
public final class NeedsUserException extends Exception {

    public final Path source;
    public final int targetPages;

    public NeedsUserException(Path source, int targetPages) {
        super("normalization could not reach " + targetPages + " page(s) for " + source
                + " after shrink steps 0/0.5/1.0pt");
        this.source = source;
        this.targetPages = targetPages;
    }
}
