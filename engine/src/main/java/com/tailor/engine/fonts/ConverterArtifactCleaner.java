package com.tailor.engine.fonts;

import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.docx.XmlSerialize;
import com.tailor.engine.numbering.NumberingResolver;
import com.tailor.engine.slots.BulletDetector;
import com.tailor.engine.slots.Slot;
import java.io.IOException;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Removes PDF-converter layout artifacts from bullet paragraphs, as part of
 * font normalization (spec section 3.2).
 *
 * Non-zero {@code w:position} (a baseline raise/lower) on some runs of a
 * bullet raises the height of whichever line those runs land on. It can't be
 * carried over meaningfully to new words, so leaving it in makes every
 * substituted bullet shift the page. Measured on the corpus: removing it
 * inside bullets changes no page count, no shrink step, and no line count,
 * and brings post-substitution drift to 0.0pt on all 9 resumes. Only bullet
 * paragraphs are touched; locked content stays byte-identical.
 */
public final class ConverterArtifactCleaner {

    private ConverterArtifactCleaner() {
    }

    /** Strips non-zero w:position from runs inside bullet paragraphs. Returns how many were removed. */
    public static int stripBulletPositions(DocxPackage pkg) throws IOException {
        Document doc = SafeXml.parse(pkg.readPart("word/document.xml"));
        Element numberingRoot = pkg.hasPart("word/numbering.xml")
                ? SafeXml.parse(pkg.readPart("word/numbering.xml")).getDocumentElement() : null;
        Element stylesRoot = pkg.hasPart("word/styles.xml")
                ? SafeXml.parse(pkg.readPart("word/styles.xml")).getDocumentElement() : null;
        List<Slot> slots = BulletDetector.detect(doc, new NumberingResolver(numberingRoot, stylesRoot));

        int removed = 0;
        for (Slot slot : slots) {
            for (Element r : DomUtil.descendants(slot.element(), "r")) {
                Element rPr = DomUtil.firstChild(r, "rPr");
                if (rPr == null) {
                    continue;
                }
                Element position = DomUtil.firstChild(rPr, "position");
                if (position != null && !"0".equals(DomUtil.attr(position, "val"))) {
                    rPr.removeChild(position);
                    removed++;
                }
            }
        }
        if (removed > 0) {
            pkg.writePart("word/document.xml", XmlSerialize.toBytes(doc));
        }
        return removed;
    }
}