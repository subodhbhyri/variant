package com.tailor.engine.golden;

/** One emphasis span within a bullet's text (spec section 4.5). */
public record GoldenSpan(int start, int end, boolean bold, boolean italic) {
}
