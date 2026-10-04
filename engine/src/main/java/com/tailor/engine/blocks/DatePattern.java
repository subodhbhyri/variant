package com.tailor.engine.blocks;

import java.util.regex.Pattern;

/** Shared regexes (PHASE3_SPEC.md section 3.2/4, reference: {@code DATE}/{@code URLISH}). */
final class DatePattern {

    static final Pattern DATE = Pattern.compile(
            "(?i)\\(?\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s*\\d{4}\\s*[-–—]\\s*"
                    + "(?:(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s*\\d{4}|present|current|now)\\)?\\s*$");

    static final Pattern URLISH = Pattern.compile(
            "(?i)^(https?://|www\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)+(/\\S*)?$");

    /** PHASE3_SPEC.md section 4 (reference: {@code SP}/{@code SEP_RE}): a header's stack/date
     * separator is a {@code |} with a plain space, NBSP (U+00A0), figure space (U+2007) or
     * narrow no-break space (U+202F) on each side — Word inserts these non-breaking variants, and
     * Java's {@code \s} doesn't match U+00A0, so the characters are listed explicitly. */
    static final String SEP_CHARS = "    ";

    static final Pattern SEP_RE = Pattern.compile("[" + SEP_CHARS + "]\\|[" + SEP_CHARS + "]");

    private DatePattern() {
    }
}
