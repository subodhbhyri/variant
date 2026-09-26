package com.tailor.engine.blocks;

import com.tailor.engine.measure.PdfLines;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-only "does the rest of the page stay put" check, shared by the
 * inline-rewrite (P3-T6), fixture-swap (P3-T3) and rotation (P3-T4) tests:
 * anchor one edited chunk's own text on each render, and require every line
 * outside that chunk — on both renders — to be the same text at the same
 * position (PHASE3_SPEC.md section 7 point 4 / section 9: "every other line
 * must stay within 0.5pt").
 */
final class LayoutDiff {

    record Result(boolean ok, double maxMovement, String problem) {
    }

    private LayoutDiff() {
    }

    /**
     * @param spanBefore the edited chunk's own [firstLine, lastLine] on the before-render (null
     *                    if it couldn't be anchored there)
     * @param spanAfter  same, on the after-render
     */
    static Result checkOutsideMovement(List<PdfLines.Line> linesBefore, int[] spanBefore,
            List<PdfLines.Line> linesAfter, int[] spanAfter) {
        if (spanBefore == null) {
            return new Result(false, Double.NaN, "could not anchor the original position");
        }
        if (spanAfter == null) {
            return new Result(false, Double.NaN, "could not anchor the new position");
        }

        List<PdfLines.Line> fixedBefore = except(linesBefore, spanBefore[0], spanBefore[1]);
        List<PdfLines.Line> fixedAfter = except(linesAfter, spanAfter[0], spanAfter[1]);
        if (fixedBefore.size() != fixedAfter.size()) {
            return new Result(false, Double.NaN,
                    "fixed line count changed: " + fixedBefore.size() + " -> " + fixedAfter.size());
        }
        double worst = 0;
        for (int i = 0; i < fixedBefore.size(); i++) {
            PdfLines.Line b = fixedBefore.get(i);
            PdfLines.Line a = fixedAfter.get(i);
            if (!b.normalizedText().equals(a.normalizedText())) {
                return new Result(false, Double.NaN,
                        "fixed line " + i + " text differs: [" + b.normalizedText() + "] vs [" + a.normalizedText() + "]");
            }
            if (b.pageIndex() != a.pageIndex()) {
                return new Result(false, Double.NaN, "fixed line " + i + " moved pages");
            }
            worst = Math.max(worst, Math.abs(b.y() - a.y()));
        }
        return new Result(true, worst, null);
    }

    private static List<PdfLines.Line> except(List<PdfLines.Line> lines, int fromInclusive, int toInclusive) {
        List<PdfLines.Line> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (i < fromInclusive || i > toInclusive) {
                out.add(lines.get(i));
            }
        }
        return out;
    }
}
