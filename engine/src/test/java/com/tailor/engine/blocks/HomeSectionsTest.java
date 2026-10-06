package com.tailor.engine.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** An added project's home section must hold a swappable position, or the project is infeasible everywhere. */
class HomeSectionsTest {

    private static final Position SWAPPABLE =
            new Position("paragraph", true, 1, null, null, List.of(), null);
    private static final Position LOCKED =
            new Position("paragraph", false, 1, null, null, List.of(), null);

    @Test
    void aProjectHeadingWithNoSwappablePositionIsNotChosen() {
        List<ProjectSections.Entry> entries = List.of(
                new ProjectSections.Entry(new Section("Projects", "projects", List.of()), List.of()),
                new ProjectSections.Entry(new Section("Selected Projects", "projects", List.of()), List.of(SWAPPABLE)));

        assertEquals("Selected Projects", HomeSections.forAddedProject(entries));
    }

    @Test
    void aProjectHeadingWithOnlyLockedPositionsIsNotChosenEither() {
        List<ProjectSections.Entry> entries = List.of(
                new ProjectSections.Entry(new Section("Projects", "projects", List.of()), List.of(LOCKED)),
                new ProjectSections.Entry(new Section("Work", "projects", List.of()), List.of(SWAPPABLE, SWAPPABLE)));

        assertEquals("Work", HomeSections.forAddedProject(entries));
    }

    @Test
    void theFirstProjectHeadingWithASwappablePositionWins() {
        List<ProjectSections.Entry> entries = List.of(
                new ProjectSections.Entry(new Section("Personal Projects", "projects", List.of()), List.of(SWAPPABLE)),
                new ProjectSections.Entry(new Section("Projects", "projects", List.of()), List.of(SWAPPABLE)));

        assertEquals("Personal Projects", HomeSections.forAddedProject(entries));
    }

    @Test
    void noSwappablePositionAnywhereGivesNoHome() {
        List<ProjectSections.Entry> entries = List.of(
                new ProjectSections.Entry(new Section("Projects", "projects", List.of()), List.of(LOCKED)));

        assertNull(HomeSections.forAddedProject(entries));
    }
}
