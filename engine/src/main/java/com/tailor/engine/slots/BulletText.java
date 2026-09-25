package com.tailor.engine.slots;

import java.util.List;

/** A bullet's content: text plus non-overlapping bold/italic spans over it (spec section 5.1). */
public record BulletText(String text, List<EmphasisSpan> emphasis) {
}
