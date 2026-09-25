package com.tailor.engine.verify;

/** What happened to a slot in an assembled output. Slots absent from the edits map are unchanged. */
public enum SlotEdit {
    /** New text (or its own text re-written) that keeps the slot's line count. */
    SUBSTITUTED,
    /** New text that needed fewer lines, padded with blank lines to hold the height (spec 5.4). */
    PADDED,
    /** Deleted by the user; held as blank lines of the same height (spec 5.3). */
    BLANKED
}