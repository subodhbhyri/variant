package com.tailor.engine.blocks;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * PHASE3_SPEC.md section 7: the Phase 4 contract for a project available to swap into a
 * position, as read from {@code library.json}. Each bullet has one text variant per line
 * count it could be asked to fill, keyed by that count as a string (e.g. {@code "1"}, {@code "2"}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LibraryProject(
        String id,
        String title,
        String detail,
        List<Link> links,
        String date,
        List<Map<String, String>> bullets) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Link(String label, String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Library(List<LibraryProject> projects) {
    }
}
