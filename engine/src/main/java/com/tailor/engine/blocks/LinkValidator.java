package com.tailor.engine.blocks;

import java.util.Locale;

/**
 * PHASE3_SPEC.md section 4: a library link's URL must be an absolute
 * {@code http://}, {@code https://} or {@code mailto:} URL, at most 2,048
 * characters, with no whitespace or control characters. Checked before any
 * header rewrite touches the page — the upload gate doesn't cover these,
 * since they're created after it runs.
 */
public final class LinkValidator {

    private static final int MAX_LENGTH = 2048;

    private LinkValidator() {
    }

    public static final class InvalidLinkException extends RuntimeException {
        public InvalidLinkException(String url) {
            super("INVALID_LINK: " + url);
        }
    }

    public static boolean isValid(String url) {
        if (url == null || url.isEmpty() || url.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                return false;
            }
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:");
    }

    /** @throws InvalidLinkException if {@code url} fails validation. */
    public static void validate(String url) {
        if (!isValid(url)) {
            throw new InvalidLinkException(url);
        }
    }
}
