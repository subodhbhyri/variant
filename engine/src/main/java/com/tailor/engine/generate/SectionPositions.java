package com.tailor.engine.generate;

import com.tailor.engine.blocks.Position;
import com.tailor.engine.blocks.PositionBuilder;
import com.tailor.engine.blocks.Section;
import com.tailor.engine.blocks.SectionDetector;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.numbering.NumberingResolver;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.slots.BulletDetector;
import com.tailor.engine.slots.Slot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * PHASE4_SPEC.md section 2 (step 4.2): the structural side of "what to generate" — the
 * "experience" section's positions (jobs, D1: never swapped, never added) and the "projects"
 * section's positions (only the swappable ones feed the library, Phase 3 section 7), each
 * resolved to its own bullets' {@link Slot} indices so an {@link OnboardReport.SlotReport}'s
 * {@code lines}/{@code editable}/{@code hintChars} can be looked up per bullet.
 */
public final class SectionPositions {

    private final List<Position> jobPositions;
    private final List<Position> projectPositions;
    private final Map<Element, Slot> slotByElement;

    private SectionPositions(List<Position> jobPositions, List<Position> projectPositions,
            Map<Element, Slot> slotByElement) {
        this.jobPositions = jobPositions;
        this.projectPositions = projectPositions;
        this.slotByElement = slotByElement;
    }

    /** The "experience" section's positions, in Phase 3 position order ({@code job-N} = index N). */
    public List<Position> jobPositions() {
        return jobPositions;
    }

    /** The "projects" section's positions, in Phase 3 position order (not filtered to swappable). */
    public List<Position> projectPositions() {
        return projectPositions;
    }

    /** A paragraph-kind position's bullets, resolved to their {@link Slot#index()} in document
     * order; empty for an inline-kind position (its bullets aren't separate slots). */
    public List<Integer> bulletSlotIndices(Position position) {
        List<Integer> out = new ArrayList<>();
        for (Element bulletP : position.block().bulletParas) {
            Slot slot = slotByElement.get(bulletP);
            if (slot != null) {
                out.add(slot.index());
            }
        }
        return out;
    }

    public static SectionPositions detect(Path normalizedDocx, OnboardReport onboardReport) throws Exception {
        DocxPackage pkg = DocxPackage.open(normalizedDocx);
        Document doc = SafeXml.parse(pkg.readPart("word/document.xml"));
        Element numberingRoot = pkg.hasPart("word/numbering.xml")
                ? SafeXml.parse(pkg.readPart("word/numbering.xml")).getDocumentElement() : null;
        Element stylesRoot = pkg.hasPart("word/styles.xml")
                ? SafeXml.parse(pkg.readPart("word/styles.xml")).getDocumentElement() : null;
        NumberingResolver resolver = new NumberingResolver(numberingRoot, stylesRoot);
        List<Slot> slots = BulletDetector.detect(doc, resolver);
        Map<Element, Slot> slotByElement = new IdentityHashMap<>();
        for (Slot s : slots) {
            slotByElement.put(s.element(), s);
        }

        Map<Integer, String> lockReasonBySlotIndex = new HashMap<>();
        for (OnboardReport.SlotReport sr : onboardReport.slots()) {
            if (!sr.editable()) {
                lockReasonBySlotIndex.put(sr.index(), sr.lockReason());
            }
        }

        List<Section> sections = SectionDetector.detect(doc);
        Section experienceSection = sections.stream().filter(s -> "experience".equals(s.role())).findFirst()
                .orElse(null);
        Section projectsSection = sections.stream().filter(s -> "projects".equals(s.role())).findFirst()
                .orElse(null);
        List<Position> jobPositions = experienceSection == null ? List.of()
                : PositionBuilder.build(experienceSection, slotByElement::containsKey, slotByElement, lockReasonBySlotIndex);
        List<Position> projectPositions = projectsSection == null ? List.of()
                : PositionBuilder.build(projectsSection, slotByElement::containsKey, slotByElement, lockReasonBySlotIndex);
        return new SectionPositions(jobPositions, projectPositions, slotByElement);
    }
}
