package com.tailor.engine.docx;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
 */
public final class DocxPackage {

    private final LinkedHashMap<String, byte[]> parts = new LinkedHashMap<>();

    private DocxPackage() {
    }

    public static DocxPackage open(Path docxPath) throws IOException {
        DocxPackage pkg = new DocxPackage();
        try (ZipInputStream zin = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(docxPath)))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                pkg.parts.put(entry.getName(), zin.readAllBytes());
            }
        }
        if (!pkg.parts.containsKey("word/document.xml")) {
            throw new IOException("not a .docx (no word/document.xml): " + docxPath);
        }
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
