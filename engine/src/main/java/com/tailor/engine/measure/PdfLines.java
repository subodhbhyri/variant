package com.tailor.engine.measure;

import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.pdfbox.Loader;
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

    private record CharPos(double y, double x, String unicode) {
    }

    private static final double LINE_THRESHOLD_PT = 3.0;

    public static List<Line> extract(Path pdfPath) throws IOException {
        List<Line> lines = new ArrayList<>();
        int[] currentPage = {-1};
        List<CharPos> pageBuffer = new ArrayList<>();

        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) throws IOException {
                    int pageIndex = getCurrentPageNo() - 1;
                    if (pageIndex != currentPage[0]) {
                        flushPage(currentPage[0], pageBuffer, lines);
                        pageBuffer.clear();
                        currentPage[0] = pageIndex;
                    }
                    for (TextPosition tp : positions) {
                        String u = tp.getUnicode();
                        if (u == null || u.isBlank()) {
                            continue;
                        }
                        pageBuffer.add(new CharPos(tp.getYDirAdj(), tp.getXDirAdj(), u));
                    }
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(doc);
            flushPage(currentPage[0], pageBuffer, lines);
        }
        return lines;
    }

    private static void flushPage(int pageIndex, List<CharPos> pageChars, List<Line> out) {
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
