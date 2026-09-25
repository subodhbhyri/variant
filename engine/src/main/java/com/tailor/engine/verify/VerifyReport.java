package com.tailor.engine.verify;

import com.tailor.engine.fonts.FontAudit;
import java.util.List;
import java.util.Map;

/**
 * Result of {@link Verifier#verify}.
 *
 * @param layoutShiftPt largest position change found by the layout check, in points
 * @param layoutProblem why the layout check could not run to completion (a line went
 *                      missing, changed page, or a slot could not be found), or null
 */
public record VerifyReport(
        boolean pagesMatch,
        int pagesBefore,
        int pagesAfter,
        Map<Integer, LineCheck> lineChecks,
        double layoutShiftPt,
        boolean layoutOk,
        String layoutProblem,
        List<FontAudit.Violation> fontViolations,
        boolean ok) {
}