package com.tailor.engine.gate;

import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.SafeXml;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * PHASE2_SPEC.md section 2.1: validates an upload before anything else ever
 * touches it, and never renders. Checks run in the exact order of the
 * spec's table; the first failure wins and its reason is returned. On
 * acceptance, every part's bytes come back too (2.1.1), so {@code DocxPackage}
 * never has to re-read the zip.
 *
 * <p>Reads via {@link ZipFile} on a temp copy of the upload — the zip's
 * central directory, the same view LibreOffice's own zip reader uses —
 * rather than {@link java.util.zip.ZipInputStream}'s local-header stream.
 */
public final class UploadGate {

    private static final long MAX_FILE = 2L * 1024 * 1024;
    private static final int MAX_ENTRIES = 200;
    private static final long MAX_TOTAL = 20L * 1024 * 1024;
    private static final long MAX_ENTRY = 10L * 1024 * 1024;
    private static final long MAX_RATIO = 200;

    private static final byte[] OLE_SIGNATURE =
            {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private static final String HYPERLINK_TYPE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink";

    private static final Pattern TRACKED_CHANGE =
            Pattern.compile("<w:(ins|del|moveFrom|moveTo)[ >]");

    private UploadGate() {
    }

    public static GateResult check(byte[] upload) throws IOException {
        // 1. Upload size
        if (upload.length > MAX_FILE) {
            return GateResult.reject(GateReason.FILE_TOO_LARGE);
        }
        // 2. OLE signature: encrypted .docx or legacy .doc
        if (startsWithOle(upload)) {
            return GateResult.reject(GateReason.ENCRYPTED_OR_LEGACY);
        }

        Path temp = Files.createTempFile("upload-gate-", ".docx");
        try {
            Files.write(temp, upload);
            return checkZip(temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static GateResult checkZip(Path temp) throws IOException {
        try (ZipFile zf = new ZipFile(temp.toFile())) {
            List<? extends ZipEntry> entries = Collections.list(zf.entries());
            return checkEntries(zf, entries);
        } catch (GateRejected r) {
            return GateResult.reject(r.reason);
        } catch (ZipException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            // Some JDKs throw at open time for a duplicate central-directory name
            // rather than exposing both entries (2.1.1: map that to DUPLICATE_ENTRY,
            // not a crash); anything else here means it isn't a readable zip at all.
            return GateResult.reject(
                    msg.contains("duplicate") ? GateReason.DUPLICATE_ENTRY : GateReason.NOT_A_DOCX);
        } catch (IOException e) {
            // 3. Not a readable zip
            return GateResult.reject(GateReason.NOT_A_DOCX);
        }
    }

    private static GateResult checkEntries(ZipFile zf, List<? extends ZipEntry> entries) throws IOException {
        // 4. Entry count
        if (entries.size() > MAX_ENTRIES) {
            throw new GateRejected(GateReason.TOO_MANY_ENTRIES);
        }

        List<String> names = entries.stream().map(ZipEntry::getName).toList();

        // 5. Duplicate entry names (also the fallback path if this JDK's ZipFile
        // tolerates duplicates in the central directory rather than throwing).
        if (new HashSet<>(names).size() != names.size()) {
            throw new GateRejected(GateReason.DUPLICATE_ENTRY);
        }

        // 6. Unsafe entry names
        for (String name : names) {
            if (!isSafePath(name)) {
                throw new GateRejected(GateReason.UNSAFE_PATH);
            }
        }

        // 7. Zip bomb, by the central directory's declared sizes
        long declaredTotal = 0;
        for (ZipEntry e : entries) {
            long size = Math.max(e.getSize(), 0);
            long compressed = Math.max(e.getCompressedSize(), 0);
            declaredTotal += size;
            if (size > MAX_ENTRY || size > MAX_RATIO * Math.max(compressed, 1)) {
                throw new GateRejected(GateReason.ZIP_BOMB);
            }
        }
        if (declaredTotal > MAX_TOTAL) {
            throw new GateRejected(GateReason.ZIP_BOMB);
        }

        // Declared sizes can lie (2.1.1): re-enforce the same limits on the
        // bytes actually inflated. This also gives us every part's content,
        // which every later check and the accepted GateResult both need.
        Map<String, byte[]> parts = readAllBounded(zf, entries);

        Set<String> nameSet = Set.copyOf(names);
        // 8. Required parts present
        if (!nameSet.contains("[Content_Types].xml") || !nameSet.contains("word/document.xml")) {
            throw new GateRejected(GateReason.NOT_A_DOCX);
        }

        // 9. Macros
        boolean hasVbaProject =
                names.stream().anyMatch(n -> n.toLowerCase(Locale.ROOT).endsWith("vbaproject.bin"));
        String contentTypes = new String(parts.get("[Content_Types].xml"), StandardCharsets.UTF_8);
        if (hasVbaProject || contentTypes.contains("macroEnabled")) {
            throw new GateRejected(GateReason.MACROS);
        }

        // 10. Embedded objects / ActiveX
        boolean hasEmbedded =
                names.stream().anyMatch(n -> n.startsWith("word/embeddings/") || n.startsWith("word/activeX/"));
        if (hasEmbedded) {
            throw new GateRejected(GateReason.EMBEDDED_OBJECT);
        }

        // 11. External resources (not hyperlinks) in relationship parts.
        // A relationship part that itself fails to parse is left for check 12
        // (UNSAFE_XML) below, rather than parsed here with anything less hardened.
        for (String name : names) {
            if (!name.endsWith(".rels")) {
                continue;
            }
            Document relsDoc;
            try {
                relsDoc = SafeXml.parse(parts.get(name));
            } catch (IOException malformed) {
                continue;
            }
            for (Element rel : DomUtil.descendants(relsDoc.getDocumentElement(), "Relationship")) {
                String targetMode = DomUtil.attr(rel, "TargetMode");
                String type = DomUtil.attr(rel, "Type");
                if ("External".equals(targetMode) && !HYPERLINK_TYPE.equals(type)) {
                    throw new GateRejected(GateReason.EXTERNAL_RESOURCE);
                }
            }
        }

        // 12. Every .xml / .rels part must parse safely: no DOCTYPE, no entities, depth <= 100
        for (String name : names) {
            if (name.endsWith(".xml") || name.endsWith(".rels")) {
                try {
                    SafeXml.parse(parts.get(name));
                } catch (IOException malformed) {
                    throw new GateRejected(GateReason.UNSAFE_XML);
                }
            }
        }

        // 13. Tracked changes
        String documentText = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        if (TRACKED_CHANGE.matcher(documentText).find()) {
            throw new GateRejected(GateReason.TRACKED_CHANGES);
        }

        return GateResult.accept(parts);
    }

    private static Map<String, byte[]> readAllBounded(ZipFile zf, List<? extends ZipEntry> entries)
            throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long[] totalRead = {0};
        for (ZipEntry entry : entries) {
            out.put(entry.getName(), readEntryBounded(zf, entry, totalRead));
        }
        return out;
    }

    private static byte[] readEntryBounded(ZipFile zf, ZipEntry entry, long[] totalRead) throws IOException {
        try (InputStream in = zf.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long entryRead = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                entryRead += n;
                totalRead[0] += n;
                if (entryRead > MAX_ENTRY || totalRead[0] > MAX_TOTAL) {
                    throw new GateRejected(GateReason.ZIP_BOMB);
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
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

    /** PHASE2_SPEC.md check 6: empty, absolute, backslash, drive letter, or a "." / ".." segment. */
    private static boolean isSafePath(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.startsWith("\\") || name.contains("\\")) {
            return false;
        }
        if (name.length() >= 2 && name.charAt(1) == ':' && Character.isLetter(name.charAt(0))) {
            return false;
        }
        for (String segment : name.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** Internal control-flow exception: short-circuits checkEntries with a reason. */
    private static final class GateRejected extends IOException {
        final String reason;

        GateRejected(String reason) {
            super(reason);
            this.reason = reason;
        }
    }
}
