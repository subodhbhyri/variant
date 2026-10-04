package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.blocks.LibraryProject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * P5-T8 operator tooling: {@code MatchBatchSummary}'s row-building and cache-against-previous
 * logic, exercised on the fixture JDs {@code FakeEmbedder} already reproduces exactly.
 */
class MatchBatchSummaryTest {

    @Test
    void topSkillsPicksTheFiveHighestWeightedInDeterministicOrder() throws Exception {
        Phase5TestSetup s = Phase5TestSetup.load();
        JobDescription platform = s.jds.get("platform");
        String top5 = MatchBatchSummary.topSkills(platform, 5);
        // platform's skills: Go/Kubernetes/Postgres/gRPC all 1.0, Bazel/Prometheus/Terraform 0.5, CI 0.3 —
        // ties break by name ascending, so the four 1.0-weight skills (alphabetical) come first.
        assertEquals("Go(1.0), Kubernetes(1.0), Postgres(1.0), gRPC(1.0), Bazel(0.5)", top5);
    }

    @Test
    void projectsSummaryListsPositionsInOrder() throws Exception {
        Phase5TestSetup s = Phase5TestSetup.load();
        JobDescription platform = s.jds.get("platform");
        AssembledResume resume =
                Assembler.assemble(s.shapes, s.jobCandidates(), s.library, platform, s.skills, s.embedder, Set.of());
        assertEquals("P0:forge, P1:harbor, P2:sprout, P3:quill", MatchBatchSummary.projectsSummary(resume));
    }

    @Test
    void alternativesSummaryJoinsLabelsSkippingResumeOne() throws Exception {
        Phase5TestSetup s = Phase5TestSetup.load();
        JobDescription platform = s.jds.get("platform");
        List<AssembledResume> resumes =
                Alternatives.top3(s.shapes, s.jobCandidates(), s.library, platform, s.skills, s.embedder);
        assertEquals(s.expected.jds().get("platform").resumes().get(1).label(),
                MatchBatchSummary.alternativesSummary(resumes));
    }

    @Test
    void alternativesSummaryIsNoneWhenOnlyResumeOneIsOffered() {
        assertEquals("(none)", MatchBatchSummary.alternativesSummary(List.of()));
    }

    @Test
    void cacheAgainstPreviousFindsThePlatformRewordingHit() throws Exception {
        Phase5TestSetup s = Phase5TestSetup.load();
        JobDescription platform = s.jds.get("platform");
        JobDescription reworded = s.jds.get("platform_reworded");
        JobDescription frontend = s.jds.get("frontend");

        List<JobDescription> previous = new ArrayList<>(List.of(platform, frontend));
        List<String> previousNames = new ArrayList<>(List.of("platform", "frontend"));

        String result = MatchBatchSummary.cacheAgainstPrevious(reworded, previous, previousNames, s.embedder);
        assertTrue(result.startsWith("HIT vs platform "), "expected a HIT against platform, got: " + result);
        assertTrue(result.contains("jaccard=1.0"), "expected jaccard=1.0, got: " + result);
    }

    @Test
    void cacheAgainstPreviousIsMissWithNoPriorMatch() throws Exception {
        Phase5TestSetup s = Phase5TestSetup.load();
        JobDescription platform = s.jds.get("platform");
        JobDescription data = s.jds.get("data");

        String result = MatchBatchSummary.cacheAgainstPrevious(
                data, List.of(platform), List.of("platform"), s.embedder);
        assertEquals("MISS", result);
    }

    /** PHASE5_SPEC.md section 9 (revision 4): a feasibility entry per library project, listing
     * the swappable positions whose shape it can fill -- same for every posting (no job
     * description involved), so it's checked once against the fixture's own real library/shapes
     * rather than per JD. */
    @Test
    void feasibilityListsEveryLibraryProjectAndTheMarkdownHasItsOwnHeading() throws Exception {
        Phase5TestSetup s = Phase5TestSetup.load();

        Map<String, List<String>> feasibility =
                MatchBatchSummary.feasibility(s.library, s.shapes.positions(), s.skills, s.embedder);

        assertEquals(s.library.stream().map(LibraryProject::id).collect(Collectors.toSet()), feasibility.keySet());
        for (var e : feasibility.entrySet()) {
            for (String positionId : e.getValue()) {
                assertTrue(s.shapes.positions().stream().anyMatch(p -> p.id().equals(positionId)),
                        e.getKey() + ": \"" + positionId + "\" is not one of this fixture's own position ids");
            }
        }

        String markdown = MatchBatchSummary.feasibilityMarkdown(feasibility);
        assertTrue(markdown.contains("## Library project feasibility"));
        for (LibraryProject p : s.library) {
            assertTrue(markdown.contains("| " + p.id() + " |"), "missing a row for " + p.id());
        }
    }

    @Test
    void toMarkdownHasHeaderAndOneRowPerEntry() {
        MatchBatchSummary.Row row = new MatchBatchSummary.Row(
                "platform", "Senior Backend Engineer, Build Platform", "Go(1.0)", "P0:forge", "(none)", "(none)", "MISS");
        String md = MatchBatchSummary.toMarkdown(List.of(row));
        assertTrue(md.startsWith("# Match batch summary"));
        assertTrue(md.contains("| platform | Senior Backend Engineer, Build Platform | Go(1.0) | P0:forge | (none) | (none) | MISS |"));
    }
}
