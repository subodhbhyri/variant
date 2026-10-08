package com.tailor.engine.generate;

import com.tailor.engine.blocks.Position;
import com.tailor.engine.blocks.PositionBuilder;
import com.tailor.engine.blocks.ProjectSections;
import com.tailor.engine.blocks.Section;
import com.tailor.engine.blocks.SectionDetector;
import com.tailor.engine.blocks.SectionRoles;
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
    private final List<String> projectPositionSections;
    private final List<ProjectSections.Entry> projectEntries;
    private final Map<Element, Slot> slotByElement;

    private SectionPositions(List<Position> jobPositions, List<ProjectSections.Entry> projectEntries,
            Map<Element, Slot> slotByElement) {
        this.jobPositions = jobPositions;
        this.projectEntries = projectEntries;
        this.projectPositions = ProjectSections.flatten(projectEntries);
        List<String> headings = new ArrayList<>();
        for (ProjectSections.Entry e : projectEntries) {
            for (int i = 0; i < e.positions().size(); i++) {
                headings.add(e.section().heading());
            }
        }
        this.projectPositionSections = headings;
        this.slotByElement = slotByElement;
    }

    /** The "experience" section's positions, in Phase 3 position order ({@code job-N} = index N). */
    public List<Position> jobPositions() {
        return jobPositions;
    }

    /** Every {@code "projects"}-role section's positions, concatenated in document order (not
     * filtered to swappable) — the same flattened order {@code "project-N"}/{@code "P"+N} ids use. */
    public List<Position> projectPositions() {
        return projectPositions;
    }

    /** {@link #projectPositions()}'s own originating section heading, index-for-index — so a
     * project position's home section (PHASE3/5: swaps and assembly never place a project outside
     * the section its position actually lives in) can be looked up by the same {@code N}. */
    public List<String> projectPositionSections() {
        return projectPositionSections;
    }

    /** Every {@code "projects"}-role section with its own positions, in document order — used to
     * decide a brand-new (no existing position) added project's home section. */
    public List<ProjectSections.Entry> projectSectionEntries() {
        return projectEntries;
    }

    /** Every bullet slot of the document these positions were read from, in document order (the same
     * {@link Slot}s, and the same DOM, as the positions' own paragraphs). */
    public List<Slot> slots() {
        return slotByElement.values().stream().sorted(java.util.Comparator.comparingInt(Slot::index)).toList();
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

    /** {@link FitLoop}'s render-check needs a real slot per needed line count to substitute a
     * candidate into: this position's own bullet slots, grouped by {@link OnboardReport.SlotReport#lines()},
     * restricted to editable ones (a job's locked bullets are never touched). */
    public Map<Integer, List<Integer>> slotIndicesByLineCount(Position position, OnboardReport report) {
        Map<Integer, OnboardReport.SlotReport> bySlotIndex = new HashMap<>();
        for (OnboardReport.SlotReport sr : report.slots()) {
            bySlotIndex.put(sr.index(), sr);
        }
        Map<Integer, List<Integer>> out = new HashMap<>();
        for (int idx : bulletSlotIndices(position)) {
            OnboardReport.SlotReport sr = bySlotIndex.get(idx);
            if (sr == null || !sr.editable() || sr.lines() == null) {
                continue;
            }
            out.computeIfAbsent(sr.lines(), k -> new ArrayList<>()).add(idx);
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

        List<Section> sections = SectionDetector.detect(doc, SectionRoles.of(onboardReport.sectionRoles()));
        Section experienceSection = sections.stream().filter(s -> "experience".equals(s.role())).findFirst()
                .orElse(null);
        List<Position> jobPositions = experienceSection == null ? List.of()
                : PositionBuilder.build(experienceSection, slotByElement::containsKey, slotByElement, lockReasonBySlotIndex);
        List<ProjectSections.Entry> projectEntries = ProjectSections.detect(
                sections, slotByElement::containsKey, slotByElement, lockReasonBySlotIndex);
        return new SectionPositions(jobPositions, projectEntries, slotByElement);
    }
}
