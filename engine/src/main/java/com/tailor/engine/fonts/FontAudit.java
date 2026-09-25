package com.tailor.engine.fonts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Checks that every rendered character used a font from {@link FontMap#auditAllowed()}.
 * A character in an unmapped font means normalization missed it — usually a
 * font referenced only through a style or theme path not yet covered.
 */
public final class FontAudit {

    public record Violation(String font, int charCount, String sample) {
    }

    private final FontMap fontMap;

    public FontAudit(FontMap fontMap) {
        this.fontMap = fontMap;
    }

    public List<Violation> audit(Path pdfPath) throws IOException {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> samples = new LinkedHashMap<>();

        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    for (TextPosition tp : positions) {
                        String ch = tp.getUnicode();
                        if (ch == null || ch.isBlank()) {
                            continue;
                        }
                        String rawName = tp.getFont() != null ? tp.getFont().getName() : null;
                        if (rawName == null) {
                            continue;
                        }
                        String base = baseName(rawName);
                        if (fontMap.auditAllowed().contains(base)) {
                            continue;
                        }
                        counts.computeIfAbsent(base, k -> new int[1])[0]++;
                        samples.putIfAbsent(base, ch);
                    }
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(doc); // drives writeString for the whole document
        }

        List<Violation> violations = new ArrayList<>();
        for (var e : counts.entrySet()) {
            violations.add(new Violation(e.getKey(), e.getValue()[0], samples.get(e.getKey())));
        }
        return violations;
    }

    /** Strips a subset prefix ("ABCDEF+") and any style suffix after the first hyphen. */
    private static String baseName(String rawFontName) {
        String name = rawFontName;
        if (name.length() > 7 && name.charAt(6) == '+') {
            name = name.substring(7);
        }
        int dash = name.indexOf('-');
        if (dash >= 0) {
            name = name.substring(0, dash);
        }
        return name;
    }
}
