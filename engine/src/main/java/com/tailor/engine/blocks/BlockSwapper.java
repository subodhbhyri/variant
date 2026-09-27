package com.tailor.engine.blocks;

import com.tailor.engine.calibrate.BatchValidator;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.docx.XmlSerialize;
import com.tailor.engine.edit.Padder;
import com.tailor.engine.edit.Substituter;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.layout.Locker;
import com.tailor.engine.measure.AnchorMeasurer;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.numbering.NumberingResolver;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.slots.BulletDetector;
import com.tailor.engine.slots.BulletText;
import com.tailor.engine.slots.Slot;
import com.tailor.engine.verify.SlotEdit;
import com.tailor.engine.verify.Verifier;
import com.tailor.engine.verify.VerifyReport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * PHASE3_SPEC.md section 7: puts a library project into a position. Every
 * call is self-contained — it copies {@code basePkg} (never mutates the
 * caller's copy), re-detects sections/blocks/positions fresh from that copy,
 * and (only on {@link SwapOutcome#OK}) saves the assembled result to {@code
 * outputDocx}. That single fresh-parse-per-call rule is what keeps a header
 * stack-fit candidate, a bullet-variant probe render, and the final assembly
 * from ever sharing DOM nodes across different {@link Document} instances
 * (appending a node cloned from one parsed document into another throws).
 *
 * <p>{@code positionIndex} is the index into {@link PositionBuilder#build}'s
 * result for the resume's "projects" section, detected fresh here — the
 * caller picks it from an earlier {@code tailor blocks}-style detection pass
 * over the same, unmodified source bytes.
 */
public final class BlockSwapper {

    public record Result(SwapOutcome outcome, Integer stackItemsKept, Integer paddedBullets, String detail) {
        static Result of(SwapOutcome outcome) {
            return new Result(outcome, null, null, null);
        }

        static Result verifyFailed(String detail) {
            return new Result(SwapOutcome.VERIFY_FAILED, null, null, detail);
        }
    }

    /** One bullet slot's edit, for the final production-{@link Verifier} region list. */
    private record BulletEdit(String afterText, int targetLines, SlotEdit edit) {
    }

    private record Located(DocxPackage pkg, Document doc, Position position, List<Slot> slots,
                            Map<Element, Slot> slotByElement, Path baselineDocx, Path baselinePdf,
                            List<PdfLines.Line> baselineLines) {
    }

    record StackFit(String detail, Integer itemsKept) {
    }

    private BlockSwapper() {
    }

    public static Result swap(DocxPackage basePkg, int positionIndex, LibraryProject project,
            Renderer renderer, FontMap fontMap, Path workDir, Path outputDocx) throws Exception {
        Located base = locate(basePkg, positionIndex, renderer, workDir);
        Position position = base.position();

        if (project.bullets().size() < position.bullets()) {
            return Result.of(SwapOutcome.INSUFFICIENT_BULLETS);
        }
        for (LibraryProject.Link link : linksOrEmpty(project)) {
            if (!LinkValidator.isValid(link.url())) {
                return Result.of(SwapOutcome.INVALID_LINK);
            }
        }

        Result result = "inline".equals(position.kind())
                ? swapInline(base, project, renderer, fontMap, workDir)
                : swapParagraph(base, project, renderer, fontMap, workDir);

        if (result.outcome() == SwapOutcome.OK) {
            base.pkg().save(outputDocx);
        }
        return result;
    }

    // --- paragraph-kind positions -------------------------------------------------------------

    private static Result swapParagraph(Located base, LibraryProject project, Renderer renderer, FontMap fontMap,
            Path workDir) throws Exception {
        Position position = base.position();
        Document doc = base.doc();
        DocxPackage pkg = base.pkg();
        Element header = position.block().headerParas.get(0);
        List<HeaderToken> template = HeaderTemplate.build(header);
        boolean hasDetailToken = template.stream().anyMatch(t -> t.kind() == HeaderToken.Kind.DETAIL);
        boolean isTabDate = "tab".equals(position.header().dateMode());
        int headerBodyIndex = bodyChildIndex(doc, header);

        Path baselineDocx = base.baselineDocx();
        Path baselinePdf = base.baselinePdf();
        List<PdfLines.Line> baselineLines = base.baselineLines();
        String originalHeaderText = DomUtil.allText(header);
        int[] span0 = AnchorMeasurer.measureSpans(baselineLines, List.of(originalHeaderText)).get(0);
        if (span0 == null) {
            throw new IllegalStateException("could not anchor the position's own header text on its own render");
        }
        int targetHeaderLines = span0[1] - span0[0] + 1;

        Double dateEdgeTwips = null;
        if (isTabDate) {
            int leftMarginTwips = DateTabConverter.leftMarginTwips(doc);
            dateEdgeTwips = DateEdgeMeasurer.measureRightEdge(baselinePdf, originalHeaderText, leftMarginTwips)
                    .map(DateEdgeMeasurer.Measurement::rightEdgeTwips).orElse(null);
        }

        StackFit resolved = resolveDetail(pkg, headerBodyIndex, project, hasDetailToken, targetHeaderLines,
                isTabDate, dateEdgeTwips, renderer, workDir);
        if (resolved == null) {
            return Result.of(SwapOutcome.HEADER_TOO_LONG);
        }
        String finalDetail = resolved.detail();
        Integer stackItemsKept = resolved.itemsKept();

        List<String> allSlotTexts = base.slots().stream().map(Slot::text).toList();
        Map<Integer, Integer> allLineCounts = new HashMap<>(AnchorMeasurer.measure(baselineLines, allSlotTexts));

        List<Element> bulletParas = position.block().bulletParas;
        Map<Integer, BulletText> chosenBySlotIndex = new LinkedHashMap<>();
        for (int j = 0; j < bulletParas.size(); j++) {
            Slot slot = base.slotByElement().get(bulletParas.get(j));
            if (slot == null) {
                throw new IllegalStateException("bullet " + j + " of the position has no detected slot");
            }
            Integer targetL = allLineCounts.get(slot.index());
            if (targetL == null) {
                throw new IllegalStateException("bullet " + j + " could not be measured on the baseline render");
            }
            Map<String, String> variants = project.bullets().get(j);
            String variantText = variants == null ? null : variants.get(String.valueOf(targetL));
            if (variantText == null) {
                return Result.of(SwapOutcome.MISSING_VARIANT);
            }
            chosenBySlotIndex.put(slot.index(), new BulletText(variantText, List.of()));
        }

        Map<Integer, List<BulletText>> candidatesPerSlot = new LinkedHashMap<>();
        for (var e : chosenBySlotIndex.entrySet()) {
            candidatesPerSlot.put(e.getKey(), List.of(e.getValue()));
        }
        List<BatchValidator.CandidateResult> bulletResults = BatchValidator.validate(
                baselineDocx, renderer, allLineCounts, Map.of(), candidatesPerSlot, workDir);

        Map<Integer, Integer> padBySlotIndex = new LinkedHashMap<>();
        for (var r : bulletResults) {
            switch (r.outcome()) {
                case FITS -> {
                }
                case FITS_WITH_PADDING -> padBySlotIndex.put(r.slotIndex(),
                        allLineCounts.get(r.slotIndex()) - r.measuredLines());
                default -> {
                    return Result.of(SwapOutcome.BULLET_TOO_LONG);
                }
            }
        }

        // --- Final assembly, into the (already-copied) working doc/pkg ---
        RelationshipWriter relWriter = RelationshipWriter.forPackage(pkg);
        HeaderRenderer.NewFields headerFields = new HeaderRenderer.NewFields(
                project.title(), finalDetail, toNewLinks(project.links()), project.date());
        HeaderRenderer.render(header, template, headerFields, relWriter::addHyperlink);
        if (isTabDate && dateEdgeTwips != null) {
            DateTabConverter.rightAlignDateTab(doc, header, dateEdgeTwips);
        }
        for (int j = 0; j < bulletParas.size(); j++) {
            Slot slot = base.slotByElement().get(bulletParas.get(j));
            Substituter.substitute(bulletParas.get(j), chosenBySlotIndex.get(slot.index()));
            Integer pad = padBySlotIndex.get(slot.index());
            if (pad != null && pad > 0) {
                Padder.pad(bulletParas.get(j), pad);
            }
        }
        relWriter.flush();
        pkg.writePart("word/document.xml", XmlSerialize.toBytes(doc));

        Map<Integer, BulletEdit> editBySlotIndex = new LinkedHashMap<>();
        for (var e : chosenBySlotIndex.entrySet()) {
            int slotIdx = e.getKey();
            Integer pad = padBySlotIndex.get(slotIdx);
            SlotEdit edit = (pad != null && pad > 0) ? SlotEdit.PADDED : SlotEdit.SUBSTITUTED;
            editBySlotIndex.put(slotIdx, new BulletEdit(e.getValue().text(), allLineCounts.get(slotIdx), edit));
        }
        Verifier.Region headerRegion = new Verifier.Region(
                originalHeaderText, DomUtil.allText(header), targetHeaderLines, SlotEdit.SUBSTITUTED);
        List<Verifier.Region> regions = buildRegions(doc, base.slots(), headerBodyIndex, headerRegion,
                editBySlotIndex);

        Path assembledForVerify = saveTemp(pkg, workDir, "swap-verify");
        VerifyReport report =
                Verifier.verifyRegions(baselineDocx, assembledForVerify, renderer, fontMap, regions, workDir);
        if (!report.ok()) {
            return Result.verifyFailed(detailOf(report));
        }

        return new Result(SwapOutcome.OK, stackItemsKept, padBySlotIndex.size(), null);
    }

    /**
     * PHASE3_SPEC.md section 5: the header's final {@code detail} and, when a stack-fit search
     * ran, how many items it kept. Package-visible so {@code CorpusRotationTest} (P3-T4) can
     * resolve a rotated position's header the same way a real swap does. Returns null for
     * {@code HEADER_TOO_LONG}.
     */
    static StackFit resolveDetail(DocxPackage basePkg, int headerBodyIndex, LibraryProject project,
            boolean hasDetailToken, int targetHeaderLines, boolean isTabDate, Double dateEdgeTwips,
            Renderer renderer, Path workDir) throws Exception {
        if (!hasDetailToken) {
            return new StackFit(null, null); // PHASE3_SPEC.md 5: no DETAIL token, no stack shown
        }
        if (project.detail() == null || !project.detail().contains(",")) {
            return new StackFit(project.detail(), null); // not a comma list: used as-is, no fit search
        }
        List<String> items = List.of(project.detail().split(",\\s*"));
        return fitStack(basePkg, headerBodyIndex, project, items, targetHeaderLines, isTabDate, dateEdgeTwips,
                renderer, workDir);
    }

    static StackFit fitStack(DocxPackage basePkg, int headerBodyIndex, LibraryProject project,
            List<String> items, int targetLines, boolean isTabDate, Double dateEdgeTwips, Renderer renderer,
            Path workDir) throws Exception {
        int lo = 1;
        int hi = items.size();
        int best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            String candidateDetail = String.join(", ", items.subList(0, mid));
            int lines = renderHeaderCandidateLines(basePkg, headerBodyIndex, project, candidateDetail, isTabDate,
                    dateEdgeTwips, renderer, workDir);
            if (lines <= targetLines) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (best > 0) {
            return new StackFit(String.join(", ", items.subList(0, best)), best);
        }
        int linesNoDetail = renderHeaderCandidateLines(basePkg, headerBodyIndex, project, null, isTabDate,
                dateEdgeTwips, renderer, workDir);
        return linesNoDetail <= targetLines ? new StackFit(null, 0) : null;
    }

    static int renderHeaderCandidateLines(DocxPackage basePkg, int headerBodyIndex, LibraryProject project,
            String detail, boolean isTabDate, Double dateEdgeTwips, Renderer renderer, Path workDir)
            throws Exception {
        DocxPackage pkg = basePkg.copy();
        Document doc = SafeXml.parse(pkg.readPart("word/document.xml"));
        Element header = resolveBodyChild(doc, headerBodyIndex);
        List<HeaderToken> template = HeaderTemplate.build(header);
        HeaderRenderer.NewFields fields = new HeaderRenderer.NewFields(
                project.title(), detail, toNewLinks(project.links()), project.date());
        HeaderRenderer.render(header, template, fields, url -> "rIdProbe");
        if (isTabDate && dateEdgeTwips != null) {
            DateTabConverter.rightAlignDateTab(doc, header, dateEdgeTwips);
        }
        String renderedText = DomUtil.allText(header);
        pkg.writePart("word/document.xml", XmlSerialize.toBytes(doc));
        Path probeDocx = saveTemp(pkg, workDir, "stackfit");
        Path pdf = renderer.render(probeDocx, workDir);
        List<PdfLines.Line> lines = PdfLines.extract(pdf);
        int[] span = AnchorMeasurer.measureSpans(lines, List.of(renderedText)).get(0);
        return span == null ? Integer.MAX_VALUE : span[1] - span[0] + 1;
    }

    // --- inline-kind positions -----------------------------------------------------------------

    private static Result swapInline(Located base, LibraryProject project, Renderer renderer, FontMap fontMap,
            Path workDir) throws Exception {
        Position position = base.position();
        Document doc = base.doc();
        DocxPackage pkg = base.pkg();
        Element paragraph = position.block().inlineHeaderParagraph;
        InlineTemplate.Model model = InlineTemplate.build(paragraph);

        List<PdfLines.Line> baselineLines = base.baselineLines();
        String originalText = DomUtil.allText(paragraph);
        int[] span0 = AnchorMeasurer.measureSpans(baselineLines, List.of(originalText)).get(0);
        if (span0 == null) {
            throw new IllegalStateException("could not anchor the inline block's own text on its own render");
        }
        int targetTotalLines = span0[1] - span0[0] + 1;

        Map<Integer, String> bulletRewrites = new LinkedHashMap<>();
        for (int j = 0; j < model.groups().size(); j++) {
            int targetL = model.groups().get(j).segments().size();
            Map<String, String> variants = project.bullets().get(j);
            String variantText = variants == null ? null : variants.get(String.valueOf(targetL));
            if (variantText == null) {
                return Result.of(SwapOutcome.MISSING_VARIANT);
            }
            bulletRewrites.put(j, variantText);
        }

        List<HeaderRenderer.NewLink> links = toNewLinks(project.links());
        InlineRenderer.HeaderRewrite headerRewrite =
                new InlineRenderer.HeaderRewrite(project.title(), links.isEmpty() ? null : links.get(0));

        RelationshipWriter relWriter = RelationshipWriter.forPackage(pkg);
        InlineRenderer.render(paragraph, model, headerRewrite, bulletRewrites, relWriter::addHyperlink);
        relWriter.flush();
        pkg.writePart("word/document.xml", XmlSerialize.toBytes(doc));

        Path assembledDocx = saveTemp(pkg, workDir, "inline-probe");
        Path pdf = renderer.render(assembledDocx, workDir);
        List<PdfLines.Line> lines = PdfLines.extract(pdf);
        String newText = DomUtil.allText(paragraph);
        int[] span1 = AnchorMeasurer.measureSpans(lines, List.of(newText)).get(0);
        if (span1 == null) {
            return Result.of(SwapOutcome.BULLET_TOO_LONG);
        }
        int newLines = span1[1] - span1[0] + 1;
        if (newLines > targetTotalLines) {
            return Result.of(SwapOutcome.BULLET_TOO_LONG);
        }
        int paddedBullets = 0;
        SlotEdit edit = SlotEdit.SUBSTITUTED;
        if (newLines < targetTotalLines) {
            Padder.pad(paragraph, targetTotalLines - newLines);
            paddedBullets = 1;
            edit = SlotEdit.PADDED;
            pkg.writePart("word/document.xml", XmlSerialize.toBytes(doc));
        }

        // newText (captured before any padding) is the region's own anchor: a padded paragraph's
        // trailing hard-break + NBSP normalizes to nothing (spec 5.4), so anchoring the padded DOM
        // text would under-measure the span by the padded amount — the same trap CorpusRotationTest
        // hit for rotation's own padding pass, sidestepped here by anchoring only the real content.
        Verifier.Region inlineRegion = new Verifier.Region(originalText, newText, targetTotalLines, edit);
        int inlineBodyIndex = bodyChildIndex(doc, paragraph);
        List<Verifier.Region> regions = buildRegions(doc, base.slots(), inlineBodyIndex, inlineRegion, Map.of());

        Path assembledForVerify = saveTemp(pkg, workDir, "swap-verify");
        VerifyReport report =
                Verifier.verifyRegions(base.baselineDocx(), assembledForVerify, renderer, fontMap, regions, workDir);
        if (!report.ok()) {
            return Result.verifyFailed(detailOf(report));
        }

        return new Result(SwapOutcome.OK, null, paddedBullets, null);
    }

    // --- shared helpers --------------------------------------------------------------------

    private static Located locate(DocxPackage basePkg, int positionIndex, Renderer renderer, Path workDir)
            throws Exception {
        DocxPackage pkg = basePkg.copy();
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

        // One render of this still-pristine copy: both the Phase 1/2 lock map (below) and every
        // later measurement (header/bullet line counts, the date-tab edge — PHASE3_SPEC.md 4.1's
        // "measured on the normalized render") come from this one baseline, never from a
        // partly-edited one.
        Path baselineDocx = saveTemp(pkg, workDir, "baseline");
        Path baselinePdf = renderer.render(baselineDocx, workDir);
        List<PdfLines.Line> baselineLines = PdfLines.extract(baselinePdf);

        Map<Integer, String> glyphBySlotIndex = glyphsOf(resolver, slots);
        List<Locker.LockedSlot> locked = Locker.lock(slots, baselineLines, glyphBySlotIndex);
        Map<Integer, String> lockReasonBySlotIndex = new HashMap<>();
        for (Locker.LockedSlot ls : locked) {
            if (!ls.editable()) {
                lockReasonBySlotIndex.put(ls.slot().index(), ls.lockReason());
            }
        }

        List<Section> sections = SectionDetector.detect(doc);
        Section projectsSection = sections.stream().filter(s -> "projects".equals(s.role())).findFirst()
                .orElseThrow(() -> new IllegalStateException("no projects section detected"));
        List<Position> positions = PositionBuilder.build(
                projectsSection, slotByElement::containsKey, slotByElement, lockReasonBySlotIndex);
        if (positionIndex < 0 || positionIndex >= positions.size()) {
            throw new IllegalArgumentException(
                    "position " + positionIndex + " out of range (0.." + (positions.size() - 1) + ")");
        }
        return new Located(pkg, doc, positions.get(positionIndex), slots, slotByElement,
                baselineDocx, baselinePdf, baselineLines);
    }

    /** Each slot's own numbering-level glyph, for {@link Locker} — mirrors OnboardPipeline's own. */
    private static Map<Integer, String> glyphsOf(NumberingResolver resolver, List<Slot> slots) {
        Map<Integer, String> glyphs = new HashMap<>();
        for (Slot s : slots) {
            resolver.resolveLvlText(s.element()).ifPresent(glyph -> glyphs.put(s.index(), glyph));
        }
        return glyphs;
    }

    private static List<LibraryProject.Link> linksOrEmpty(LibraryProject project) {
        return project.links() == null ? List.of() : project.links();
    }

    /**
     * Every bullet slot in the whole document, in order, each an unchanged {@link Verifier.Region}
     * unless {@code editBySlotIndex} names it — plus {@code extraRegion} (a swapped header or
     * inline paragraph), inserted at {@code extraBodyIndex}'s own point in that document order:
     * right before the first slot whose paragraph comes after it. PHASE3_SPEC.md section 7 step 4:
     * the production Verifier anchors every region together, in one pass, in document order.
     */
    private static List<Verifier.Region> buildRegions(Document doc, List<Slot> allSlots, int extraBodyIndex,
            Verifier.Region extraRegion, Map<Integer, BulletEdit> editBySlotIndex) {
        List<Verifier.Region> regions = new ArrayList<>();
        boolean inserted = false;
        for (Slot s : allSlots) {
            if (!inserted && bodyChildIndex(doc, s.element()) > extraBodyIndex) {
                regions.add(extraRegion);
                inserted = true;
            }
            BulletEdit e = editBySlotIndex.get(s.index());
            regions.add(e == null ? Verifier.Region.unchanged(s.text())
                    : new Verifier.Region(s.text(), e.afterText(), e.targetLines(), e.edit()));
        }
        if (!inserted) {
            regions.add(extraRegion);
        }
        return regions;
    }

    /** Package-visible so {@code CorpusRotationTest} (P3-T4) can report the same failure detail
     * from its own, multi-position call to the production Verifier. */
    static String detailOf(VerifyReport r) {
        if (!r.pagesMatch()) {
            return "pages " + r.pagesBefore() + " -> " + r.pagesAfter();
        }
        if (r.layoutProblem() != null) {
            return r.layoutProblem();
        }
        if (!r.layoutOk()) {
            return "layout shift " + r.layoutShiftPt() + "pt";
        }
        if (!r.fontViolations().isEmpty()) {
            return "font violations: " + r.fontViolations();
        }
        for (var e : r.lineChecks().entrySet()) {
            if (!e.getValue().ok()) {
                return "region " + e.getKey() + " line count " + e.getValue().measured()
                        + " != " + e.getValue().target();
            }
        }
        return "verify failed";
    }

    static List<HeaderRenderer.NewLink> toNewLinks(List<LibraryProject.Link> links) {
        if (links == null) {
            return List.of();
        }
        return links.stream().map(l -> new HeaderRenderer.NewLink(l.label(), l.url())).toList();
    }

    static int bodyChildIndex(Document doc, Element target) {
        Element body = DomUtil.firstChild(doc.getDocumentElement(), "body");
        List<Element> children = DomUtil.elementChildren(body);
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i) == target) {
                return i;
            }
        }
        throw new IllegalStateException("paragraph is not a direct child of the document body");
    }

    static Element resolveBodyChild(Document doc, int index) {
        Element body = DomUtil.firstChild(doc.getDocumentElement(), "body");
        return DomUtil.elementChildren(body).get(index);
    }

    static Path saveTemp(DocxPackage pkg, Path workDir, String label) throws Exception {
        Path p = workDir.resolve(label + "-" + System.nanoTime() + ".docx");
        pkg.save(p);
        return p;
    }
}
