package com.tailor.engine.match;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * {@code reference/jd_ref.py} rounds every score with Python's {@code round(x, 4)} — correctly
 * rounded on the double's exact binary value, ties to even. {@code new BigDecimal(double)} (not
 * {@code BigDecimal.valueOf}, which goes through the shortest decimal string first) captures that
 * exact value, so {@code setScale(4, HALF_EVEN)} on it reproduces Python's result bit for bit
 * (D7: determinism against {@code expected_selection.json}).
 */
final class Round {

    private Round() {
    }

    static double to4(double v) {
        return new BigDecimal(v).setScale(4, RoundingMode.HALF_EVEN).doubleValue();
    }
}
