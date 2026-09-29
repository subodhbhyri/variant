package com.tailor.engine.match;

import java.util.Map;

/**
 * PHASE5_SPEC.md section 1: a parsed job description — {@code skills} maps each canonical
 * skill name to its weight (1.0 required, 0.5 preferred, 0.3 other/unheaded text), sorted by
 * key to match {@code reference/jd_ref.py}'s {@code dict(sorted(found.items()))}.
 */
public record JobDescription(String title, Map<String, Double> skills, String requirementText) {
}
