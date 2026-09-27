package com.tailor.engine.generate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** PHASE4_SPEC.md section 1: a section's header fields, pre-filled from the resume. Only the
 * fields the resume's header template has are shown and accepted; absent ones are null. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IntakeFields(String title, String detail, List<Link> links, String date) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Link(String label, String url) {
    }
}
