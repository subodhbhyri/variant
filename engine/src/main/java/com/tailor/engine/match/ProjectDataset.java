package com.tailor.engine.match;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tailor.engine.generate.IntakeFields;
import java.util.List;

/**
 * P5-T8 operator tooling: one entry of {@code project_datasets.json}, the operator's own raw
 * material per project, fed to {@code tailor intake-fill}. {@code title} doubles as the matching
 * key against an {@code intake-template}-generated project section's current title — only the
 * part before the first {@code ":"} is compared (and is what actually becomes the section's
 * title); the rest of the string is free-form notes for the operator's own file, never written
 * into the resume.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectDataset(String title, String detail, List<IntakeFields.Link> links, String rawText) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectDatasets(List<ProjectDataset> datasets) {
    }
}
