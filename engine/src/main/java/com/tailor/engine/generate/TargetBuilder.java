package com.tailor.engine.generate;

import com.tailor.engine.blocks.Position;
import com.tailor.engine.onboard.OnboardReport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * PHASE4_SPEC.md section 2 (step 4.2): what to generate for a job or the project library —
 * the line counts needed, how many candidates, and each length's character budget (the median
 * Phase 1 calibration hint of the relevant slots at that line count).
 */
public final class TargetBuilder {

    public record SectionTarget(
            String kind,
            List<Integer> lineCounts,
            int candidateCount,
            Map<Integer, Integer> budgetCharsByLineCount) {
    }

    private TargetBuilder() {
    }

    /**
     * One job position (D1: a job's bullets tailor in place, only its editable slots count).
     * PHASE4_SPEC.md section 2 (revision 2): {@code DETAILED} asks for up to 2 x the job's
     * editable slots (min 4); {@code EXISTING_ONLY} asks for exactly one candidate per editable
     * bullet (ids {@code b0}, {@code b1}, ... — chosen by the model from the prompt's own
     * wording, not generated here).
     */
    public static SectionTarget forJob(Position jobPosition, OnboardReport report, SectionPositions positions,
            String mode) {
        Map<Integer, OnboardReport.SlotReport> bySlotIndex = indexReports(report);
        List<Integer> slotIndices = positions.bulletSlotIndices(jobPosition);

        List<Integer> lines = new ArrayList<>();
        Map<Integer, List<Integer>> hintsByLine = new TreeMap<>();
        int editableCount = 0;
        for (int idx : slotIndices) {
            OnboardReport.SlotReport sr = bySlotIndex.get(idx);
            if (sr == null || !sr.editable() || sr.lines() == null) {
                continue;
            }
            editableCount++;
            lines.add(sr.lines());
            if (sr.hintChars() != null) {
                hintsByLine.computeIfAbsent(sr.lines(), k -> new ArrayList<>()).add(sr.hintChars());
            }
        }
        List<Integer> lineCounts = new ArrayList<>(new TreeSet<>(lines));
        int candidateCount = "EXISTING_ONLY".equals(mode) ? editableCount : Math.max(4, 2 * editableCount);
        return new SectionTarget("job", lineCounts, candidateCount, medianBudgets(hintsByLine, lineCounts));
    }

    /** All swappable project positions, pooled (Phase 3 section 7: the library, not one position). */
    public static SectionTarget forProjects(OnboardReport report, SectionPositions positions) {
        Map<Integer, OnboardReport.SlotReport> bySlotIndex = indexReports(report);
        List<Position> swappable = positions.projectPositions().stream().filter(Position::swappable).toList();

        List<Integer> lines = new ArrayList<>();
        Map<Integer, List<Integer>> hintsByLine = new TreeMap<>();
        int maxBullets = 0;
        for (Position p : swappable) {
            maxBullets = Math.max(maxBullets, p.bullets());
            for (int idx : positions.bulletSlotIndices(p)) {
                OnboardReport.SlotReport sr = bySlotIndex.get(idx);
                if (sr == null || sr.lines() == null) {
                    continue;
                }
                lines.add(sr.lines());
                if (sr.hintChars() != null) {
                    hintsByLine.computeIfAbsent(sr.lines(), k -> new ArrayList<>()).add(sr.hintChars());
                }
            }
        }
        List<Integer> lineCounts = new ArrayList<>(new TreeSet<>(lines));
        int bulletsPerCandidate = swappable.isEmpty() ? 0 : maxBullets + 1;
        return new SectionTarget("project", lineCounts, bulletsPerCandidate, medianBudgets(hintsByLine, lineCounts));
    }

    private static Map<Integer, OnboardReport.SlotReport> indexReports(OnboardReport report) {
        Map<Integer, OnboardReport.SlotReport> m = new HashMap<>();
        for (OnboardReport.SlotReport sr : report.slots()) {
            m.put(sr.index(), sr);
        }
        return m;
    }

    /** Absent for a line count with no hint data at all — a missing budget, not a zero one. */
    private static Map<Integer, Integer> medianBudgets(Map<Integer, List<Integer>> hintsByLine, List<Integer> lineCounts) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (int lineCount : lineCounts) {
            List<Integer> hints = hintsByLine.get(lineCount);
            if (hints == null || hints.isEmpty()) {
                continue;
            }
            out.put(lineCount, median(hints));
        }
        return out;
    }

    private static int median(List<Integer> values) {
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (int) Math.round((sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0);
    }
}
