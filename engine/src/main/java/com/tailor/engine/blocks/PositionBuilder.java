package com.tailor.engine.blocks;

import com.tailor.engine.slots.Slot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;

/**
 * PHASE3_SPEC.md section 3: turns a section's blocks into positions, deciding
 * swappability in the order the spec's table lists reasons: the block's own
 * structural reason (from {@link BlockDetector}), then a Phase 1 lock on one
 * of its bullets (cheap — already computed, no render needed), then a header
 * parse failure, then (if all of that is clean) the header's token fields.
 *
 * <p>Not yet integrated: the render-based "shared_lines" lock (Phase 2's
 * {@code Locker}) is not checked here. No corpus position or fixture position
 * currently needs it (PHASE3_SPEC.md section 8's table shows no such position
 * reason for any of the 9 resumes or the fixture); it belongs with whichever
 * later step first needs a render pass over the projects section anyway,
 * rather than adding one here on spec alone.
 */
public final class PositionBuilder {

    private PositionBuilder() {
    }

    public static List<Position> build(Section projectsSection, java.util.function.Predicate<Element> isBullet,
            Map<Element, Slot> slotByBulletElement) {
        List<Block> blocks = BlockDetector.detect(projectsSection, isBullet);
        List<Position> positions = new ArrayList<>();
        for (Block b : blocks) {
            positions.add(toPosition(b, slotByBulletElement));
        }
        return positions;
    }

    private static Position toPosition(Block b, Map<Element, Slot> slotByBulletElement) {
        if (b.kind == Block.Kind.INLINE) {
            List<Integer> segmentsPerBullet = b.inlineBulletGroups.stream().map(List::size).toList();
            return new Position("inline", true, b.inlineBulletGroups.size(), null, null, segmentsPerBullet, b);
        }

        String reason = b.reason;

        if (reason == null) {
            for (Element bulletP : b.bulletParas) {
                Slot slot = slotByBulletElement.get(bulletP);
                if (slot != null && !slot.supported() && !slot.unsupportedReasons().isEmpty()) {
                    reason = slot.unsupportedReasons().get(0);
                    break;
                }
            }
        }

        HeaderParser.Result parsed = null;
        if (reason == null) {
            parsed = HeaderParser.parse(b.headerParas.get(0));
            if (parsed.reason() != null) {
                reason = parsed.reason();
            }
        }

        Position.HeaderInfo header = null;
        if (reason == null) {
            List<HeaderToken> template = HeaderTemplate.build(b.headerParas.get(0));
            List<String> fields = template.stream().map(t -> t.kind().name()).toList();
            HeaderParser.Parsed p = parsed.parsed();
            header = new Position.HeaderInfo(fields, p.detailIsList(), p.links().size(), p.dateMode());
        }

        return new Position("paragraph", reason == null, b.bulletParas.size(), reason, header, null, b);
    }
}
