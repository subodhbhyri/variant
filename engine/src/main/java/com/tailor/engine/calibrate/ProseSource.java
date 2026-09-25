package com.tailor.engine.calibrate;

import com.tailor.engine.slots.Slot;
import java.util.List;

/**
 * A deterministic source of realistic prose for calibration (spec 7.3).
 *
 * The spec's reference calibration text was built from a Python-seeded
 * shuffle of real corpus bullets. Replicating that exact shuffle would mean
 * reimplementing Python's RNG for no functional benefit: calibration only
 * produces a hint (validation, section 7.4, is the actual gate), so its
 * numeric output isn't required to match golden's max_chars_prose. This
 * builds prose from the corpus's own real bullet text instead, in a fixed
 * order, so it's realistic (not lorem-ipsum) and deterministic (needed for
 * T11: calibrating twice must give identical results).
 */
public final class ProseSource {

    private ProseSource() {
    }

    public static String fromSlots(List<Slot> slots, int minLength) {
        StringBuilder sb = new StringBuilder();
        if (slots.isEmpty()) {
            throw new IllegalArgumentException("need at least one slot's text to build prose from");
        }
        int i = 0;
        while (sb.length() < minLength) {
            Slot s = slots.get(i % slots.size());
            String t = s.text().strip();
            if (!t.isEmpty()) {
                sb.append(t).append(' ');
            }
            i++;
        }
        return sb.toString();
    }
}
