package com.tailor.engine.docx;

import com.tailor.engine.gate.GateReason;
import com.tailor.engine.gate.GateResult;
import com.tailor.engine.gate.UploadGate;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A .docx file as a zip of parts, read and written at the byte level.
 *
 * Deviates from PHASE1_SPEC.md section 5's literal "use POI's OPCPackage":
 * OPCPackage rewrites relationships/content-types on save in ways not
 * verified against the corpus in this environment. The Python reference
 * (reference/normalize.py — verified against all 9 resumes) worked directly
 * on zip entry bytes, copying every part unchanged except the ones being
 * edited. This class does the same: get/set one part's bytes, leave
 * everything else byte-identical. The rule being protected — no XWPF
 * object-model mutation (no {@code XWPFRun.setText}, etc.) — still holds.
 * Callers only see byte[], so this can be swapped for OPCPackage later
 * without changing anything above it.
 *
 * <p>PHASE2_SPEC.md 2.1.1: {@link #open} goes through {@link UploadGate}'s
 * limits — the same central-directory read LibreOffice's own zip reader
 * would see — so a file that never reaches the upload endpoint (a local
 * corpus file, an intermediate pipeline file) is held to the same safety
 * limits as an actual upload.
 */
public final class DocxPackage {

    private final LinkedHashMap<String, byte[]> parts = new LinkedHashMap<>();

    private DocxPackage() {
    }

    public static DocxPackage open(Path docxPath) throws IOException {
        // Check the file's size before reading it in, so an oversized local file is
        // rejected without first pulling the whole thing into memory (PHASE2_SPEC.md 2.1.1).
        if (Files.size(docxPath) > UploadGate.MAX_UPLOAD_BYTES) {
            throw new IOException("rejected (" + GateReason.FILE_TOO_LARGE + "): " + docxPath);
        }
        byte[] bytes = Files.readAllBytes(docxPath);
        GateResult result = UploadGate.check(bytes);
        if (!result.accepted()) {
            throw new IOException("rejected (" + result.reason() + "): " + docxPath);
        }
        return fromGatedUpload(result);
    }

    /** Builds a package from an already-accepted {@link GateResult}, without re-reading the zip. */
    public static DocxPackage fromGatedUpload(GateResult result) {
        if (!result.accepted()) {
            throw new IllegalArgumentException("cannot open a rejected upload: " + result.reason());
        }
        DocxPackage pkg = new DocxPackage();
        pkg.parts.putAll(result.parts());
        return pkg;
    }

    /** A shallow copy: safe because parts are only ever replaced wholesale, never mutated in place. */
    public DocxPackage copy() {
        DocxPackage c = new DocxPackage();
        c.parts.putAll(this.parts);
        return c;
    }

    public byte[] readPart(String partName) {
        return parts.get(partName);
    }

    public boolean hasPart(String partName) {
        return parts.containsKey(partName);
    }

    public void writePart(String partName, byte[] content) {
        if (!parts.containsKey(partName)) {
            throw new IllegalArgumentException("part not found: " + partName);
        }
        parts.put(partName, content);
    }

    /**
     * Adds a part that doesn't already exist (PHASE3_SPEC.md 4: a header's first added link
     * relationship, when the document had none — {@code word/_rels/document.xml.rels} is
     * absent iff the document never had any relationship at all). Every extension this can
     * plausibly apply to (.rels itself, chiefly) already has a {@code Default} entry in
     * {@code [Content_Types].xml} in any package that passed the upload gate, so no content-type
     * bookkeeping is needed here.
     */
    public void addPart(String partName, byte[] content) {
        if (parts.containsKey(partName)) {
            throw new IllegalArgumentException("part already exists: " + partName);
        }
        parts.put(partName, content);
    }

    public List<String> partNamesStartingWith(String prefix) {
        return parts.keySet().stream().filter(n -> n.startsWith(prefix)).toList();
    }

    public void save(Path outPath) throws IOException {
        try (ZipOutputStream zout = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(outPath)))) {
            for (var e : parts.entrySet()) {
                zout.putNextEntry(new ZipEntry(e.getKey()));
                zout.write(e.getValue());
                zout.closeEntry();
            }
        }
    }
}
