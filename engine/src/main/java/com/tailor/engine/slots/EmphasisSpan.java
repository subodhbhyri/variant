package com.tailor.engine.slots;

/** A half-open [start,end) span over a bullet's text where bold and/or italic is on. */
public record EmphasisSpan(int start, int end, boolean bold, boolean italic) {
}
