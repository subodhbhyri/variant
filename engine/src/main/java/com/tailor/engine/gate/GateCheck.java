package com.tailor.engine.gate;

import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.SafeXml;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * One run of PHASE2_SPEC.md section 2.1's checks (revision 2, after the Opus
 * security review) against a single upload, already written to a temp file.
 * All per-call mutable state — the bounded-read part bytes, the parsed-XML
 * cache, the content-type maps — lives on this instance, so {@link
 * UploadGate#check} itself stays a stateless static entry point and nothing
 * is shared across concurrent uploads.
 */
final class GateCheck {

    private static final int MAX_ENTRIES = 200;
    private static final long MAX_TOTAL = 20L * 1024 * 1024;
    private static final long MAX_ENTRY = 10L * 1024 * 1024;
    private static final long MAX_RATIO = 200;

    private static final String CT_NS = "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    // The macro-enabled variants count as "a Word document" here so check 9 can reject
    // them with the clearer MACROS reason, rather than check 8 rejecting them as NOT_A_DOCX.
    private static final Set<String> MAIN_TYPES = Set.of(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml",
            "application/vnd.ms-word.document.macroenabled.main+xml",
            "application/vnd.ms-word.template.macroenabledtemplate.main+xml");

    private static final Set<String> REL_MACRO = Set.of("vbaproject", "wordvbadata");
    private static final Set<String> REL_EMBED =
            Set.of("oleobject", "package", "control", "activexcontrol", "activexcontrolbinary", "afchunk");
    private static final Set<String> LINKED_FIELDS =
            Set.of("INCLUDETEXT", "INCLUDEPICTURE", "LINK", "DDE", "DDEAUTO", "IMPORT");
    private static final Set<String> TRACKED_CHANGE_TAGS = Set.of("ins", "del", "moveFrom", "moveTo");

    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.\\-]*:");

    private final Path tempFile;
    private final Map<String, byte[]> parts = new LinkedHashMap<>();
    private final Map<String, Document> parsedXml = new HashMap<>();
    private final Set<String> failedXml = new HashSet<>();
    private final Map<String, String> defaultsByExtension = new HashMap<>();
    private final Map<String, String> overridesByPartName = new HashMap<>();

    GateCheck(Path tempFile) {
        this.tempFile = tempFile;
    }

    GateResult run() {
        try (ZipFile zf = new ZipFile(tempFile.toFile())) {
            return doRun(zf);
        } catch (GateRejected r) {
            return GateResult.reject(r.reason);
        } catch (ZipException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            // Some JDKs throw at open time for a duplicate central-directory name rather
            // than exposing both entries (2.1.1): map that to DUPLICATE_ENTRY, not a crash.
            return GateResult.reject(
                    msg.contains("duplicate") ? GateReason.DUPLICATE_ENTRY : GateReason.NOT_A_DOCX);
        } catch (IOException e) {
            // 3. Not a readable zip
            return GateResult.reject(GateReason.NOT_A_DOCX);
        } catch (RuntimeException e) {
            // 2.1.1: the gate never throws. Anything unexpected while reading — a malformed
            // entry name throwing IllegalArgumentException, etc. — is "not a docx".
            return GateResult.reject(GateReason.NOT_A_DOCX);
        }
    }

    private GateResult doRun(ZipFile zf) throws IOException {
        List<? extends ZipEntry> allEntries = Collections.list(zf.entries());
        // 4. Entry count (central directory, directories included)
        if (allEntries.size() > MAX_ENTRIES) {
            throw new GateRejected(GateReason.TOO_MANY_ENTRIES);
        }

        List<ZipEntry> fileEntries = new ArrayList<>();
        for (ZipEntry e : allEntries) {
            if (!e.getName().endsWith("/")) {
                fileEntries.add(e);
            }
        }
        List<String> names = fileEntries.stream().map(ZipEntry::getName).toList();

        // 5. Duplicate entry names, case-insensitive (OPC part names are case-insensitive)
        List<String> lowerNames = names.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList();
        if (new HashSet<>(lowerNames).size() != lowerNames.size()) {
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
        for (ZipEntry e : fileEntries) {
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

        // Declared sizes can lie (2.1.1): re-enforce the same limits on the bytes actually
        // inflated. This also gives us every part's content for every check below.
        readAllBounded(zf, fileEntries);

        Set<String> nameSet = Set.copyOf(names);
        // 8. A well-formed Word package
        if (!nameSet.contains("[Content_Types].xml")
                || !nameSet.contains("word/document.xml")
                || !nameSet.contains("_rels/.rels")) {
            throw new GateRejected(GateReason.NOT_A_DOCX);
        }
        checkWellFormedPackage(names);

        // Relationship parts, parsed once and reused for checks 9-11. A .rels part that
        // fails to parse here is left for check 12 (UNSAFE_XML) below, not reported now.
        Map<String, Document> relRoots = new LinkedHashMap<>();
        for (String name : names) {
            if (name.endsWith(".rels")) {
                Document doc = tryParseXml(name);
                if (doc != null) {
                    relRoots.put(name, doc);
                }
            }
        }
        List<String> relTypes = new ArrayList<>();
        for (Document root : relRoots.values()) {
            for (Element rel : relationshipElements(root)) {
                relTypes.add(typeLastSegment(DomUtil.attr(rel, "Type")));
            }
        }

        // 9. Macros: by entry name, content type, and relationship type
        String allContentTypes = (String.join(" ", defaultsByExtension.values())
                + " " + String.join(" ", overridesByPartName.values())).toLowerCase(Locale.ROOT);
        boolean hasVbaProject =
                names.stream().anyMatch(n -> n.toLowerCase(Locale.ROOT).endsWith("vbaproject.bin"));
        if (hasVbaProject
                || allContentTypes.contains("macroenabled")
                || allContentTypes.contains("vbaproject")
                || containsAny(relTypes, REL_MACRO)) {
            throw new GateRejected(GateReason.MACROS);
        }

        // 10. Embedded objects: by path and relationship type
        boolean hasEmbeddedPath = names.stream()
                .anyMatch(n -> n.startsWith("word/embeddings/") || n.startsWith("word/activeX/"));
        if (hasEmbeddedPath || containsAny(relTypes, REL_EMBED)) {
            throw new GateRejected(GateReason.EMBEDDED_OBJECT);
        }

        // 11. External resources: external mode, or a URI/UNC target, on anything but a hyperlink
        for (Document root : relRoots.values()) {
            for (Element rel : relationshipElements(root)) {
                if ("hyperlink".equals(typeLastSegment(DomUtil.attr(rel, "Type")))) {
                    continue;
                }
                String target = DomUtil.attr(rel, "Target");
                String targetMode = DomUtil.attr(rel, "TargetMode");
                boolean external = "External".equals(targetMode)
                        || (target != null
                                && (SCHEME.matcher(target).find()
                                        || target.startsWith("//") || target.startsWith("\\\\")));
                if (external) {
                    throw new GateRejected(GateReason.EXTERNAL_RESOURCE);
                }
            }
        }

        // 12. Every part whose content type ends in xml, and every .rels part, parses safely.
        // Collect word/ story parts (not .rels) along the way, to reuse their trees for 13a/13b.
        Map<String, Document> stories = new LinkedHashMap<>();
        for (String name : names) {
            String contentType = contentTypeOf(name);
            boolean isXmlPart = name.endsWith(".rels") || (contentType != null && contentType.endsWith("xml"));
            if (!isXmlPart) {
                continue;
            }
            Document doc = tryParseXml(name);
            if (doc == null) {
                throw new GateRejected(GateReason.UNSAFE_XML);
            }
            if (name.startsWith("word/") && !name.endsWith(".rels")) {
                stories.put(name, doc);
            }
        }

        // 13a. Linked fields, namespace-aware
        for (Document story : stories.values()) {
            if (hasLinkedField(story)) {
                throw new GateRejected(GateReason.EXTERNAL_RESOURCE);
            }
        }
        // 13b. Tracked changes, namespace-aware — never a literal "w:" prefix match
        for (Document story : stories.values()) {
            if (hasTrackedChange(story)) {
                throw new GateRejected(GateReason.TRACKED_CHANGES);
            }
        }

        return GateResult.accept(parts);
    }

    /** Check 8: required parts, every part typed, exactly one officeDocument relationship. */
    private void checkWellFormedPackage(List<String> names) throws IOException {
        Document contentTypesDoc = tryParseXml("[Content_Types].xml");
        if (contentTypesDoc == null) {
            throw new GateRejected(GateReason.UNSAFE_XML);
        }
        for (Element el : directChildrenNS(contentTypesDoc.getDocumentElement(), CT_NS, "Default")) {
            String ext = DomUtil.attr(el, "Extension");
            if (ext != null) {
                defaultsByExtension.put(ext.toLowerCase(Locale.ROOT), nullToEmpty(DomUtil.attr(el, "ContentType")));
            }
        }
        for (Element el : directChildrenNS(contentTypesDoc.getDocumentElement(), CT_NS, "Override")) {
            String partName = DomUtil.attr(el, "PartName");
            if (partName != null) {
                String key = partName.startsWith("/") ? partName.substring(1) : partName;
                overridesByPartName.put(key.toLowerCase(Locale.ROOT), nullToEmpty(DomUtil.attr(el, "ContentType")));
            }
        }

        for (String name : names) {
            if (!name.equals("[Content_Types].xml") && contentTypeOf(name) == null) {
                throw new GateRejected(GateReason.NOT_A_DOCX);
            }
        }

        Document pkgRels = tryParseXml("_rels/.rels");
        if (pkgRels == null) {
            throw new GateRejected(GateReason.UNSAFE_XML);
        }
        List<String> officeDocumentTargets = new ArrayList<>();
        for (Element rel : relationshipElements(pkgRels)) {
            String type = DomUtil.attr(rel, "Type");
            if (type != null && type.endsWith("/officeDocument")) {
                String target = DomUtil.attr(rel, "Target");
                officeDocumentTargets.add(
                        target != null && target.startsWith("/") ? target.substring(1) : target);
            }
        }
        String mainType = contentTypeOf("word/document.xml");
        boolean mainTypeOk = mainType != null && MAIN_TYPES.contains(mainType.toLowerCase(Locale.ROOT));
        if (officeDocumentTargets.size() != 1
                || !"word/document.xml".equals(officeDocumentTargets.get(0))
                || !mainTypeOk) {
            throw new GateRejected(GateReason.NOT_A_DOCX);
        }
    }

    /** Override (by exact part name) wins, else Default (by extension); null if neither applies. */
    private String contentTypeOf(String name) {
        String override = overridesByPartName.get(name.toLowerCase(Locale.ROOT));
        if (override != null && !override.isEmpty()) {
            return override;
        }
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot + 1) : "";
        return defaultsByExtension.get(extension.toLowerCase(Locale.ROOT));
    }

    private static List<Element> relationshipElements(Document root) {
        Element rootEl = root.getDocumentElement();
        if (rootEl == null) {
            return List.of();
        }
        List<Element> out = new ArrayList<>();
        for (Element child : DomUtil.elementChildren(rootEl)) {
            if ("Relationship".equals(child.getLocalName())) {
                out.add(child);
            }
        }
        return out;
    }

    private static String typeLastSegment(String type) {
        if (type == null) {
            return "";
        }
        int idx = type.lastIndexOf('/');
        return (idx >= 0 ? type.substring(idx + 1) : type).toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(List<String> haystack, Set<String> needles) {
        for (String h : haystack) {
            if (needles.contains(h)) {
                return true;
            }
        }
        return false;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Direct-child elements matching both namespace URI and local name — real content-types parts are flat. */
    private static List<Element> directChildrenNS(Element parent, String ns, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) n;
                if (localName.equals(el.getLocalName()) && ns.equals(el.getNamespaceURI())) {
                    out.add(el);
                }
            }
        }
        return out;
    }

    /** 13a: w:instrText text, and w:fldSimple/@w:instr, in the WordprocessingML namespace. */
    private static boolean hasLinkedField(Document story) {
        NodeList instrText = story.getElementsByTagNameNS(W_NS, "instrText");
        for (int i = 0; i < instrText.getLength(); i++) {
            if (isLinkedFieldInstruction(DomUtil.textContent((Element) instrText.item(i)))) {
                return true;
            }
        }
        NodeList fldSimple = story.getElementsByTagNameNS(W_NS, "fldSimple");
        for (int i = 0; i < fldSimple.getLength(); i++) {
            Element el = (Element) fldSimple.item(i);
            if (isLinkedFieldInstruction(el.getAttributeNS(W_NS, "instr"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLinkedFieldInstruction(String instr) {
        if (instr == null) {
            return false;
        }
        String trimmed = instr.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String first = trimmed.split("\\s+", 2)[0];
        return LINKED_FIELDS.contains(first.toUpperCase(Locale.ROOT));
    }

    /** 13b: ins/del/moveFrom/moveTo in the WordprocessingML namespace, whatever prefix is used. */
    private static boolean hasTrackedChange(Document story) {
        for (String tag : TRACKED_CHANGE_TAGS) {
            if (story.getElementsByTagNameNS(W_NS, tag).getLength() > 0) {
                return true;
            }
        }
        return false;
    }

    /** Parses (and caches) a part's XML once; a prior failure is remembered, not retried. */
    private Document tryParseXml(String name) {
        Document cached = parsedXml.get(name);
        if (cached != null) {
            return cached;
        }
        if (failedXml.contains(name)) {
            return null;
        }
        byte[] bytes = parts.get(name);
        if (bytes == null) {
            failedXml.add(name);
            return null;
        }
        try {
            Document doc = SafeXml.parse(bytes);
            parsedXml.put(name, doc);
            return doc;
        } catch (IOException malformed) {
            failedXml.add(name);
            return null;
        }
    }

    private void readAllBounded(ZipFile zf, List<ZipEntry> entries) throws IOException {
        long[] totalRead = {0};
        for (ZipEntry entry : entries) {
            parts.put(entry.getName(), readEntryBounded(zf, entry, totalRead));
        }
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

    /** Internal control-flow exception: short-circuits doRun with a reason. */
    private static final class GateRejected extends IOException {
        final String reason;

        GateRejected(String reason) {
            super(reason);
            this.reason = reason;
        }
    }
}
