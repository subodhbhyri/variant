package com.tailor.engine.blocks;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * PHASE3_SPEC.md section 4 (revised after the Opus review of step 3.2): a
 * library link's URL must be at most 2,048 characters; printable ASCII only
 * (0x21-0x7E — no spaces, control, invisible or right-to-left characters, no
 * look-alike letters; international domains must be written as {@code xn--...}),
 * which the ASCII restriction enforces on its own; and either {@code http://}/
 * {@code https://} with a non-empty host and no user-info part (rejects
 * {@code https://github.com@evil.example}), or {@code mailto:} followed by
 * {@code name@domain}. Checked before any header rewrite touches the page —
 * the upload gate doesn't cover these, since they're created after it runs.
 */
public final class LinkValidator {

    private static final int MAX_LENGTH = 2048;
    private static final Pattern MAILTO_NAME_AT_DOMAIN = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private LinkValidator() {
    }

    public static final class InvalidLinkException extends RuntimeException {
        public InvalidLinkException(String url) {
            super("INVALID_LINK: " + url);
        }
    }

    public static boolean isValid(String url) {
        if (url == null || url.isEmpty() || url.length() > MAX_LENGTH || !isPrintableAscii(url)) {
            return false;
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        String lowerScheme = scheme.toLowerCase(Locale.ROOT);
        if (lowerScheme.equals("http") || lowerScheme.equals("https")) {
            return uri.getUserInfo() == null && uri.getHost() != null && !uri.getHost().isEmpty();
        }
        if (lowerScheme.equals("mailto")) {
            String rest = uri.getSchemeSpecificPart();
            return rest != null && MAILTO_NAME_AT_DOMAIN.matcher(rest).matches();
        }
        return false;
    }

    private static boolean isPrintableAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /** @throws InvalidLinkException if {@code url} fails validation. */
    public static void validate(String url) {
        if (!isValid(url)) {
            throw new InvalidLinkException(url);
        }
    }
}
