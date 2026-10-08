package com.tailor.engine.layout;

import com.tailor.engine.blocks.Position;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.generate.SectionPositions;
import com.tailor.engine.measure.AnchorMeasurer;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.slots.Slot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;

/**
 * Where everything is on the page (PHASE6_SPEC.md revision 3, for the UI): for a document and its render, the pages'
 * sizes in PDF points and, for every bullet slot and every job and project position, the page and the box of each of
 * its lines, whether it can be edited and why not. The boxes are the engine's own measurement of the rendered PDF
 * ({@link PdfLines#extractLayout}); a slot or position is tied to its lines the way the engine finds them everywhere
 * else, by anchoring the document's own text in the rendered lines ({@link AnchorMeasurer}). Nothing is estimated.
 *
 * <p>Coordinates are PDF points from the page's top-left corner. No resume text is in the result.
 */
public final class PageLayout {

    /** A line box (see {@link PdfLines.LineBox}). */
    public record Box(int page, double x, double y, double w, double h) {
    }

    /** One bullet. {@code lines} is empty when nothing of it shows (a blanked bullet). */
    public record SlotLayout(int slot, boolean editable, String lockReason, Integer lineCount, List<Box> lines) {
    }

    /**
     * A job or project position: {@code id} is {@code job-N} or {@code PN}; {@code lines} are all the lines from its
     * header to its last bullet; {@code slots} its bullets' slot indices (none for an inline position, whose bullets
     * are part of its one paragraph); {@code swappable}/{@code reason} are the engine's own verdict on swapping it.
     */
    public record PositionLayout(String id, String kind, String block, boolean swappable, String reason,
            List<Integer> slots, List<Box> lines) {
    }

    public record Layout(List<PdfLines.PageSize> pages, List<SlotLayout> slots, List<PositionLayout> positions) {
    }

    private PageLayout() {
    }

    public static Layout build(Path docx, Path pdf, OnboardReport report) throws Exception {
        PdfLines.Layout rendered = PdfLines.extractLayout(pdf);
        SectionPositions positions = SectionPositions.detect(docx, report);
        List<Slot> slots = positions.slots(); // same DOM as the positions' paragraphs

        Map<Integer, OnboardReport.SlotReport> reportBySlot = new HashMap<>();
        for (OnboardReport.SlotReport sr : report.slots()) {
            reportBySlot.put(sr.index(), sr);
        }

        // Every piece of text the engine can anchor, in document order: bullets, and the paragraphs of every header.
        Map<Element, Integer> order = documentOrder(slots, positions);
        List<Region> regions = new ArrayList<>();
        for (Slot s : slots) {
            regions.add(new Region(order.get(s.element()), "slot", s.index(), s.text()));
        }
        List<Position> jobs = positions.jobPositions();
        List<Position> projects = positions.projectPositions();
        for (int i = 0; i < jobs.size(); i++) {
            addHeaders(regions, order, "job", i, jobs.get(i));
        }
        for (int i = 0; i < projects.size(); i++) {
            addHeaders(regions, order, "project", i, projects.get(i));
        }
        regions.removeIf(r -> r.order() == null);
        regions.sort(java.util.Comparator.comparingInt(Region::order).thenComparing(Region::kind));

        // One pass in document order, as the Verifier anchors a document's regions.
        List<String> texts = regions.stream().map(Region::text).toList();
        Map<Integer, int[]> spans = AnchorMeasurer.measureSpans(rendered.lines(), texts);
        Map<String, int[]> slotSpan = new HashMap<>();
        Map<String, List<int[]>> headerSpans = new HashMap<>();
        for (int i = 0; i < regions.size(); i++) {
            int[] span = spans.get(i);
            if (span == null) {
                continue;
            }
            Region r = regions.get(i);
            if ("slot".equals(r.kind())) {
                slotSpan.put("slot-" + r.id(), span);
            } else {
                headerSpans.computeIfAbsent(r.kind() + "-" + r.id(), k -> new ArrayList<>()).add(span);
            }
        }

        List<SlotLayout> slotLayouts = new ArrayList<>();
        for (Slot s : slots) {
            OnboardReport.SlotReport sr = reportBySlot.get(s.index());
            int[] span = slotSpan.get("slot-" + s.index());
            slotLayouts.add(new SlotLayout(s.index(), sr != null && sr.editable(), sr == null ? null : sr.lockReason(),
                    span == null ? null : span[1] - span[0] + 1, span == null ? List.of() : boxes(rendered, span[0], span[1])));
        }

        List<PositionLayout> positionLayouts = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            positionLayouts.add(position("job-" + i, "job", jobs.get(i), positions, headerSpans.get("job-" + i), slotSpan, rendered));
        }
        for (int i = 0; i < projects.size(); i++) {
            positionLayouts.add(position("P" + i, "project", projects.get(i), positions, headerSpans.get("project-" + i),
                    slotSpan, rendered));
        }
        return new Layout(rendered.pages(), slotLayouts, positionLayouts);
    }

    private record Region(Integer order, String kind, int id, String text) {
    }

    private static void addHeaders(List<Region> regions, Map<Element, Integer> order, String kind, int index, Position p) {
        List<Element> paragraphs = new ArrayList<>(p.block().headerParas);
        if (p.block().inlineHeaderParagraph != null) {
            paragraphs.add(p.block().inlineHeaderParagraph);
        }
        for (Element paragraph : paragraphs) {
            String text = DomUtil.allText(paragraph);
            if (!text.isBlank()) {
                regions.add(new Region(order.get(paragraph), kind, index, text));
            }
        }
    }

    /** The position of every paragraph of interest in the document (tables and text boxes included). */
    private static Map<Element, Integer> documentOrder(List<Slot> slots, SectionPositions positions) {
        Element any = !slots.isEmpty() ? slots.get(0).element() : null;
        Map<Element, Integer> order = new IdentityHashMap<>();
        if (any == null) {
            return order;
        }
        Element body = DomUtil.firstChild(any.getOwnerDocument().getDocumentElement(), "body");
        int i = 0;
        for (Element p : DomUtil.descendants(body, "p")) {
            order.put(p, i++);
        }
        return order;
    }

    private static PositionLayout position(String id, String kind, Position p, SectionPositions positions,
            List<int[]> headerSpans, Map<String, int[]> slotSpan, PdfLines.Layout rendered) {
        List<Integer> slotIndices = positions.bulletSlotIndices(p);
        int first = Integer.MAX_VALUE;
        int last = -1;
        if (headerSpans != null) {
            for (int[] span : headerSpans) {
                first = Math.min(first, span[0]);
                last = Math.max(last, span[1]);
            }
        }
        for (int slot : slotIndices) {
            int[] span = slotSpan.get("slot-" + slot);
            if (span != null) {
                first = Math.min(first, span[0]);
                last = Math.max(last, span[1]);
            }
        }
        String block = p.block().inlineHeaderParagraph != null ? "inline" : "paragraph";
        return new PositionLayout(id, kind, block, p.swappable(), p.reason(), slotIndices,
                last < 0 ? List.of() : boxes(rendered, first, last));
    }

    private static List<Box> boxes(PdfLines.Layout rendered, int from, int to) {
        List<Box> out = new ArrayList<>();
        for (int k = from; k <= to && k < rendered.boxes().size(); k++) {
            PdfLines.LineBox b = rendered.boxes().get(k);
            out.add(new Box(b.pageIndex(), round(b.x()), round(b.y()), round(b.w()), round(b.h())));
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
