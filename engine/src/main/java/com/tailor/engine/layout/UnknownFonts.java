package com.tailor.engine.layout;

import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.SafeXml;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** PHASE2_SPEC.md 4.3 (P3): family-based candidates for a font outside the Phase 1 map. */
public final class UnknownFonts {

    private static final List<String> ROMAN =
            List.of("Liberation Serif", "Caladea", "Gelasio RT", "TeX Gyre Pagella");
    private static final List<String> SWISS = List.of("Liberation Sans", "Carlito", "DejaVu Sans");
    private static final List<String> MODERN = List.of("Liberation Mono");

    private UnknownFonts() {
    }

    /** True if this font is a glyph-only font (spec 3.1/4.3), never a text font to substitute. */
    public static boolean isGlyphFont(String fontName) {
        return "Symbol".equals(fontName) || fontName.startsWith("Wingdings");
    }

    /** Candidates by family, in try-first-to-last order; unknown/missing family falls back to swiss. */
    public static List<String> candidatesForFamily(String family) {
        if (family == null) {
            return SWISS;
        }
        return switch (family.toLowerCase(Locale.ROOT)) {
            case "roman" -> ROMAN;
            case "swiss" -> SWISS;
            case "modern" -> MODERN;
            default -> SWISS;
        };
    }

    /** {@code word/fontTable.xml}'s {@code <w:font w:name>/<w:family w:val>} for this font, or null. */
    public static String familyOf(DocxPackage pkg, String fontName) throws IOException {
        if (!pkg.hasPart("word/fontTable.xml")) {
            return null;
        }
        Document doc = SafeXml.parse(pkg.readPart("word/fontTable.xml"));
        for (Element font : DomUtil.descendants(doc.getDocumentElement(), "font")) {
            if (fontName.equals(DomUtil.attr(font, "name"))) {
                Element family = DomUtil.firstChild(font, "family");
                return family != null ? DomUtil.attr(family, "val") : null;
            }
        }
        return null;
    }
}
