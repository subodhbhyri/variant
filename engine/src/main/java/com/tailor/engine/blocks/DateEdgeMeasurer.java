package com.tailor.engine.blocks;

import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * PHASE3_SPEC.md 4.1: measures a header's own rendered line's rightmost
 * character's right edge, for {@link DateTabConverter}'s {@code dateEdgeTwips}.
 *
 * <p>Self-contained rather than extending {@code PdfLines}/{@code Line}: this
 * needs per-character width (for the right edge), which {@code Line} doesn't
 * carry, and {@code Line}'s constructor is already relied on directly by
 * existing tests — adding a field there would break them for no benefit here.
 */
public final class DateEdgeMeasurer {

    private static final double LINE_THRESHOLD_PT = 3.0; // matches PdfLines

    private record Glyph(double y, double x, double width, String unicode) {
    }

    private record MeasuredLine(String normalizedText, List<Glyph> glyphs) {
    }

    private DateEdgeMeasurer() {
    }

    public record GlyphPosition(int pageIndex, double x, double y, String unicode) {
    }

    /**
     * Every rendered character's own position, in reading order — for P3-T5's before/after
     * comparison. Since a date-tab conversion changes no text (only {@code pPr/tabs}
     * metadata), the two renders' glyph lists line up positionally (same index = same
     * character), so this deliberately does not cluster into lines the way {@link #extract}
     * does; the caller compares raw (x, y) pairs index-by-index.
     */
    public static List<GlyphPosition> allGlyphPositions(Path pdfPath) throws IOException {
        List<GlyphPosition> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    int pageIndex = getCurrentPageNo() - 1;
                    for (TextPosition tp : positions) {
                        String u = tp.getUnicode();
                        if (u == null || u.isBlank()) {
                            continue;
                        }
                        out.add(new GlyphPosition(pageIndex, tp.getXDirAdj(), tp.getYDirAdj(), u));
                    }
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(doc);
        }
        return out;
    }

    /**
     * The header's own rendered line's rightmost glyph's right edge, and that line's own Y
     * (for identifying which glyphs in a before/after comparison belong to it).
     *
     * @param rightEdgeTwips raw (untruncated) twips from the left margin — PHASE3_SPEC.md 4.1
     *                       truncates this to an int only at the point of clamping it to the
     *                       text width, in {@link DateTabConverter#rightAlignDateTab}, exactly
     *                       where the reference's {@code int(measured_edge_twips)} does; rounding
     *                       here instead would quantize a step early and could overshoot by a
     *                       whole twip for no reason
     */
    public record Measurement(double rightEdgeTwips, double lineY) {
    }

    /**
     * @param headerText      the header paragraph's own text (e.g. DomUtil.allText(p)) — not yet
     *                        normalized; normalized the same way as the rendered line is
     * @param leftMarginTwips the section's left margin, from word/document.xml's sectPr
     * @return empty if the header's text couldn't be found on the render at all
     */
    public static Optional<Measurement> measureRightEdge(Path pdfPath, String headerText, int leftMarginTwips)
            throws IOException {
        String target = normalize(headerText);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        for (MeasuredLine line : extract(pdfPath)) {
            if (line.normalizedText().contains(target)) {
                double rightmostPt = 0;
                for (Glyph g : line.glyphs()) {
                    rightmostPt = Math.max(rightmostPt, g.x() + g.width());
                }
                double twips = Math.max(rightmostPt * 20 - leftMarginTwips, 0);
                return Optional.of(new Measurement(twips, line.glyphs().get(0).y()));
            }
        }
        return Optional.empty();
    }

    private static List<MeasuredLine> extract(Path pdfPath) throws IOException {
        List<MeasuredLine> lines = new ArrayList<>();
        int[] currentPage = {-1};
        List<Glyph> pageBuffer = new ArrayList<>();

        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    int pageIndex = getCurrentPageNo() - 1;
                    if (pageIndex != currentPage[0]) {
                        flushPage(pageBuffer, lines);
                        pageBuffer.clear();
                        currentPage[0] = pageIndex;
                    }
                    for (TextPosition tp : positions) {
                        String u = tp.getUnicode();
                        if (u == null || u.isBlank()) {
                            continue;
                        }
                        pageBuffer.add(new Glyph(tp.getYDirAdj(), tp.getXDirAdj(), tp.getWidthDirAdj(), u));
                    }
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(doc);
            flushPage(pageBuffer, lines);
        }
        return lines;
    }

    private static void flushPage(List<Glyph> pageChars, List<MeasuredLine> out) {
        if (pageChars.isEmpty()) {
            return;
        }
        List<List<Glyph>> clusters = new ArrayList<>();
        for (Glyph g : pageChars) {
            if (!clusters.isEmpty()) {
                List<Glyph> current = clusters.get(clusters.size() - 1);
                double clusterStartY = current.get(0).y();
                if (Math.abs(g.y() - clusterStartY) <= LINE_THRESHOLD_PT) {
                    current.add(g);
                    continue;
                }
            }
            List<Glyph> fresh = new ArrayList<>();
            fresh.add(g);
            clusters.add(fresh);
        }
        for (List<Glyph> cluster : clusters) {
            cluster.sort(Comparator.comparingDouble(Glyph::x));
            StringBuilder sb = new StringBuilder();
            for (Glyph g : cluster) {
                sb.append(g.unicode());
            }
            out.add(new MeasuredLine(normalize(sb.toString()), cluster));
        }
    }

    private static String normalize(String s) {
        String nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nfkc.length(); i++) {
            char c = nfkc.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
