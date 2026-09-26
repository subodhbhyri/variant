package com.tailor.engine.blocks;

import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.docx.XmlSerialize;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * PHASE3_SPEC.md section 4: writes a fresh relationship for a swapped-in
 * header link into {@code word/_rels/document.xml.rels}, never touching or
 * reusing an existing one. One instance per package edit (a swap may
 * rewrite several headers, each possibly adding more than one link); call
 * {@link #flush()} once after every header in the edit has been rendered.
 */
public final class RelationshipWriter {

    private static final String REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
    static final String HYPERLINK_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink";
    private static final String PART_NAME = "word/_rels/document.xml.rels";
    private static final Pattern VARIANT_ID = Pattern.compile("rIdVariant(\\d+)");

    private final DocxPackage pkg;
    private final Document relsDoc;
    private final Set<String> existingIds;
    private int nextVariantNumber;

    private RelationshipWriter(DocxPackage pkg, Document relsDoc, int nextVariantNumber, Set<String> existingIds) {
        this.pkg = pkg;
        this.relsDoc = relsDoc;
        this.nextVariantNumber = nextVariantNumber;
        this.existingIds = existingIds;
    }

    public static RelationshipWriter forPackage(DocxPackage pkg) throws IOException {
        Document relsDoc = pkg.hasPart(PART_NAME) ? SafeXml.parse(pkg.readPart(PART_NAME)) : emptyRelationshipsDoc();

        int next = 1;
        Set<String> ids = new HashSet<>();
        for (Element rel : DomUtil.elementChildren(relsDoc.getDocumentElement())) {
            String id = DomUtil.attr(rel, "Id");
            if (id != null) {
                ids.add(id);
                Matcher m = VARIANT_ID.matcher(id);
                if (m.matches()) {
                    next = Math.max(next, Integer.parseInt(m.group(1)) + 1);
                }
            }
        }
        return new RelationshipWriter(pkg, relsDoc, next, ids);
    }

    /**
     * Adds a new external hyperlink relationship and returns its fresh id (e.g. "rIdVariant7").
     * The candidate id is checked against every id already in the part, not just ones matching
     * the {@code rIdVariant} pattern — an id is never reused, even one that looks free.
     */
    public String addHyperlink(String url) {
        String id;
        do {
            id = "rIdVariant" + nextVariantNumber++;
        } while (existingIds.contains(id));
        existingIds.add(id);

        Element rel = relsDoc.createElementNS(REL_NS, "Relationship");
        rel.setAttribute("Id", id);
        rel.setAttribute("Type", HYPERLINK_TYPE);
        rel.setAttribute("Target", url);
        rel.setAttribute("TargetMode", "External");
        relsDoc.getDocumentElement().appendChild(rel);
        return id;
    }

    /** Writes the accumulated relationships back into the package. Call once, after rendering. */
    public void flush() throws IOException {
        byte[] bytes = XmlSerialize.toBytes(relsDoc);
        if (pkg.hasPart(PART_NAME)) {
            pkg.writePart(PART_NAME, bytes);
        } else {
            pkg.addPart(PART_NAME, bytes);
        }
    }

    private static Document emptyRelationshipsDoc() throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"" + REL_NS + "\"></Relationships>";
        return SafeXml.parse(xml.getBytes(StandardCharsets.UTF_8));
    }
}
