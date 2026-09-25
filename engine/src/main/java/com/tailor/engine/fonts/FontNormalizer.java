package com.tailor.engine.fonts;

import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.measure.PdfPageCounter;
import com.tailor.engine.render.RenderException;
import com.tailor.engine.render.Renderer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

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
     * The font-mapped, squeeze-removed, bullet-position-cleaned package a shrink
     * loop starts from, plus the pristine (pre-shrink) XML a shrink step re-derives
     * from every time — never compounding a previous shrink attempt (PHASE1_SPEC.md 3.3).
     */
    public record PreparedBase(
            DocxPackage base, int squeezeRemoved, int positionRemoved,
            String pristineDocumentXml, String pristineStylesXml) {
    }

    /** Runs spec section 3.1-3.2 (font map, squeeze removal) and the bullet-position cleanup once. */
    public PreparedBase prepare(Path src) throws IOException {
        DocxPackage base = DocxPackage.open(src);
        int squeezeRemoved = applyFontMapAndSqueezeRemoval(base);
        int positionRemoved = ConverterArtifactCleaner.stripBulletPositions(base);
        String pristineDocumentXml = new String(base.readPart("word/document.xml"), StandardCharsets.UTF_8);
        String pristineStylesXml = base.hasPart("word/styles.xml")
                ? new String(base.readPart("word/styles.xml"), StandardCharsets.UTF_8)
                : null;
        return new PreparedBase(base, squeezeRemoved, positionRemoved, pristineDocumentXml, pristineStylesXml);
    }

    /** A copy of {@code prepared}'s base with {@code steps} half-point shrink steps applied (0 = none). */
    public DocxPackage shrunkCopy(PreparedBase prepared, int steps) {
        DocxPackage attempt = prepared.base().copy();
        if (steps > 0) {
            attempt.writePart("word/document.xml",
                    shrinkSizes(prepared.pristineDocumentXml(), steps).getBytes(StandardCharsets.UTF_8));
            if (prepared.pristineStylesXml() != null) {
                attempt.writePart("word/styles.xml",
                        shrinkSizes(prepared.pristineStylesXml(), steps).getBytes(StandardCharsets.UTF_8));
            }
        }
        return attempt;
    }

    /**
     * Normalizes {@code src} and writes the result to {@code dst}.
     *
     * @throws NeedsUserException if no shrink step reaches {@code targetPages}
     */
    public NormalizeResult normalize(Path src, Path dst, int targetPages, Path renderTmpDir)
            throws IOException, RenderException, NeedsUserException {
        PreparedBase prepared = prepare(src);

        for (int steps = 0; steps <= MAX_SHRINK_STEPS; steps++) {
            DocxPackage attempt = shrunkCopy(prepared, steps);
            attempt.save(dst);
            Path pdf = renderer.render(dst, renderTmpDir);
            int pages = PdfPageCounter.count(pdf);
            if (pages <= targetPages) {
                return new NormalizeResult(prepared.squeezeRemoved(), steps * 0.5, pages, prepared.positionRemoved());
            }
        }
        throw new NeedsUserException(src, targetPages);
    }

    private static final List<String> RFONTS_ATTRS = List.of("ascii", "hAnsi", "cs", "eastAsia");
    private static final List<String> STYLE_REF_TAGS = List.of("pStyle", "rStyle", "tblStyle");
    private static final int MAX_STYLE_CLOSURE = 2000; // generous global cap against a cyclic basedOn chain

    /**
     * Every literal (or theme-resolved) font name that would actually be applied to some
     * visible run — PHASE2_SPEC.md 4.3's input for "named in the document". DOM-based, not
     * regex: a scan must not match {@code <w:lang w:eastAsia="en-US">} (a locale code, not a
     * font, on an attribute name that collides with {@code <w:rFonts>}'s). Scans {@code
     * <w:rFonts>} wherever it can apply to visible text: run and paragraph-mark overrides in
     * document.xml/headers/footers, docDefaults, and every style a paragraph or run actually
     * references (pStyle/rStyle/tblStyle) plus its basedOn chain — never every style in
     * styles.xml, since most are Word's built-in latent styles nothing applies (e.g. the
     * default "MacroText" style). A {@code w:asciiTheme}/{@code w:cstheme}/etc. reference is
     * resolved against the theme's font scheme. Does not look in word/numbering.xml: that
     * part's fonts are usually bullet glyphs, which have their own glyph-font exclusion
     * (PHASE1_SPEC.md 3.1's numberingGlyphKeep). The font audit (3.4) is the backstop for
     * whatever this heuristic still misses.
     */
    public Set<String> namedFonts(DocxPackage pkg) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        ThemeFonts theme = ThemeFonts.load(pkg);

        collectRunLevelFonts(pkg, "word/document.xml", theme, names);
        for (String prefix : FONT_PART_PREFIXES) {
            for (String partName : pkg.partNamesStartingWith(prefix)) {
                if (partName.endsWith(".xml")) {
                    collectRunLevelFonts(pkg, partName, theme, names);
                }
            }
        }
        collectUsedStyleFonts(pkg, theme, names);
        return names;
    }

    private void collectRunLevelFonts(DocxPackage pkg, String partName, ThemeFonts theme, Set<String> out)
            throws IOException {
        byte[] bytes = pkg.readPart(partName);
        if (bytes == null) {
            return;
        }
        Document doc = SafeXml.parse(bytes);
        for (Element rFonts : DomUtil.descendants(doc.getDocumentElement(), "rFonts")) {
            addRFontsAttrs(rFonts, theme, out);
        }
    }

    /** docDefaults, "Normal", and every style actually referenced (plus its basedOn chain). */
    private void collectUsedStyleFonts(DocxPackage pkg, ThemeFonts theme, Set<String> out) throws IOException {
        if (!pkg.hasPart("word/styles.xml")) {
            return;
        }
        Document stylesDoc = SafeXml.parse(pkg.readPart("word/styles.xml"));
        Element stylesRoot = stylesDoc.getDocumentElement();

        Map<String, Element> styleById = new HashMap<>();
        for (Element style : DomUtil.elementChildren(stylesRoot)) {
            if ("style".equals(style.getLocalName())) {
                String id = DomUtil.attr(style, "styleId");
                if (id != null) {
                    styleById.put(id, style);
                }
            }
        }

        Set<String> usedIds = new LinkedHashSet<>();
        usedIds.add("Normal"); // the implicit default for any paragraph without its own pStyle
        collectReferencedStyleIds(pkg, "word/document.xml", usedIds);
        for (String prefix : FONT_PART_PREFIXES) {
            for (String partName : pkg.partNamesStartingWith(prefix)) {
                if (partName.endsWith(".xml")) {
                    collectReferencedStyleIds(pkg, partName, usedIds);
                }
            }
        }

        Set<String> closure = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(usedIds);
        int hops = 0;
        while (!queue.isEmpty() && hops < MAX_STYLE_CLOSURE) {
            String id = queue.poll();
            hops++;
            if (!closure.add(id)) {
                continue; // already resolved (or mid-cycle); basedOn already queued
            }
            Element style = styleById.get(id);
            Element basedOn = style != null ? DomUtil.firstChild(style, "basedOn") : null;
            String parentId = basedOn != null ? DomUtil.attr(basedOn, "val") : null;
            if (parentId != null) {
                queue.add(parentId);
            }
        }

        Element docDefaults = DomUtil.firstChild(stylesRoot, "docDefaults");
        if (docDefaults != null) {
            Element rPrDefault = DomUtil.firstChild(docDefaults, "rPrDefault");
            addRFontsFromRPr(rPrDefault != null ? DomUtil.firstChild(rPrDefault, "rPr") : null, theme, out);
        }
        for (String id : closure) {
            Element style = styleById.get(id);
            if (style != null) {
                addRFontsFromRPr(DomUtil.firstChild(style, "rPr"), theme, out);
            }
        }
    }

    private void collectReferencedStyleIds(DocxPackage pkg, String partName, Set<String> out) throws IOException {
        byte[] bytes = pkg.readPart(partName);
        if (bytes == null) {
            return;
        }
        Document doc = SafeXml.parse(bytes);
        Element root = doc.getDocumentElement();
        for (String tag : STYLE_REF_TAGS) {
            for (Element el : DomUtil.descendants(root, tag)) {
                String v = DomUtil.attr(el, "val");
                if (v != null) {
                    out.add(v);
                }
            }
        }
    }

    private void addRFontsFromRPr(Element rPr, ThemeFonts theme, Set<String> out) {
        Element rFonts = rPr != null ? DomUtil.firstChild(rPr, "rFonts") : null;
        if (rFonts != null) {
            addRFontsAttrs(rFonts, theme, out);
        }
    }

    private void addRFontsAttrs(Element rFonts, ThemeFonts theme, Set<String> out) {
        for (String attr : RFONTS_ATTRS) {
            String literal = nonBlank(DomUtil.attr(rFonts, attr));
            if (literal != null) {
                out.add(literal);
                continue;
            }
            String resolved = theme.resolve(DomUtil.attr(rFonts, attr.equals("cs") ? "cstheme" : attr + "Theme"));
            if (resolved != null) {
                out.add(resolved);
            }
        }
    }

    /** Null for a missing or empty value — a theme's east-asian/complex-script slot is
     * routinely present but empty ({@code <a:ea typeface=""/>}) for a Latin-only theme. */
    private static String nonBlank(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** The theme's font scheme (minor/major x latin/ea/cs), for resolving {@code w:*Theme} attributes. */
    private record ThemeFonts(
            String minorLatin, String majorLatin, String minorEa, String majorEa, String minorCs, String majorCs) {

        private static final ThemeFonts EMPTY = new ThemeFonts(null, null, null, null, null, null);

        static ThemeFonts load(DocxPackage pkg) throws IOException {
            for (String partName : pkg.partNamesStartingWith("word/theme/")) {
                if (!partName.endsWith(".xml")) {
                    continue;
                }
                byte[] bytes = pkg.readPart(partName);
                if (bytes == null) {
                    continue;
                }
                Document doc = SafeXml.parse(bytes);
                List<Element> schemes = DomUtil.descendants(doc.getDocumentElement(), "fontScheme");
                if (schemes.isEmpty()) {
                    continue;
                }
                Element scheme = schemes.get(0);
                Element minorFont = DomUtil.firstChild(scheme, "minorFont");
                Element majorFont = DomUtil.firstChild(scheme, "majorFont");
                return new ThemeFonts(
                        typefaceOf(minorFont, "latin"), typefaceOf(majorFont, "latin"),
                        typefaceOf(minorFont, "ea"), typefaceOf(majorFont, "ea"),
                        typefaceOf(minorFont, "cs"), typefaceOf(majorFont, "cs"));
            }
            return EMPTY;
        }

        private static String typefaceOf(Element fontGroup, String tag) {
            Element el = fontGroup != null ? DomUtil.firstChild(fontGroup, tag) : null;
            String typeface = el != null ? DomUtil.attr(el, "typeface") : null;
            return typeface == null || typeface.isEmpty() ? null : typeface;
        }

        /** {@code "minorHAnsi"/"majorBidi"/...} -> the theme's actual typeface name, or null. */
        String resolve(String themeValue) {
            if (themeValue == null || themeValue.length() <= 5) {
                return null;
            }
            boolean minor = themeValue.startsWith("minor");
            boolean major = !minor && themeValue.startsWith("major");
            if (!minor && !major) {
                return null;
            }
            return switch (themeValue.substring(5)) {
                case "Ascii", "HAnsi" -> minor ? minorLatin : majorLatin;
                case "EastAsia" -> minor ? minorEa : majorEa;
                case "Bidi" -> minor ? minorCs : majorCs;
                default -> null;
            };
        }
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