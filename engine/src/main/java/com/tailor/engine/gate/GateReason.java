package com.tailor.engine.gate;

/** Reason codes from PHASE2_SPEC.md sections 2.1 and 2.1.2. */
public final class GateReason {

    private GateReason() {
    }

    // Static upload gate (section 2.1) — returned by UploadGate.check, in check order.
    public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
    public static final String ENCRYPTED_OR_LEGACY = "ENCRYPTED_OR_LEGACY";
    public static final String NOT_A_DOCX = "NOT_A_DOCX";
    public static final String TOO_MANY_ENTRIES = "TOO_MANY_ENTRIES";
    public static final String DUPLICATE_ENTRY = "DUPLICATE_ENTRY";
    public static final String UNSAFE_PATH = "UNSAFE_PATH";
    public static final String ZIP_BOMB = "ZIP_BOMB";
    public static final String MACROS = "MACROS";
    public static final String EMBEDDED_OBJECT = "EMBEDDED_OBJECT";
    public static final String EXTERNAL_RESOURCE = "EXTERNAL_RESOURCE";
    public static final String UNSAFE_XML = "UNSAFE_XML";
    public static final String TRACKED_CHANGES = "TRACKED_CHANGES";

    // Later pipeline stages (section 4) — not returned by UploadGate itself.
    public static final String TOO_MANY_PAGES = "TOO_MANY_PAGES";
    public static final String TOO_FEW_EDITABLE = "TOO_FEW_EDITABLE";
    public static final String NEEDS_USER = "NEEDS_USER";
}
