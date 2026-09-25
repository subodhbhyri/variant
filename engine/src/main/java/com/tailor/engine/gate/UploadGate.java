package com.tailor.engine.gate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PHASE2_SPEC.md section 2.1 (revision 2, after the Opus security review):
 * validates an upload before anything else ever touches it, and never
 * renders. The actual checks live in {@link GateCheck}, a fresh instance per
 * call, so this entry point holds no mutable state of its own.
 *
 * <p>Reads via {@link java.util.zip.ZipFile} on a temp copy of the upload —
 * the zip's central directory, the same view LibreOffice's own zip reader
 * uses — rather than {@link java.util.zip.ZipInputStream}'s local-header
 * stream (2.1.1).
 */
public final class UploadGate {

    /** PHASE2_SPEC.md P5. Also used by {@code DocxPackage.open}'s pre-read size check. */
    public static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024;

    private static final byte[] OLE_SIGNATURE =
            {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private UploadGate() {
    }

    public static GateResult check(byte[] upload) throws IOException {
        // 1. Upload size
        if (upload.length > MAX_UPLOAD_BYTES) {
            return GateResult.reject(GateReason.FILE_TOO_LARGE);
        }
        // 2. OLE signature: encrypted .docx or legacy .doc
        if (startsWithOle(upload)) {
            return GateResult.reject(GateReason.ENCRYPTED_OR_LEGACY);
        }

        // From here on the gate never throws (2.1.1) — GateCheck.run() catches everything
        // except genuine infrastructure failures (e.g. an unwritable temp directory), which
        // are allowed to propagate as IOException.
        Path temp = Files.createTempFile("upload-gate-", ".docx");
        try {
            Files.write(temp, upload);
            return new GateCheck(temp).run();
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static boolean startsWithOle(byte[] upload) {
        if (upload.length < OLE_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < OLE_SIGNATURE.length; i++) {
            if (upload[i] != OLE_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }
}
