package com.tailor.engine.match;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.blocks.Position;
import com.tailor.engine.generate.SectionPositions;
import com.tailor.engine.onboard.OnboardReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PHASE5_SPEC.md sections 3-4: the job's slot line counts and the projects section's swappable
 * positions (each position's bullet-line-count shape, and whether its header shows a stack) —
 * measured on the user's own document by Phase 3's position detection; fixtures supply it
 * directly as {@code shapes.json}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Shapes(JobShape job, List<PositionShape> positions, List<PositionShape> lockedPositions) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JobShape(String id, List<Integer> slots) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PositionShape(String id, List<Integer> shape, boolean showsDetail) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    public static Shapes load(Path shapesJson) throws IOException {
        if (!Files.isRegularFile(shapesJson)) {
            throw new IOException("shapes file not found: " + shapesJson.toAbsolutePath());
        }
        return MAPPER.readValue(shapesJson.toFile(), Shapes.class);
    }

    /** Measures a live {@link Shapes} straight off the user's own document — the job's first
     * position (D2: job order never changes, only its bullets do) and every "projects" section
     * position, in Phase 3 position order ({@code "P0"}, {@code "P1"}, ...: the same index
     * {@link com.tailor.engine.blocks.BlockSwapper#swap} takes). {@code shows_detail} is whether
     * the header's own token template names a {@code DETAIL} field (the same check
     * {@code BlockSwapper.resolveDetail} makes before running its stack-fit search). */
    public static Shapes measure(Path normalizedDocx, OnboardReport report) throws Exception {
        SectionPositions positions = SectionPositions.detect(normalizedDocx, report);
        if (positions.jobPositions().isEmpty()) {
            throw new IllegalStateException("no job position detected");
        }
        Map<Integer, OnboardReport.SlotReport> bySlotIndex = new HashMap<>();
        for (OnboardReport.SlotReport sr : report.slots()) {
            bySlotIndex.put(sr.index(), sr);
        }

        Position job0 = positions.jobPositions().get(0);
        JobShape jobShape = new JobShape("job-0", linesOf(positions.bulletSlotIndices(job0), bySlotIndex));

        List<PositionShape> swappable = new ArrayList<>();
        List<PositionShape> locked = new ArrayList<>();
        List<Position> projectPositions = positions.projectPositions();
        for (int i = 0; i < projectPositions.size(); i++) {
            Position p = projectPositions.get(i);
            // An inline position's bullets aren't separate paragraph slots (bulletSlotIndices is
            // empty for them, by contract) — its own precomputed segment counts (section-count per
            // inline bullet group) are its "line count" for scoring/placement purposes, the same
            // value BlockSwapper.swapInline uses as targetL for each bullet.
            List<Integer> shape = "inline".equals(p.kind())
                    ? p.segmentsPerBullet()
                    : linesOf(positions.bulletSlotIndices(p), bySlotIndex);
            boolean showsDetail = p.header() != null && p.header().fields().contains("DETAIL");
            PositionShape ps = new PositionShape("P" + i, shape, showsDetail);
            (p.swappable() ? swappable : locked).add(ps);
        }
        return new Shapes(jobShape, swappable, locked);
    }

    private static List<Integer> linesOf(List<Integer> slotIndices, Map<Integer, OnboardReport.SlotReport> bySlotIndex) {
        List<Integer> out = new ArrayList<>(slotIndices.size());
        for (int idx : slotIndices) {
            OnboardReport.SlotReport sr = bySlotIndex.get(idx);
            out.add(sr == null ? null : sr.lines());
        }
        return out;
    }
}
