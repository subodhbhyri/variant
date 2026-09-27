package com.tailor.engine.blocks;

import com.tailor.engine.slots.Slot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;

/**
 * PHASE3_SPEC.md section 3: turns a section's blocks into positions, deciding
 * swappability in the order the spec's table lists reasons: the block's own
 * structural reason (from {@link BlockDetector}), then any Phase 1/2 lock on
 * one of its bullets, then a header parse failure, then (if all of that is
 * clean) the header's token fields.
 *
 * <p>The Phase 1/2 lock check is a single lookup into the caller-supplied
 * onboarding lock map (built from {@code OnboardReport.slots()}, or however
 * else the caller re-derives it — see {@link BlockSwapper}'s own fresh
 * {@code Locker} run): every non-editable slot index maps to its reason,
 * covering both a cheap Phase 1 reason (hyperlink, tab_in_text, a run-level
 * break, …) and the render-based Phase 2 {@code shared_lines} lock uniformly,
 * exactly as {@code OnboardPipeline} already merges them for onboarding's own
 * report. A slot missing from the map is editable.
 */
public final class PositionBuilder {

    private PositionBuilder() {
    }

    public static List<Position> build(Section projectsSection, java.util.function.Predicate<Element> isBullet,
            Map<Element, Slot> slotByBulletElement, Map<Integer, String> lockReasonBySlotIndex) {
        List<Block> blocks = BlockDetector.detect(projectsSection, isBullet);
        List<Position> positions = new ArrayList<>();
        for (Block b : blocks) {
            positions.add(toPosition(b, slotByBulletElement, lockReasonBySlotIndex));
        }
        return positions;
    }

    private static Position toPosition(Block b, Map<Element, Slot> slotByBulletElement,
            Map<Integer, String> lockReasonBySlotIndex) {
        if (b.kind == Block.Kind.INLINE) {
            List<Integer> segmentsPerBullet = b.inlineBulletGroups.stream().map(List::size).toList();
            return new Position("inline", true, b.inlineBulletGroups.size(), null, null, segmentsPerBullet, b);
        }

        String reason = b.reason;

        if (reason == null) {
            for (Element bulletP : b.bulletParas) {
                Slot slot = slotByBulletElement.get(bulletP);
                if (slot == null) {
                    continue;
                }
                if (!slot.supported() && !slot.unsupportedReasons().isEmpty()) {
                    reason = slot.unsupportedReasons().get(0);
                    break;
                }
                String lockReason = lockReasonBySlotIndex.get(slot.index());
                if (lockReason != null) {
                    reason = lockReason;
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
