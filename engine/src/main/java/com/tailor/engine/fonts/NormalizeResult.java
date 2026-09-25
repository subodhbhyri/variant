package com.tailor.engine.fonts;

/** Result of {@link FontNormalizer#normalize}. */
public record NormalizeResult(int squeezeRemoved, double shrinkPt, int pages, int positionRemoved) {
}