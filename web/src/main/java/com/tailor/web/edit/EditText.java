package com.tailor.web.edit;

import com.tailor.engine.generate.BulletEnding;
import java.util.regex.Pattern;

/**
 * PHASE6_SPEC.md section 6.1, input normalization for an edited bullet: trim; newlines and tabs become
 * spaces; runs of spaces collapse; at most 1,000 characters; and the bullet ending follows the resume's own
 * convention, as for generated text (Phase 4 section 6.1). The words themselves are the user's own claim and
 * are never changed. Empty text (after trimming) means a blank: the slot keeps its height.
 */
public final class EditText {

    public static final int MAX_CHARS = 1000;
    private static final Pattern LINE_BREAKS_AND_TABS = Pattern.compile("[\\r\\n\\t\\u000B\\u000C\\u0085\\u2028\\u2029]+");
    private static final Pattern SPACES = Pattern.compile(" {2,}");

    private EditText() {
    }

    public record Normalized(String text, boolean tooLong) {
        public boolean blank() {
            return text.isEmpty();
        }
    }

    /** @param endingConvention {@link BulletEnding#convention} of the resume's own bullets (may be null) */
    public static Normalized normalize(String raw, String endingConvention) {
        String s = raw == null ? "" : raw;
        s = LINE_BREAKS_AND_TABS.matcher(s).replaceAll(" ");
        s = SPACES.matcher(s).replaceAll(" ").strip();
        if (s.length() > MAX_CHARS) {
            return new Normalized(s, true);
        }
        if (s.isEmpty()) {
            return new Normalized("", false);
        }
        return new Normalized(BulletEnding.apply(s, endingConvention), false);
    }
}
