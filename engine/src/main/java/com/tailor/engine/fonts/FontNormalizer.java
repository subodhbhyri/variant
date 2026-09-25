package com.tailor.engine.fonts;

import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.measure.PdfPageCounter;
import com.tailor.engine.render.RenderException;
import com.tailor.engine.render.Renderer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Font normalization (spec section 3): remap fonts everywhere they're named,
 * remove PDF-converter letter squeezing, and — if the result still overflows
 * the target page count — shrink text by 0.5pt then 1.0pt before giving up.
 *
 * All three steps run from the paragraph-mark-untouched original text; a
 * shrink attempt always starts from the pre-shrink (but post font-map)
 * sizes, never compounding a previous shrink attempt.
 */
public final class FontNormalizer {

    private static final Pattern FONT_ATTR =
            Pattern.compile("(w:(?:ascii|hAnsi|cs|eastAsia))=\"([^\"]+)\"");
    private static final Pattern THEME_LATIN =
            Pattern.compile("(<a:latin typeface=\")([^\"]+)(\")");
    private static final Pattern NEGATIVE_SPACING =
            Pattern.compile("<w:spacing w:val=\"-\\d+\"\\s*/>");
    private static final Pattern SIZE_ATTR =
            Pattern.compile("<w:(sz|szCs) w:val=\"(\\d+)\"");

    private static final List<String> FONT_PARTS = List.of("word/document.xml", "word/styles.xml");
    private static final List<String> FONT_PART_PREFIXES = List.of("word/header", "word/footer");
    private static final int MAX_SHRINK_STEPS = 2; // 0, 0.5pt, 1.0pt

    private final FontMap fontMap;
    private final Renderer renderer;

    public FontNormalizer(FontMap fontMap, Renderer renderer) {
        this.fontMap = fontMap;
        this.renderer = renderer;
    }

    /**
     * Normalizes {@code src} and writes the result to {@code dst}.
     *
     * @throws NeedsUserException if no shrink step reaches {@code targetPages}
     */
    public NormalizeResult normalize(Path src, Path dst, int targetPages, Path renderTmpDir)
            throws IOException, RenderException, NeedsUserException {
        DocxPackage base = DocxPackage.open(src);
        int squeezeRemoved = applyFontMapAndSqueezeRemoval(base);
        int positionRemoved = ConverterArtifactCleaner.stripBulletPositions(base);

        String pristineDocumentXml = new String(base.readPart("word/document.xml"), StandardCharsets.UTF_8);
        String pristineStylesXml = base.hasPart("word/styles.xml")
                ? new String(base.readPart("word/styles.xml"), StandardCharsets.UTF_8)
                : null;

        for (int steps = 0; steps <= MAX_SHRINK_STEPS; steps++) {
            DocxPackage attempt = base.copy();
            if (steps > 0) {
                attempt.writePart("word/document.xml",
                        shrinkSizes(pristineDocumentXml, steps).getBytes(StandardCharsets.UTF_8));
                if (pristineStylesXml != null) {
                    attempt.writePart("word/styles.xml",
                            shrinkSizes(pristineStylesXml, steps).getBytes(StandardCharsets.UTF_8));
                }
            }
            attempt.save(dst);
            Path pdf = renderer.render(dst, renderTmpDir);
            int pages = PdfPageCounter.count(pdf);
            if (pages <= targetPages) {
                return new NormalizeResult(squeezeRemoved, steps * 0.5, pages, positionRemoved);
            }
        }
        throw new NeedsUserException(src, targetPages);
    }

    /** Applies the font map (document/styles/headers/footers/theme/numbering) and removes letter-squeezing. */
    private int applyFontMapAndSqueezeRemoval(DocxPackage pkg) {
        int squeezeRemoved = 0;

        for (String partName : FONT_PARTS) {
            byte[] bytes = pkg.readPart(partName);
            if (bytes == null) {
                continue;
            }
            String xml = remapFontAttrs(new String(bytes, StandardCharsets.UTF_8), false);
            if (partName.equals("word/document.xml")) {
                RemovalResult r = removeNegativeSpacing(xml);
                xml = r.xml();
                squeezeRemoved = r.count();
            }
            pkg.writePart(partName, xml.getBytes(StandardCharsets.UTF_8));
        }

        for (String prefix : FONT_PART_PREFIXES) {
            for (String partName : pkg.partNamesStartingWith(prefix)) {
                if (!partName.endsWith(".xml")) {
                    continue;
                }
                String xml = remapFontAttrs(new String(pkg.readPart(partName), StandardCharsets.UTF_8), false);
                pkg.writePart(partName, xml.getBytes(StandardCharsets.UTF_8));
            }
        }

        if (pkg.hasPart("word/numbering.xml")) {
            String xml = remapFontAttrs(
                    new String(pkg.readPart("word/numbering.xml"), StandardCharsets.UTF_8), true);
            pkg.writePart("word/numbering.xml", xml.getBytes(StandardCharsets.UTF_8));
        }

        for (String partName : pkg.partNamesStartingWith("word/theme/")) {
            byte[] bytes = pkg.readPart(partName);
            if (bytes == null) {
                continue;
            }
            String xml = remapThemeLatin(new String(bytes, StandardCharsets.UTF_8));
            pkg.writePart(partName, xml.getBytes(StandardCharsets.UTF_8));
        }

        return squeezeRemoved;
    }

    private String remapFontAttrs(String xml, boolean isNumberingPart) {
        Matcher m = FONT_ATTR.matcher(xml);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String attr = m.group(1);
            String value = m.group(2);
            String replacement = (isNumberingPart && fontMap.isNumberingGlyphKeep(value))
                    ? value
                    : fontMap.replacement(value).orElse(value);
            m.appendReplacement(sb, Matcher.quoteReplacement(attr + "=\"" + replacement + "\""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String remapThemeLatin(String xml) {
        Matcher m = THEME_LATIN.matcher(xml);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement = fontMap.replacement(m.group(2)).orElse(m.group(2));
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + replacement + m.group(3)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private record RemovalResult(String xml, int count) {
    }

    private static RemovalResult removeNegativeSpacing(String xml) {
        Matcher m = NEGATIVE_SPACING.matcher(xml);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (m.find()) {
            count++;
            m.appendReplacement(sb, "");
        }
        m.appendTail(sb);
        return new RemovalResult(sb.toString(), count);
    }

    /** Reduces every w:sz/w:szCs by {@code halfPointSteps} half-point units (1 unit = 0.5pt). */
    private static String shrinkSizes(String xml, int halfPointSteps) {
        Matcher m = SIZE_ATTR.matcher(xml);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int newVal = Integer.parseInt(m.group(2)) - halfPointSteps;
            m.appendReplacement(sb, Matcher.quoteReplacement("<w:" + m.group(1) + " w:val=\"" + newVal + "\""));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}