package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.blocks.LibraryProject;
import com.tailor.engine.golden.Phase5Fixtures.ExpectedProjectAssignment;
import com.tailor.engine.golden.Phase5Fixtures.ExpectedResume;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A resume's "projects" positions can live in more than one {@code "projects"}-role section
 * (PHASE3_SPEC.md section 2), and a project only ever belongs to one of them (its "home
 * section"): an existing position's home is wherever it actually lives in the document; an added
 * project's home is fixed once, up front. {@link Assembler#assignProjects} (PHASE5_SPEC.md
 * section 4) must never place a project into a position outside its own home, even when doing so
 * would score higher — proven here by deliberately tagging the known-best (home-unconstrained)
 * choice for P0 as belonging to a section no position actually has, and confirming it's placed
 * nowhere rather than smuggled into P0 anyway.
 */
class AssemblerHomeSectionTest {

    @Test
    void neverPlacesAProjectOutsideItsHomeSection() throws Exception {
        Phase5TestSetup s = Phase5TestSetup.load();
        JobDescription jd = s.jds.get("platform");

        ExpectedResume expected = s.expected.jds().get("platform").resumes().get(0);
        ExpectedProjectAssignment bestForP0 = expected.projects().stream()
                .filter(p -> "P0".equals(p.position()))
                .findFirst().orElseThrow(() -> new AssertionError("fixture has no P0 assignment to test against"));
        String bestProjectId = bestForP0.project();

        // Every position's home is "A" -- there is no "B" position anywhere in this resume.
        List<Shapes.PositionShape> positions = new ArrayList<>();
        for (Shapes.PositionShape p : s.shapes.positions()) {
            positions.add(new Shapes.PositionShape(p.id(), p.shape(), p.showsDetail(), "A"));
        }

        // The known best (unconstrained) choice for P0 is tagged "B" -- a mismatch with every
        // position's actual home -- while every other library project stays eligible ("A").
        List<LibraryProject> library = new ArrayList<>();
        for (LibraryProject p : s.library) {
            String home = p.id().equals(bestProjectId) ? "B" : "A";
            library.add(new LibraryProject(p.id(), p.title(), p.detail(), p.links(), p.date(), p.bullets(), home));
        }

        List<AssembledResume.ProjectAssignment> result =
                Assembler.assignProjects(positions, library, jd, s.skills, s.embedder, Set.of());

        assertTrue(result.stream().noneMatch(a -> bestProjectId.equals(a.project())),
                "home-mismatched project " + bestProjectId + " must never be assigned to any position, "
                        + "even though it scores best for P0; got " + result);
        assertFalse(result.isEmpty(), "the other, home-valid library projects should still fill P0 instead");
    }
}
