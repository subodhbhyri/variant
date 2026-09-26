package com.tailor.engine.blocks;

import java.util.List;

/** {@code tailor blocks}' result (PHASE3_SPEC.md section 8). */
public record BlocksReport(List<Section> sections, String projectsSection, List<Position> positions) {
}
