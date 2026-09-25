package com.tailor.engine.layout;

/** PHASE2_SPEC.md 4.1 step 3: the page rule's resulting target exceeds the 2-page cap. */
public final class TooManyPagesException extends Exception {

    private final int pages;

    public TooManyPagesException(int pages) {
        super("resume is " + pages + " page(s) after normalization (cap is 2)");
        this.pages = pages;
    }

    public int pages() {
        return pages;
    }
}
