package com.tailor.engine.blocks;

import java.util.regex.Pattern;

/** Shared regexes (PHASE3_SPEC.md section 3.2/4, reference: {@code DATE}/{@code URLISH}). */
final class DatePattern {

    static final Pattern DATE = Pattern.compile(
            "(?i)\\(?\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s*\\d{4}\\s*[-–—]\\s*"
                    + "(?:(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s*\\d{4}|present|current|now)\\)?\\s*$");

    static final Pattern URLISH = Pattern.compile(
            "(?i)^(https?://|www\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)+(/\\S*)?$");

    private DatePattern() {
    }
}
