package com.tailor.engine.generate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** PHASE4_SPEC.md section 1: {@code intake.json}'s top level. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Intake(List<IntakeSection> sections) {
}
