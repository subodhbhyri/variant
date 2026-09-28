package com.tailor.engine.generate;

import com.tailor.engine.render.Renderer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Everything {@link FitLoop} needs for one section (PHASE4_SPEC.md sections 2-6): the prompt
 * inputs (fields, current bullets, raw text, lengths, candidate count, sources for the guard)
 * plus the render-check inputs (the baseline docx and, for each needed line count, the slot
 * indices real enough to substitute a candidate into and measure).
 *
 * <p>{@code lineCounts} is the distinct lengths to generate ({@code {1, 2}} for Jane Doe's
 * job-0); {@code slotLineCounts} is the job's own bullets' raw, per-slot line counts in slot
 * order ({@code [2, 2, 1]}) — reporting only, carried straight through to {@link
 * FitLoop.FitLoopResult#slotLineCounts()}.
 */
public record SectionContext(
        String sectionId,
        String kind,
        String mode,
        List<PromptBuilder.FieldLine> fields,
        List<String> currentBullets,
        String rawText,
        List<String> sourceTexts,
        List<Integer> lineCounts,
        List<Integer> slotLineCounts,
        int candidateCount,
        Map<Integer, Integer> budgetCharsByLineCount,
        Map<Integer, List<Integer>> slotIndicesByLineCount,
        Path baselineDocx,
        Renderer renderer) {
}
