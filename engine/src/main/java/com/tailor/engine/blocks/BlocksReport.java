package com.tailor.engine.blocks;

import java.util.List;

/**
 * {@code tailor blocks}' result (PHASE3_SPEC.md section 8). {@code projectsSection}/{@code
 * positions} keep their original single-section meaning ({@code projectsSection} is the first
 * {@code role: "projects"} section's heading, or null if there is none; {@code positions} is
 * every projects-role section's positions, concatenated in document order) — identical to the
 * old output whenever a resume has only one such section, which is every fixture before
 * {@code projects_synthetic_multi_section.docx}. {@code projectSections} is the same positions
 * broken out by their own originating section, so a resume with more than one {@code "projects"}
 * section (e.g. a headerless "Open-Source Contributions" list ahead of the real "Projects"
 * section) can be told apart in the report instead of silently merged.
 */
public record BlocksReport(
        List<Section> sections, String projectsSection, List<Position> positions,
        List<ProjectSectionReport> projectSections) {

    public record ProjectSectionReport(String heading, List<Position> positions) {
    }
}
