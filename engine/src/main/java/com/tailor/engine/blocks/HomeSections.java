package com.tailor.engine.blocks;

import java.util.List;
import java.util.Locale;

/** Where a brand-new added project lives (PHASE4_SPEC.md section 2, added projects). */
public final class HomeSections {

    private HomeSections() {
    }

    /**
     * The home section for an added project: the first {@code "projects"}-role section, in document
     * order, whose heading contains "project" and which holds at least one swappable position; if
     * none does, the section with the most positions (first in document order on a tie); null when
     * no section holds any position. A section with no swappable position can't be a home: a project
     * there would be infeasible at every position, so it is never chosen.
     */
    public static String forAddedProject(List<ProjectSections.Entry> entries) {
        for (ProjectSections.Entry e : entries) {
            boolean hasSwappable = e.positions().stream().anyMatch(Position::swappable);
            if (hasSwappable && e.section().heading().toLowerCase(Locale.ROOT).contains("project")) {
                return e.section().heading();
            }
        }
        String best = null;
        int bestCount = 0;
        for (ProjectSections.Entry e : entries) {
            long swappable = e.positions().stream().filter(Position::swappable).count();
            if (swappable > bestCount) {
                bestCount = (int) swappable;
                best = e.section().heading();
            }
        }
        return best;
    }
}
