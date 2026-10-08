package com.tailor.engine.measure;

import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Extracts lines by clustering characters on baseline position (spec section
 * 6.1), rather than trusting PDFTextStripper's own line breaks, which can
 * split or merge lines in unusual layouts (this was the original motivation
 * for text-anchored measurement over {@code pdftotext -layout}).
 *
 * Deliberately does NOT sort characters by Y itself: PDFBox's Y-axis sign
 * convention for {@code getYDirAdj()} is not something this environment can
 * verify by running code, and getting it backward would silently scramble
 * line order rather than fail loudly. Instead, characters are kept in
 * PDFTextStripper's own callback order, which is already top-to-bottom per
 * page when {@code sortByPosition(true)} is set — that ordering is the
 * documented purpose of the flag. Y is used only for the "same line?"
 * threshold check (an absolute-value comparison, so its sign doesn't
 * matter); X, which is unambiguous, orders characters within a line.
 */
public final class PdfLines {

    public record Line(int pageIndex, double y, String normalizedText) {
    }

    /** A page's size in PDF points (1/72 inch). */
    public record PageSize(int pageIndex, double width, double height) {
    }

    /**
     * One extracted line's box, in PDF points from the page's top-left corner: {@code x} the left edge of its first
     * glyph, {@code w} up to the right edge of its last, {@code y} the top (the baseline minus the tallest font ascent on
     * the line) and {@code h} the height down to the lowest font descent. It describes the same line as the
     * {@link Line} at the same position in {@link #extract}'s result (the same clustering), and is measured from the
     * same glyphs, not re-derived from text.
     */
    public record LineBox(int pageIndex, double x, double y, double w, double h, double baseline) {
    }

    /** Pages, lines and line boxes of one PDF; {@code lines} and {@code boxes} correspond one to one. */
    public record Layout(List<PageSize> pages, List<Line> lines, List<LineBox> boxes) {
    }

    private record CharPos(double y, double x, String unicode, double xEnd, double top, double bottom) {
    }

    private static final double LINE_THRESHOLD_PT = 3.0;

    public static List<Line> extract(Path pdfPath) throws IOException {
        return scan(pdfPath, false).lines();
    }

    /** As {@link #extract}, with each line's box and each page's size (PHASE6_SPEC.md revision 3). */
    public static Layout extractLayout(Path pdfPath) throws IOException {
        return scan(pdfPath, true);
    }

    private static Layout scan(Path pdfPath, boolean withBoxes) throws IOException {
        List<Line> lines = new ArrayList<>();
        List<LineBox> boxes = withBoxes ? new ArrayList<>() : null;
        List<PageSize> pages = new ArrayList<>();
        int[] currentPage = {-1};
        List<CharPos> pageBuffer = new ArrayList<>();

        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) throws IOException {
                    int pageIndex = getCurrentPageNo() - 1;
                    if (pageIndex != currentPage[0]) {
                        flushPage(currentPage[0], pageBuffer, lines, boxes);
                        pageBuffer.clear();
                        currentPage[0] = pageIndex;
                    }
                    for (TextPosition tp : positions) {
                        String u = tp.getUnicode();
                        if (u == null || u.isBlank()) {
                            continue;
                        }
                        pageBuffer.add(withBoxes ? boxed(tp, u) : new CharPos(tp.getYDirAdj(), tp.getXDirAdj(), u, 0, 0, 0));
                    }
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(doc);
            flushPage(currentPage[0], pageBuffer, lines, boxes);
            if (withBoxes) {
                for (int i = 0; i < doc.getNumberOfPages(); i++) {
                    PDPage page = doc.getPage(i);
                    PDRectangle box = page.getCropBox();
                    boolean turned = page.getRotation() == 90 || page.getRotation() == 270;
                    pages.add(new PageSize(i, turned ? box.getHeight() : box.getWidth(), turned ? box.getWidth() : box.getHeight()));
                }
            }
        }
        return new Layout(pages, lines, boxes);
    }

    /** A glyph with the extent of its font's ascent and descent, from the PDF's own font descriptor. */
    private static CharPos boxed(TextPosition tp, String unicode) {
        double baseline = tp.getYDirAdj();
        double size = tp.getFontSizeInPt();
        double ascent = tp.getHeightDir();
        double descent = 0;
        PDFontDescriptor descriptor = tp.getFont() == null ? null : tp.getFont().getFontDescriptor();
        if (descriptor != null && descriptor.getAscent() > 0) {
            ascent = descriptor.getAscent() * size / 1000.0;
            descent = Math.abs(descriptor.getDescent()) * size / 1000.0;
        }
        double x = tp.getXDirAdj();
        return new CharPos(baseline, x, unicode, x + tp.getWidthDirAdj(), baseline - ascent, baseline + descent);
    }

    private static void flushPage(int pageIndex, List<CharPos> pageChars, List<Line> out, List<LineBox> boxesOut) {
        if (pageIndex < 0 || pageChars.isEmpty()) {
            return;
        }
        List<List<CharPos>> clusters = new ArrayList<>();
        for (CharPos c : pageChars) {
            if (!clusters.isEmpty()) {
                List<CharPos> current = clusters.get(clusters.size() - 1);
                double clusterStartY = current.get(0).y();
                if (Math.abs(c.y() - clusterStartY) <= LINE_THRESHOLD_PT) {
                    current.add(c);
                    continue;
                }
            }
            List<CharPos> fresh = new ArrayList<>();
            fresh.add(c);
            clusters.add(fresh);
        }
        for (List<CharPos> cluster : clusters) {
            cluster.sort(Comparator.comparingDouble(CharPos::x));
            StringBuilder sb = new StringBuilder();
            for (CharPos c : cluster) {
                sb.append(c.unicode());
            }
            out.add(new Line(pageIndex, cluster.get(0).y(), normalize(sb.toString())));
            if (boxesOut != null) {
                double left = Double.MAX_VALUE;
                double right = -Double.MAX_VALUE;
                double top = Double.MAX_VALUE;
                double bottom = -Double.MAX_VALUE;
                for (CharPos c : cluster) {
                    left = Math.min(left, c.x());
                    right = Math.max(right, c.xEnd());
                    top = Math.min(top, c.top());
                    bottom = Math.max(bottom, c.bottom());
                }
                boxesOut.add(new LineBox(pageIndex, left, top, right - left, bottom - top, cluster.get(0).y()));
            }
        }
    }

    private static String normalize(String s) {
        // Word's no-break hyphen (U+2011, or U+2010) is written as an ordinary hyphen in the text model.
        String nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC).replace('‐', '-').replace('‑', '-');
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
