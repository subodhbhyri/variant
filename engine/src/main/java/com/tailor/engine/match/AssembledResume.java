package com.tailor.engine.match;

import java.util.List;

/**
 * PHASE5_SPEC.md section 3-4: one candidate resume — {@code job} has one entry per job slot
 * ({@code null} means the original bullet is kept), {@code projects} one entry per swappable
 * position. {@code label} is null for resume #1 (set to {@code "best match"} only when returned
 * from {@link Alternatives#top3}); an alternative's project assignments never carry a {@code
 * stack} (only {@link Assembler#assemble} computes it — {@code reference/jd_ref.py}'s
 * {@code top3} reuses {@code assign_projects}' raw output for the "unused project" branch).
 *
 * <p>{@code degraded} (PHASE5_SPEC.md section 5, fail-soft assembly) is always empty except on a
 * fully-rendered resume #1: a position whose swap kept failing verification after 3 re-solves,
 * left at its own original (unswapped) content instead, with the reason recorded here —
 * {@link ResumeRenderer#renderFailSoft} is the only place that ever populates it.
 */
public record AssembledResume(List<String> job, List<ProjectAssignment> projects, String label, Double total,
        List<Degraded> degraded) {

    public AssembledResume(List<String> job, List<ProjectAssignment> projects, String label, Double total) {
        this(job, projects, label, total, List.of());
    }

    public record ProjectAssignment(String position, String project, List<Integer> bullets, double score,
            List<String> stack) {
    }

    public record Degraded(String position, String project, String reason) {
    }
}
