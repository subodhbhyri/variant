package com.tailor.engine.match;

import java.util.List;

/**
 * PHASE5_SPEC.md section 3-4: one candidate resume — {@code job} has one entry per job slot
 * ({@code null} means the original bullet is kept), {@code projects} one entry per swappable
 * position. {@code label} is null for resume #1 (set to {@code "best match"} only when returned
 * from {@link Alternatives#top3}); an alternative's project assignments never carry a {@code
 * stack} (only {@link Assembler#assemble} computes it — {@code reference/jd_ref.py}'s
 * {@code top3} reuses {@code assign_projects}' raw output for the "unused project" branch).
 */
public record AssembledResume(List<String> job, List<ProjectAssignment> projects, String label, Double total) {

    public record ProjectAssignment(String position, String project, List<Integer> bullets, double score,
            List<String> stack) {
    }
}
