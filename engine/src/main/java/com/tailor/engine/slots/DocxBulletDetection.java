package com.tailor.engine.slots;

import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.numbering.NumberingResolver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Opens a .docx and runs {@link BulletDetector} on it in one call. */
public final class DocxBulletDetection {

    private DocxBulletDetection() {
    }

    public static List<Slot> detect(Path docxPath) throws IOException {
        DocxPackage pkg = DocxPackage.open(docxPath);

        byte[] documentBytes = pkg.readPart("word/document.xml");
        if (documentBytes == null) {
            throw new IOException("no word/document.xml in " + docxPath);
        }
        Document documentXml = SafeXml.parse(documentBytes);

        Element numberingRoot = pkg.hasPart("word/numbering.xml")
                ? SafeXml.parse(pkg.readPart("word/numbering.xml")).getDocumentElement()
                : null;
        Element stylesRoot = pkg.hasPart("word/styles.xml")
                ? SafeXml.parse(pkg.readPart("word/styles.xml")).getDocumentElement()
                : null;

        NumberingResolver resolver = new NumberingResolver(numberingRoot, stylesRoot);
        return BulletDetector.detect(documentXml, resolver);
    }
}
