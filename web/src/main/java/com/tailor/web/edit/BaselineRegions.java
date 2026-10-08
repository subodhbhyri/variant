package com.tailor.web.edit;

import com.tailor.engine.blocks.Position;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.generate.SectionPositions;
import com.tailor.engine.measure.AnchorMeasurer;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.measure.StoredBaseline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.slots.DocxBulletDetection;
import com.tailor.engine.slots.Slot;
import com.tailor.engine.verify.SlotEdit;
import com.tailor.engine.verify.Verifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * The regions of a whole-document check of a revision against the resume's stored onboarding baseline
 * (PHASE6_SPEC.md section 6): every bullet slot, and every project header or inline paragraph, with the text the
 * ORIGINAL (normalized) document had and the text the revision has now. This is what the engine's own final check of a
 * tailored resume does ({@code FinalVerifier}, PHASE5_SPEC.md 5.6), applied to a document that has been tailored AND
 * edited: whatever differs from the original (tailored bullets, swapped projects, the user's edits) is an edited
 * region held to its line count, and everything else must be exactly where the original had it, within 0.5 pt.
 * Because the comparison is always with the original and never with the previous snapshot, drift cannot add up
 * across revisions.
 */
final class BaselineRegions {

    /** The regions in document order, or {@code null} if the revision and the original no longer line up. */
    static List<Verifier.Region> build(Path normalizedDocx, Path revisedDocx, OnboardReport report,
            StoredBaseline baseline, Renderer renderer, Path work) throws Exception {
        List<Slot> baseSlots = DocxBulletDetection.detect(normalizedDocx);
        List<Slot> revisedSlots = DocxBulletDetection.detect(revisedDocx);
        if (baseSlots.size() != revisedSlots.size()) {
            return null;
        }
        List<PdfLines.Line> baseLines = baseline.lines();
        Map<Integer, Integer> lineCounts = AnchorMeasurer.measure(baseLines,
                baseSlots.stream().map(Slot::text).toList());
        List<PdfLines.Line> revisedLines = PdfLines.extract(renderer.render(revisedDocx, work));

        List<IndexedRegion> ordered = new ArrayList<>();
        for (int i = 0; i < baseSlots.size(); i++) {
            Slot base = baseSlots.get(i);
            String before = base.text();
            String after = revisedSlots.get(i).text();
            Verifier.Region region;
            if (before.equals(after)) {
                region = Verifier.Region.unchanged(before);
            } else if (SnapshotSlots.isBlank(after)) {
                region = new Verifier.Region(before, after, lineCounts.get(i), SlotEdit.BLANKED);
            } else {
                region = new Verifier.Region(before, after, lineCounts.get(i),
                        paddedOrNot(revisedLines, after, lineCounts.get(i)));
            }
            ordered.add(new IndexedRegion(bodyIndex(base.element()), region));
        }

        SectionPositions basePositions = SectionPositions.detect(normalizedDocx, report);
        SectionPositions revisedPositions = SectionPositions.detect(revisedDocx, report);
        List<Position> baseProjects = basePositions.projectPositions();
        List<Position> revisedProjects = revisedPositions.projectPositions();
        if (baseProjects.size() != revisedProjects.size()) {
            return null;
        }
        for (int i = 0; i < baseProjects.size(); i++) {
            Position p = baseProjects.get(i);
            Position q = revisedProjects.get(i);
            boolean inline = "inline".equals(p.kind());
            Element baseParagraph = inline ? p.block().inlineHeaderParagraph : p.block().headerParas.get(0);
            Element revisedParagraph = inline ? q.block().inlineHeaderParagraph : q.block().headerParas.get(0);
            String before = DomUtil.allText(baseParagraph);
            String after = DomUtil.allText(revisedParagraph);
            if (before.equals(after)) {
                continue; // unchanged: its lines are fixed lines
            }
            int[] span = AnchorMeasurer.measureSpans(baseLines, List.of(before)).get(0);
            if (span == null) {
                return null;
            }
            int target = span[1] - span[0] + 1;
            SlotEdit edit = inline ? paddedOrNot(revisedLines, after, target) : SlotEdit.SUBSTITUTED;
            ordered.add(new IndexedRegion(bodyIndex(baseParagraph), new Verifier.Region(before, after, target, edit)));
        }
        ordered.sort(Comparator.comparingInt(IndexedRegion::bodyIndex));
        return ordered.stream().map(IndexedRegion::region).toList();
    }

    /** PADDED if the new text takes fewer lines than the original held (the rest is padding), else SUBSTITUTED. */
    private static SlotEdit paddedOrNot(List<PdfLines.Line> lines, String text, Integer target) {
        int[] span = AnchorMeasurer.measureSpans(lines, List.of(text)).get(0);
        return span != null && target != null && span[1] - span[0] + 1 < target ? SlotEdit.PADDED : SlotEdit.SUBSTITUTED;
    }

    private record IndexedRegion(int bodyIndex, Verifier.Region region) {
    }

    private static int bodyIndex(Element target) {
        Document doc = target.getOwnerDocument();
        Element body = DomUtil.firstChild(doc.getDocumentElement(), "body");
        List<Element> children = DomUtil.elementChildren(body);
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i) == target) {
                return i;
            }
        }
        throw new IllegalStateException("paragraph is not a direct child of the document body");
    }

    private BaselineRegions() {
    }
}
