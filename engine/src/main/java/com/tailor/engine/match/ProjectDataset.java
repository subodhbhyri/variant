package com.tailor.engine.match;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tailor.engine.generate.IntakeFields;
import java.util.Map;

/**
 * P5-T8 operator tooling: one entry of {@code project_datasets.json}, the operator's own raw
 * material per project, fed to {@code tailor intake-fill}. The top level is a map keyed by a
 * free-form display name (shown in the operator's own file for readability only, e.g. "LLM
 * Eval") — matching against an {@code intake-template}-generated project section is on {@link
 * #fields}' own {@code title} (only the part before the first {@code ":"}), never on the map key.
 *
 * <pre>{@code
 * {"projects": {"<display name>": {"fields": {"title": "...", "detail": "...", "links": [...]},
 *                                   "raw_text": "..."}}}
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectDataset(IntakeFields fields, String rawText) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectDatasets(Map<String, ProjectDataset> projects) {
    }
}
