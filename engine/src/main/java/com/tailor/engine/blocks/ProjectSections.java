package com.tailor.engine.blocks;

import com.tailor.engine.slots.Slot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.w3c.dom.Element;

/**
 * A resume may have more than one {@code role: "projects"} section (PHASE3_SPEC.md section 2) —
 * e.g. a headerless "Open-Source Contributions" bullet list ahead of the real "Projects"
 * section, both vocabulary-matched to "projects". Every caller that used to take only the
 * <b>first</b> projects-role section (missing every position in any section after it) now goes
 * through here instead: every projects-role section is detected, each independently (its own
 * {@link PositionBuilder#build} call, never mixing paragraphs across sections), in document
 * order. {@link #flatten} concatenates their positions into the one externally-numbered list
 * ({@code "P0"}, {@code "P1"}, ...) {@code tailor swap}, {@code tailor blocks} and Phase 4/5's own
 * position indexing have always assumed — identical to the old single-section behavior when
 * there's exactly one such section, which is every existing fixture before this one.
 */
public final class ProjectSections {

    public record Entry(Section section, List<Position> positions) {
    }

    private ProjectSections() {
    }

    public static List<Entry> detect(List<Section> allSections, Predicate<Element> isBullet,
            Map<Element, Slot> slotByBulletElement, Map<Integer, String> lockReasonBySlotIndex) {
        List<Entry> out = new ArrayList<>();
        for (Section s : allSections) {
            if ("projects".equals(s.role())) {
                List<Position> positions = PositionBuilder.build(s, isBullet, slotByBulletElement, lockReasonBySlotIndex);
                out.add(new Entry(s, positions));
            }
        }
        return out;
    }

    public static List<Position> flatten(List<Entry> entries) {
        List<Position> out = new ArrayList<>();
        for (Entry e : entries) {
            out.addAll(e.positions());
        }
        return out;
    }
}
