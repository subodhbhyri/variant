package com.tailor.engine.gate;

import java.util.Map;

/**
 * Outcome of {@link UploadGate#check}. On acceptance, carries every part's
 * bytes exactly as read from the zip's central directory (PHASE2_SPEC.md
 * 2.1.1), so callers such as DocxPackage never have to re-read the upload.
 */
public record GateResult(boolean accepted, String reason, Map<String, byte[]> parts) {

    public static GateResult accept(Map<String, byte[]> parts) {
        return new GateResult(true, null, Map.copyOf(parts));
    }

    public static GateResult reject(String reason) {
        return new GateResult(false, reason, null);
    }
}
