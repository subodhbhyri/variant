package com.tailor.engine.generate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * PHASE4_SPEC.md section 1: one section of {@code intake.json}.
 *
 * @param id      Phase 3 position order: {@code job-N}, {@code project-N}; an added project is
 *                {@code project-new-N}
 * @param kind    {@code "job"} or {@code "project"}
 * @param mode    {@code DETAILED}, {@code EXISTING_ONLY}, or {@code SKIPPED} (D2)
 * @param rawText candidate material, DETAILED only; empty/absent for EXISTING_ONLY and SKIPPED
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IntakeSection(String id, String kind, String mode, IntakeFields fields, String rawText) {
}
