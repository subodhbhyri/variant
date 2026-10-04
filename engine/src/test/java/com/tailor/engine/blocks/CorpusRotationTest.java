package com.tailor.engine.blocks;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.docx.XmlSerialize;
import com.tailor.engine.edit.Padder;
import com.tailor.engine.edit.Substituter;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.measure.AnchorMeasurer;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.numbering.NumberingResolver;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.slots.BulletDetector;
import com.tailor.engine.slots.BulletText;
import com.tailor.engine.slots.Slot;
import com.tailor.engine.verify.SlotEdit;
import com.tailor.engine.verify.Verifier;
import com.tailor.engine.verify.VerifyReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * P3-T4 (PHASE3_SPEC.md section 9.1): rotates every swappable position's
 * content to the next swappable position (wrap-around), in each of the 9
 * corpus resumes' project sections, fitting bullets by binary search on word
 * count (batched, one render per round) and reusing {@link BlockSwapper}'s
 * own header stack-fit/date-tab logic. Skips must equal golden's
 * {@code _rotation.skipped} exactly, and every line outside the rotated
 * positions must stay within 0.5pt — anchored in one pass, in document
 * order, since a skip leaves the same project's content on the page twice
 * (its own untouched slot and the slot that just received it).
 */
@Tag("corpus")
class CorpusRotationTest {

    private static final double LAYOUT_TOLERANCE_PT = 0.5;
    private static final String R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    @Test
    void rotatingSwappablePositionsVerifiesOkOnAllNine() throws Exception {
        Map<String, List<Integer>> expectedSkipped = Map.of(
                "resume_ajitesh__", List.of(1),
                "Subodh_Ashok_Bhyri", List.of(1),
                "My_resume1", List.of(0));

        Renderer renderer = new LibreOfficeRenderer();
        FontMap fontMap = FontMap.loadDefault();
        OnboardPipeline onboard = new OnboardPipeline(renderer, fontMap);

        StringBuilder failures = new StringBuilder();
        StringBuilder report = new StringBuilder();

        for (Path docx : CorpusPaths.corpusDocx()) {
            String base = stripExt(docx.getFileName().toString());
            Path workDir = Files.createTempDirectory("rotation-" + base);

            OnboardReport onboardReport = onboard.run(Files.readAllBytes(docx), workDir);
            if (!onboardReport.accepted()) {
                failures.append(base).append(": failed to onboard, reason=").append(onboardReport.reason()).append('\n');
                continue;
            }
            Path normalizedDocx = workDir.resolve("normalized.docx");

            RotationResult result = rotate(base, normalizedDocx, onboardReport, renderer, fontMap, workDir);
            if (result == null) {
                report.append(base).append(": no projects section / nothing swappable — skipped entirely\n");
                continue;
            }

            List<Integer> expected = expectedSkipped.getOrDefault(base, List.of());
            if (!result.skipped.equals(expected)) {
                failures.append(base).append(": expected skipped=").append(expected)
                        .append(", got ").append(result.skipped).append('\n');
            }
            if (result.verify != null && !result.verify.ok()) {
                failures.append(base).append(": production Verifier disagrees: ")
                        .append(BlockSwapper.detailOf(result.verify)).append('\n');
            }
            if (!result.diag.ok()) {
                failures.append(base).append(": ").append(result.diag.problem()).append('\n');
            } else if (result.diag.maxMovement() > LAYOUT_TOLERANCE_PT) {
                failures.append(base).append(": moved a fixed line by ").append(result.diag.maxMovement()).append("pt\n");
            }
            report.append(String.format("%-32s rotated=%-3d skipped=%-10s worst=%.4fpt%n",
                    base, result.rotatedCount, result.skipped, result.diag.maxMovement()));
        }

        System.out.println("P3-T4 rotation report:\n" + report);
        System.out.println("FAILURES:\n" + (failures.isEmpty() ? "(none)" : failures));
        assertTrue(failures.isEmpty(), "P3-T4 failures:\n" + failures);
    }

    private record RotationResult(int rotatedCount, List<Integer> skipped, LayoutDiff.Result diag,
                                   VerifyReport verify) {
    }

    private record Adapter(String title, String detail, String date, List<LibraryProject.Link> links,
                            List<String> bulletTexts) {
    }

    private record HeaderEditInfo(String afterText, int targetLines) {
    }

    private record BulletEditInfo(String afterText, int targetLines, boolean padded) {
    }

    private record InlineEditInfo(String afterText, int targetLines, boolean padded) {
    }

    /** {@code locator}: for a paragraph bullet, its (stable) Slot index; for an inline bullet,
     * the body-child index of its inline paragraph. {@code groupIdx}: inline bullet group index
     * (-1 for paragraph bullets). */
    private record BulletTarget(int receivingPosIdx, int bulletIdx, boolean inline, int locator, int groupIdx,
                                 int targetLines, String sourceText) {
    }

    private record TargetFit(String text, Integer measuredLines) {
    }

    private static RotationResult rotate(String base, Path normalizedDocx, OnboardReport onboardReport,
            Renderer renderer, FontMap fontMap, Path workDir) throws Exception {
        DocxPackage basePkg = DocxPackage.open(normalizedDocx);
        Document doc = SafeXml.parse(basePkg.readPart("word/document.xml"));
        Element numberingRoot = basePkg.hasPart("word/numbering.xml")
                ? SafeXml.parse(basePkg.readPart("word/numbering.xml")).getDocumentElement() : null;
        Element stylesRoot = basePkg.hasPart("word/styles.xml")
                ? SafeXml.parse(basePkg.readPart("word/styles.xml")).getDocumentElement() : null;
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
        Section projectsSection = sections.stream().filter(s -> "projects".equals(s.role())).findFirst().orElse(null);
        if (projectsSection == null) {
            return null;
        }
        List<Position> positions =
                PositionBuilder.build(projectsSection, slotByElement::containsKey, slotByElement, lockReasonBySlotIndex);

        List<Integer> swappableIdx = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            if (positions.get(i).swappable()) {
                swappableIdx.add(i);
            }
        }
        int n = swappableIdx.size();
        if (n == 0) {
            return null;
        }

        Map<Integer, Integer> receivingToIncoming = new LinkedHashMap<>();
        List<Integer> skipped = new ArrayList<>(); // indices into swappableIdx (golden's own convention), not overall position indices
        for (int k = 0; k < n; k++) {
            int receivingPosIdx = swappableIdx.get(k);
            int incomingPosIdx = swappableIdx.get((k + 1) % n);
            if (positions.get(incomingPosIdx).bullets() < positions.get(receivingPosIdx).bullets()) {
                skipped.add(k);
            } else {
                receivingToIncoming.put(receivingPosIdx, incomingPosIdx);
            }
        }
        Collections.sort(skipped);

        if (receivingToIncoming.isEmpty()) {
            return new RotationResult(0, skipped, new LayoutDiff.Result(true, 0, null), null);
        }

        Path baselinePdf = renderer.render(normalizedDocx, workDir);
        List<PdfLines.Line> baselineLines = PdfLines.extract(baselinePdf);
        List<String> allSlotTexts = slots.stream().map(Slot::text).toList();
        Map<Integer, Integer> lineCountBySlotIndex = new HashMap<>(AnchorMeasurer.measure(baselineLines, allSlotTexts));

        Document relsDoc = basePkg.hasPart("word/_rels/document.xml.rels")
                ? SafeXml.parse(basePkg.readPart("word/_rels/document.xml.rels")) : null;

        Map<Integer, Adapter> adapterByIncomingPosIdx = new LinkedHashMap<>();
        for (int incomingPosIdx : new LinkedHashSet<>(receivingToIncoming.values())) {
            adapterByIncomingPosIdx.put(incomingPosIdx, buildAdapter(positions.get(incomingPosIdx), relsDoc));
        }

        List<BulletTarget> targets = new ArrayList<>();
        Map<Integer, List<Integer>> targetIndicesByReceivingPos = new LinkedHashMap<>();
        Map<Integer, Integer> searchStartHintByBodyIdx = new HashMap<>();
        for (var e : receivingToIncoming.entrySet()) {
            int receivingPosIdx = e.getKey();
            Position receiving = positions.get(receivingPosIdx);
            Adapter adapter = adapterByIncomingPosIdx.get(e.getValue());
            int k = receiving.bullets();
            List<Integer> indices = new ArrayList<>();
            if ("inline".equals(receiving.kind())) {
                int bodyIdx = bodyChildIndex(doc, receiving.block().inlineHeaderParagraph);
                // Same duplicate-content risk as the header search (PHASE3_SPEC.md 9.1's own
                // warning): the incoming source's own text is still, unedited, elsewhere on the
                // page during this isolated probe. Restrict each inline target's anchor search to
                // lines near this receiving paragraph's own known (baseline) position.
                String ownWholeText = DomUtil.allText(receiving.block().inlineHeaderParagraph);
                int[] ownSpan = AnchorMeasurer.measureSpans(baselineLines, List.of(ownWholeText)).get(0);
                if (ownSpan == null) {
                    throw new AssertionError(base + ": could not anchor inline position " + receivingPosIdx
                            + "'s own text on the baseline render");
                }
                searchStartHintByBodyIdx.put(bodyIdx, ownSpan[0]);
                List<Integer> segCounts = receiving.segmentsPerBullet();
                for (int j = 0; j < k; j++) {
                    indices.add(targets.size());
                    targets.add(new BulletTarget(receivingPosIdx, j, true, bodyIdx, j,
                            segCounts.get(j), adapter.bulletTexts().get(j)));
                }
            } else {
                List<Element> bulletParas = receiving.block().bulletParas;
                for (int j = 0; j < k; j++) {
                    Slot slot = slotByElement.get(bulletParas.get(j));
                    int targetLines = lineCountBySlotIndex.get(slot.index());
                    indices.add(targets.size());
                    targets.add(new BulletTarget(receivingPosIdx, j, false, slot.index(), -1,
                            targetLines, adapter.bulletTexts().get(j)));
                }
            }
            targetIndicesByReceivingPos.put(receivingPosIdx, indices);
        }

        Map<Integer, TargetFit> fitByTargetIdx =
                fitBullets(base, targets, normalizedDocx, searchStartHintByBodyIdx, renderer, workDir);

        // --- Final assembly: fresh parse, apply every rotated position's header + bullets. ---
        DocxPackage finalPkg = DocxPackage.open(normalizedDocx);
        Document finalDoc = SafeXml.parse(finalPkg.readPart("word/document.xml"));
        RelationshipWriter relWriter = RelationshipWriter.forPackage(finalPkg);

        // For the final production-Verifier check (PHASE3_SPEC.md section 7 step 4): each edited
        // region's own after-render anchor text, captured before any padding is applied — a padded
        // bullet's trailing <w:br/>+nbsp normalizes to nothing (spec 5.4), so anchoring the padded
        // DOM text would under-measure its span by the padded amount.
        Map<Integer, HeaderEditInfo> headerEdits = new LinkedHashMap<>();
        Map<Integer, Map<Integer, BulletEditInfo>> bulletEdits = new LinkedHashMap<>();

        for (var e : receivingToIncoming.entrySet()) {
            int receivingPosIdx = e.getKey();
            Position receiving = positions.get(receivingPosIdx);
            Adapter adapter = adapterByIncomingPosIdx.get(e.getValue());
            List<Integer> tIndices = targetIndicesByReceivingPos.get(receivingPosIdx);

            if ("inline".equals(receiving.kind())) {
                int bodyIdx = bodyChildIndex(doc, receiving.block().inlineHeaderParagraph);
                Element paragraph = resolveBodyChild(finalDoc, bodyIdx);
                InlineTemplate.Model model = InlineTemplate.build(paragraph);
                Map<Integer, String> rewrites = new LinkedHashMap<>();
                for (int j = 0; j < receiving.bullets(); j++) {
                    rewrites.put(j, fitByTargetIdx.get(tIndices.get(j)).text());
                }
                List<HeaderRenderer.NewLink> links = BlockSwapper.toNewLinks(adapter.links());
                InlineRenderer.HeaderRewrite headerRewrite =
                        new InlineRenderer.HeaderRewrite(adapter.title(), links.isEmpty() ? null : links.get(0));
                InlineRenderer.render(paragraph, model, headerRewrite, rewrites, relWriter::addHyperlink);
            } else {
                Element originalHeader = receiving.block().headerParas.get(0);
                int headerBodyIdx = bodyChildIndex(doc, originalHeader);
                Element header = resolveBodyChild(finalDoc, headerBodyIdx);
                List<HeaderToken> template = HeaderTemplate.build(header);
                boolean hasDetailToken = template.stream().anyMatch(t -> t.kind() == HeaderToken.Kind.DETAIL);
                boolean isTabDate = "tab".equals(receiving.header().dateMode());
                String originalHeaderText = DomUtil.allText(originalHeader);
                int[] hspan = AnchorMeasurer.measureSpans(baselineLines, List.of(originalHeaderText)).get(0);
                if (hspan == null) {
                    throw new AssertionError(base + ": could not anchor position " + receivingPosIdx + "'s own header");
                }
                int targetHeaderLines = hspan[1] - hspan[0] + 1;
                Double dateEdgeTwips = null;
                if (isTabDate) {
                    int leftMarginTwips = DateTabConverter.leftMarginTwips(doc);
                    dateEdgeTwips = DateEdgeMeasurer.measureRightEdge(baselinePdf, originalHeaderText, leftMarginTwips)
                            .map(DateEdgeMeasurer.Measurement::rightEdgeTwips).orElse(null);
                }
                LibraryProject probeProject = new LibraryProject(
                        null, adapter.title(), adapter.detail(), adapter.links(), adapter.date(), null, null);
                // Not BlockSwapper.resolveDetail: its candidate-header anchor search starts at
                // cursor 0 across the whole page, which a real swap never collides on (a library
                // title is never also, independently, still sitting elsewhere on the same page).
                // Rotation's adapter title is literally another position's own current text —
                // during this isolated probe that other position is still unedited, so the same
                // prefix genuinely occurs twice; restricting the search to lines near this
                // header's own known (baseline) position avoids matching the wrong one.
                BlockSwapper.StackFit fit = rotationResolveDetail(basePkg, headerBodyIdx, probeProject,
                        hasDetailToken, targetHeaderLines, isTabDate, dateEdgeTwips, hspan[0], renderer, workDir);
                if (fit == null) {
                    throw new AssertionError(base + ": rotated header at position " + receivingPosIdx
                            + " is HEADER_TOO_LONG — a real finding, not a harness bug (PHASE3_SPEC.md 9.1)");
                }
                HeaderRenderer.NewFields headerFields = new HeaderRenderer.NewFields(
                        adapter.title(), fit.detail(), BlockSwapper.toNewLinks(adapter.links()), adapter.date());
                HeaderRenderer.render(header, template, headerFields, relWriter::addHyperlink);
                if (isTabDate && dateEdgeTwips != null) {
                    DateTabConverter.rightAlignDateTab(finalDoc, header, dateEdgeTwips);
                }
                headerEdits.put(receivingPosIdx, new HeaderEditInfo(DomUtil.allText(header), targetHeaderLines));

                List<Element> originalBulletParas = receiving.block().bulletParas;
                Map<Integer, BulletEditInfo> bulletEditsForPos = new LinkedHashMap<>();
                for (int j = 0; j < originalBulletParas.size(); j++) {
                    int bIdx = bodyChildIndex(doc, originalBulletParas.get(j));
                    Element bulletP = resolveBodyChild(finalDoc, bIdx);
                    TargetFit tf = fitByTargetIdx.get(tIndices.get(j));
                    Substituter.substitute(bulletP, new BulletText(tf.text(), List.of()));
                    Integer measured = tf.measuredLines();
                    int targetL = targets.get(tIndices.get(j)).targetLines();
                    boolean padded = measured != null && measured < targetL;
                    if (padded) {
                        Padder.pad(bulletP, targetL - measured);
                    }
                    bulletEditsForPos.put(j, new BulletEditInfo(tf.text(), targetL, padded));
                }
                bulletEdits.put(receivingPosIdx, bulletEditsForPos);
            }
        }
        relWriter.flush();
        finalPkg.writePart("word/document.xml", XmlSerialize.toBytes(finalDoc));

        // Inline positions: their whole paragraph is one edited unit — probe once, pad if short.
        Map<Integer, InlineEditInfo> inlineEdits = padInlineRotatedPositions(
                base, doc, finalDoc, finalPkg, positions, receivingToIncoming, baselineLines, renderer, workDir);
        if (inlineEdits.values().stream().anyMatch(InlineEditInfo::padded)) {
            finalPkg.writePart("word/document.xml", XmlSerialize.toBytes(finalDoc));
        }

        Path assembledDocx = workDir.resolve("rotation-final-" + base + "-" + System.nanoTime() + ".docx");
        finalPkg.save(assembledDocx);
        Path finalPdf = renderer.render(assembledDocx, workDir);
        List<PdfLines.Line> finalLines = PdfLines.extract(finalPdf);

        List<Verifier.Region> regions = buildOrderedRegions(positions, headerEdits, bulletEdits, inlineEdits);
        VerifyReport verifyReport =
                Verifier.verifyRegions(normalizedDocx, assembledDocx, renderer, fontMap, regions, workDir);

        PositionAnchors beforeAnchors = buildOrderedAnchors(positions);
        Map<Integer, int[]> beforeEntrySpans = AnchorMeasurer.measureSpans(baselineLines, beforeAnchors.texts());

        List<Position> afterPositions = detectPositionsOnly(assembledDocx);
        PositionAnchors afterAnchors = buildOrderedAnchors(afterPositions);
        Map<Integer, int[]> afterEntrySpans = AnchorMeasurer.measureSpans(finalLines, afterAnchors.texts());

        List<int[]> editedSpansBefore = new ArrayList<>();
        List<int[]> editedSpansAfter = new ArrayList<>();
        for (int receivingPosIdx : receivingToIncoming.keySet()) {
            int[] before = combinedSpan(beforeEntrySpans, beforeAnchors.rangePerPosition().get(receivingPosIdx));
            editedSpansBefore.add(before);
            // Not combinedSpan for "after": a padded bullet's trailing <w:br/>+nbsp normalizes to
            // an empty string (NFKC maps nbsp to a plain space, which normalize() then strips),
            // so it's invisible to anchor-text matching on *either* side and a text-based span can
            // never include it. Padding exists specifically to keep the position's total line
            // count identical to "before", so the after-span's end is derived from that known,
            // invariant length instead — only its start (the header/whole-paragraph's own first
            // anchor entry, never itself padded) needs anchoring.
            int[] afterFirstEntry = afterEntrySpans.get(afterAnchors.rangePerPosition().get(receivingPosIdx)[0]);
            if (before == null || afterFirstEntry == null) {
                editedSpansAfter.add(null);
            } else {
                int lineCount = before[1] - before[0] + 1;
                editedSpansAfter.add(new int[] {afterFirstEntry[0], afterFirstEntry[0] + lineCount - 1});
            }
        }
        LayoutDiff.Result diag =
                LayoutDiff.checkOutsideMovementMulti(baselineLines, editedSpansBefore, finalLines, editedSpansAfter);
        return new RotationResult(receivingToIncoming.size(), skipped, diag, verifyReport);
    }

    /**
     * Like {@link BlockSwapper#resolveDetail}, but restricts each candidate's anchor search to
     * lines from {@code searchStartHint} onward (a small buffer before it), rather than the whole
     * page from line 0. A rotation adapter's title is literally another position's own current
     * text; during this isolated probe that other position is still unedited, so the same title
     * prefix genuinely occurs twice on the page — a whole-page, cursor-0 search matches the wrong
     * (earlier, unrelated) occurrence and then fails to find the rest of the candidate there.
     */
    private static BlockSwapper.StackFit rotationResolveDetail(DocxPackage basePkg, int headerBodyIdx,
            LibraryProject project, boolean hasDetailToken, int targetHeaderLines, boolean isTabDate,
            Double dateEdgeTwips, int searchStartHint, Renderer renderer, Path workDir) throws Exception {
        if (!hasDetailToken) {
            return new BlockSwapper.StackFit(null, null);
        }
        if (project.detail() == null || !project.detail().contains(",")) {
            return new BlockSwapper.StackFit(project.detail(), null);
        }
        List<String> items = List.of(project.detail().split(",\\s*"));
        int lo = 1;
        int hi = items.size();
        int best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            String candidateDetail = String.join(", ", items.subList(0, mid));
            int lines = rotationRenderHeaderCandidateLines(basePkg, headerBodyIdx, project, candidateDetail,
                    isTabDate, dateEdgeTwips, searchStartHint, renderer, workDir);
            if (lines <= targetHeaderLines) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (best > 0) {
            return new BlockSwapper.StackFit(String.join(", ", items.subList(0, best)), best);
        }
        int linesNoDetail = rotationRenderHeaderCandidateLines(basePkg, headerBodyIdx, project, null, isTabDate,
                dateEdgeTwips, searchStartHint, renderer, workDir);
        return linesNoDetail <= targetHeaderLines ? new BlockSwapper.StackFit(null, 0) : null;
    }

    private static int rotationRenderHeaderCandidateLines(DocxPackage basePkg, int headerBodyIdx,
            LibraryProject project, String detail, boolean isTabDate, Double dateEdgeTwips, int searchStartHint,
            Renderer renderer, Path workDir) throws Exception {
        DocxPackage pkg = basePkg.copy();
        Document doc = SafeXml.parse(pkg.readPart("word/document.xml"));
        Element header = resolveBodyChild(doc, headerBodyIdx);
        List<HeaderToken> template = HeaderTemplate.build(header);
        HeaderRenderer.NewFields fields = new HeaderRenderer.NewFields(
                project.title(), detail, BlockSwapper.toNewLinks(project.links()), project.date());
        HeaderRenderer.render(header, template, fields, url -> "rIdProbe");
        if (isTabDate && dateEdgeTwips != null) {
            DateTabConverter.rightAlignDateTab(doc, header, dateEdgeTwips);
        }
        String renderedText = DomUtil.allText(header);
        pkg.writePart("word/document.xml", XmlSerialize.toBytes(doc));
        Path probeDocx = workDir.resolve("rotation-stackfit-" + System.nanoTime() + ".docx");
        pkg.save(probeDocx);
        Path pdf = renderer.render(probeDocx, workDir);
        List<PdfLines.Line> allLines = PdfLines.extract(pdf);
        int start = Math.max(0, Math.min(searchStartHint - 3, allLines.size()));
        int[] span = AnchorMeasurer.measureSpans(allLines.subList(start, allLines.size()), List.of(renderedText))
                .get(0);
        return span == null ? Integer.MAX_VALUE : span[1] - span[0] + 1;
    }

    /** Whole-paragraph total-line-count check for rotated inline positions, batched in one probe
     * render; pads any that came up short. Returns each rotated inline position's own edit info
     * (its pre-pad anchor text, captured before {@link Padder#pad} — same reasoning as {@code
     * headerEdits}/{@code bulletEdits} above — for the final production-Verifier check). */
    private static Map<Integer, InlineEditInfo> padInlineRotatedPositions(String base, Document originalDoc,
            Document finalDoc, DocxPackage finalPkg, List<Position> positions,
            Map<Integer, Integer> receivingToIncoming, List<PdfLines.Line> baselineLines, Renderer renderer,
            Path workDir) throws Exception {
        List<Integer> inlineReceiving = new ArrayList<>();
        for (int receivingPosIdx : receivingToIncoming.keySet()) {
            if ("inline".equals(positions.get(receivingPosIdx).kind())) {
                inlineReceiving.add(receivingPosIdx);
            }
        }
        if (inlineReceiving.isEmpty()) {
            return Map.of();
        }

        Path probeDocx = workDir.resolve("rotation-inline-probe-" + base + "-" + System.nanoTime() + ".docx");
        finalPkg.save(probeDocx);
        Path probePdf = renderer.render(probeDocx, workDir);
        List<PdfLines.Line> probeLines = PdfLines.extract(probePdf);

        // One ordered pass over every position (not just the inline receiving ones), in document
        // order, exactly like the final duplicate-content check: a skipped position leaves its
        // own content on the page unedited while whoever received FROM it now shows a near- or
        // exact duplicate, so an isolated, unordered search for either one's text can match the
        // wrong occurrence. A shared, forward-only cursor across all of them resolves it.
        List<Position> probePositions = detectPositionsOnly(probeDocx);
        PositionAnchors probeAnchors = buildOrderedAnchors(probePositions);
        Map<Integer, int[]> probeEntrySpans = AnchorMeasurer.measureSpans(probeLines, probeAnchors.texts());

        Map<Integer, InlineEditInfo> edits = new LinkedHashMap<>();
        for (int receivingPosIdx : inlineReceiving) {
            Position receiving = positions.get(receivingPosIdx);
            int bodyIdx = bodyChildIndex(originalDoc, receiving.block().inlineHeaderParagraph);
            Element paragraph = resolveBodyChild(finalDoc, bodyIdx);

            String originalWholeText = DomUtil.allText(receiving.block().inlineHeaderParagraph);
            int[] origSpan = AnchorMeasurer.measureSpans(baselineLines, List.of(originalWholeText)).get(0);
            if (origSpan == null) {
                throw new AssertionError(base + ": could not anchor original inline position " + receivingPosIdx);
            }
            int targetTotalLines = origSpan[1] - origSpan[0] + 1;

            int[] span = combinedSpan(probeEntrySpans, probeAnchors.rangePerPosition().get(receivingPosIdx));
            if (span == null) {
                throw new AssertionError(base + ": could not anchor rotated inline position " + receivingPosIdx);
            }
            int newLines = span[1] - span[0] + 1;

            if (newLines > targetTotalLines) {
                throw new AssertionError(base + ": rotated inline position " + receivingPosIdx + " grew from "
                        + targetTotalLines + " to " + newLines + " lines — a real finding");
            }
            // Captured before any padding: the paragraph's own real (unpadded) content, the safe
            // anchor for the final production-Verifier region.
            String prePadText = DomUtil.allText(paragraph);
            boolean padded = newLines < targetTotalLines;
            if (padded) {
                Padder.pad(paragraph, targetTotalLines - newLines);
            }
            edits.put(receivingPosIdx, new InlineEditInfo(prePadText, targetTotalLines, padded));
        }
        return edits;
    }

    // --- adapter construction -----------------------------------------------------------------

    private static Adapter buildAdapter(Position incoming, Document relsDoc) {
        if ("inline".equals(incoming.kind())) {
            Element paragraph = incoming.block().inlineHeaderParagraph;
            InlineTemplate.Model model = InlineTemplate.build(paragraph);
            StringBuilder title = new StringBuilder();
            List<LibraryProject.Link> links = new ArrayList<>();
            for (Element node : model.headerNodes()) {
                if ("hyperlink".equals(node.getLocalName())) {
                    String url = resolveLinkUrl(node, relsDoc);
                    String label = DomUtil.allText(node).strip();
                    if (url != null && LinkValidator.isValid(url)) {
                        links.add(new LibraryProject.Link(label, url));
                    }
                } else {
                    title.append(DomUtil.allText(node));
                }
            }
            List<String> bulletTexts = new ArrayList<>();
            for (InlineTemplate.BulletGroup g : model.groups()) {
                bulletTexts.add(stripLeadingGlyph(InlineTemplate.ownText(g)));
            }
            return new Adapter(title.toString().strip(), null, null, links, bulletTexts);
        }

        Element header = incoming.block().headerParas.get(0);
        HeaderParser.Result parsed = HeaderParser.parse(header);
        HeaderParser.Parsed p = parsed.parsed();
        List<HeaderToken> template = HeaderTemplate.build(header);
        List<LibraryProject.Link> links = new ArrayList<>();
        for (HeaderToken t : template) {
            if (t.kind() == HeaderToken.Kind.LINK) {
                String url = resolveLinkUrl(t.element(), relsDoc);
                String label = DomUtil.allText(t.element()).strip();
                if (url != null && LinkValidator.isValid(url)) {
                    links.add(new LibraryProject.Link(label, url));
                }
            }
        }
        List<String> bulletTexts = incoming.block().bulletParas.stream().map(DomUtil::allText).toList();
        return new Adapter(p.title(), p.detail(), p.date(), links, bulletTexts);
    }

    private static String resolveLinkUrl(Element linkElement, Document relsDoc) {
        if (relsDoc == null) {
            return null;
        }
        String rId = linkElement.getAttributeNS(R_NS, "id");
        if (rId == null || rId.isEmpty()) {
            return null;
        }
        for (Element rel : DomUtil.elementChildren(relsDoc.getDocumentElement())) {
            if (rId.equals(DomUtil.attr(rel, "Id"))) {
                return DomUtil.attr(rel, "Target");
            }
        }
        return null;
    }

    private static String stripLeadingGlyph(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        if (i < s.length() && BlockDetector.GLYPHS.indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    // --- batched word-count fitting (the Phase 1 batch principle; parallel binary search) -----

    private static Map<Integer, TargetFit> fitBullets(String base, List<BulletTarget> targets, Path sourceDocx,
            Map<Integer, Integer> searchStartHintByBodyIdx, Renderer renderer, Path workDir) throws Exception {
        int n = targets.size();
        int[] lo = new int[n];
        int[] hi = new int[n];
        Integer[] bestWords = new Integer[n];
        Integer[] bestMeasured = new Integer[n];
        for (int i = 0; i < n; i++) {
            String[] words = targets.get(i).sourceText().trim().split("\\s+");
            lo[i] = 1;
            hi[i] = Math.max(words.length, 1);
        }

        int round = 0;
        while (true) {
            Map<Integer, Integer> mids = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                if (lo[i] <= hi[i]) {
                    mids.put(i, (lo[i] + hi[i]) / 2);
                }
            }
            if (mids.isEmpty()) {
                break;
            }
            round++;
            Map<Integer, String> candidateByTargetIdx = new LinkedHashMap<>();
            for (var e : mids.entrySet()) {
                candidateByTargetIdx.put(e.getKey(), wordPrefix(targets.get(e.getKey()).sourceText(), e.getValue()));
            }
            Path outDocx = workDir.resolve("rotation-fit-" + base + "-r" + round + "-" + System.nanoTime() + ".docx");
            Map<Integer, Integer> measured = renderFittingRound(sourceDocx, targets, candidateByTargetIdx,
                    searchStartHintByBodyIdx, renderer, outDocx, workDir);
            for (var e : mids.entrySet()) {
                int i = e.getKey();
                int mid = e.getValue();
                Integer measuredLines = measured.get(i);
                int targetL = targets.get(i).targetLines();
                if (measuredLines != null && measuredLines <= targetL) {
                    bestWords[i] = mid;
                    bestMeasured[i] = measuredLines;
                    lo[i] = mid + 1;
                } else {
                    hi[i] = mid - 1;
                }
            }
        }

        Map<Integer, TargetFit> out = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int words = bestWords[i] == null ? 1 : bestWords[i];
            out.put(i, new TargetFit(wordPrefix(targets.get(i).sourceText(), words), bestMeasured[i]));
        }
        return out;
    }

    private static String wordPrefix(String text, int wordCount) {
        String[] words = text.trim().split("\\s+");
        int n = Math.max(Math.min(wordCount, words.length), 0);
        return String.join(" ", Arrays.asList(words).subList(0, n));
    }

    /** One shared render: every active target's current-round candidate substituted at once
     * (paragraph bullets via Substituter, inline groups via InlineRenderer, batched per
     * paragraph), everything else left at its original text. */
    private static Map<Integer, Integer> renderFittingRound(Path sourceDocx, List<BulletTarget> targets,
            Map<Integer, String> candidateByTargetIdx, Map<Integer, Integer> searchStartHintByBodyIdx,
            Renderer renderer, Path outDocx, Path workDir) throws Exception {
        DocxPackage pkg = DocxPackage.open(sourceDocx);
        Document doc = SafeXml.parse(pkg.readPart("word/document.xml"));
        Element numberingRoot = pkg.hasPart("word/numbering.xml")
                ? SafeXml.parse(pkg.readPart("word/numbering.xml")).getDocumentElement() : null;
        Element stylesRoot = pkg.hasPart("word/styles.xml")
                ? SafeXml.parse(pkg.readPart("word/styles.xml")).getDocumentElement() : null;
        NumberingResolver resolver = new NumberingResolver(numberingRoot, stylesRoot);
        List<Slot> freshSlots = BulletDetector.detect(doc, resolver);

        Map<Integer, Integer> targetIdxBySlotIndex = new HashMap<>();
        Map<Integer, Map<Integer, String>> inlineRewritesByBodyIdx = new LinkedHashMap<>();
        for (int ti = 0; ti < targets.size(); ti++) {
            String candidate = candidateByTargetIdx.get(ti);
            if (candidate == null) {
                continue;
            }
            BulletTarget t = targets.get(ti);
            if (t.inline()) {
                inlineRewritesByBodyIdx.computeIfAbsent(t.locator(), k -> new LinkedHashMap<>())
                        .put(t.groupIdx(), candidate);
            } else {
                targetIdxBySlotIndex.put(t.locator(), ti);
            }
        }

        List<String> textsForAnchor = new ArrayList<>();
        for (int i = 0; i < freshSlots.size(); i++) {
            Slot s = freshSlots.get(i);
            Integer ti = targetIdxBySlotIndex.get(i);
            if (ti != null) {
                String candidate = candidateByTargetIdx.get(ti);
                Substituter.substitute(s.element(), new BulletText(candidate, List.of()));
                textsForAnchor.add(candidate);
            } else {
                textsForAnchor.add(s.text());
            }
        }
        for (var e : inlineRewritesByBodyIdx.entrySet()) {
            Element paragraph = resolveBodyChild(doc, e.getKey());
            InlineTemplate.Model model = InlineTemplate.build(paragraph);
            InlineRenderer.render(paragraph, model, e.getValue());
        }

        pkg.writePart("word/document.xml", XmlSerialize.toBytes(doc));
        pkg.save(outDocx);
        Path pdf = renderer.render(outDocx, workDir);
        List<PdfLines.Line> lines = PdfLines.extract(pdf);

        Map<Integer, Integer> measuredBySlotIndex = AnchorMeasurer.measure(lines, textsForAnchor);
        Map<Integer, Integer> result = new LinkedHashMap<>();
        for (int ti = 0; ti < targets.size(); ti++) {
            String candidate = candidateByTargetIdx.get(ti);
            if (candidate == null) {
                continue;
            }
            BulletTarget t = targets.get(ti);
            if (!t.inline()) {
                Integer m = measuredBySlotIndex.get(t.locator());
                if (m != null) {
                    result.put(ti, m);
                }
            } else {
                // Same duplicate-content risk as the header search: the incoming source's own
                // paragraph is still, unedited, elsewhere on the page during this probe.
                int hint = searchStartHintByBodyIdx.getOrDefault(t.locator(), 0);
                int start = Math.max(0, Math.min(hint - 3, lines.size()));
                int[] span = AnchorMeasurer.measureSpans(lines.subList(start, lines.size()), List.of("• " + candidate))
                        .get(0);
                if (span != null) {
                    result.put(ti, span[1] - span[0] + 1);
                }
            }
        }
        return result;
    }

    // --- shared helpers --------------------------------------------------------------------

    /** {@code texts}: one anchor entry per paragraph, across every position, in document order.
     * {@code rangePerPosition}: each position's own [firstEntryIdx, lastEntryIdx] into {@code
     * texts}. A numbered bullet's own rendered line carries a glyph the numbering draws, never
     * present in the paragraph's own {@code <w:t>} text, so — exactly like {@link
     * BlockSwapFixtureTest}'s own positionSpan — a position's header and each bullet must be
     * anchored as separate entries, never one concatenated string, or a bullet's entry can never
     * be found. */
    private record PositionAnchors(List<String> texts, List<int[]> rangePerPosition) {
    }

    private static PositionAnchors buildOrderedAnchors(List<Position> positions) {
        List<String> texts = new ArrayList<>();
        List<int[]> ranges = new ArrayList<>();
        for (Position p : positions) {
            int start = texts.size();
            if ("inline".equals(p.kind())) {
                texts.add(DomUtil.allText(p.block().inlineHeaderParagraph));
            } else {
                for (Element e : p.block().headerParas) {
                    texts.add(DomUtil.allText(e));
                }
                for (Element e : p.block().bulletParas) {
                    texts.add(DomUtil.allText(e));
                }
                for (Element e : p.block().strayParas) {
                    texts.add(DomUtil.allText(e));
                }
            }
            if (texts.size() == start) {
                texts.add(""); // no paragraphs at all; keep index alignment (measureSpans skips blanks)
            }
            ranges.add(new int[] {start, texts.size() - 1});
        }
        return new PositionAnchors(texts, ranges);
    }

    /**
     * One {@link Verifier.Region} per paragraph across every position, in the same document order
     * as {@link #buildOrderedAnchors} — the production-Verifier equivalent of that method, for
     * PHASE3_SPEC.md section 7 step 4's final check. A rotated position's header/bullets (or whole
     * inline paragraph) become edited regions; everything else — including a skipped position's own
     * untouched content — is {@link Verifier.Region#unchanged}.
     */
    private static List<Verifier.Region> buildOrderedRegions(List<Position> positions,
            Map<Integer, HeaderEditInfo> headerEdits, Map<Integer, Map<Integer, BulletEditInfo>> bulletEdits,
            Map<Integer, InlineEditInfo> inlineEdits) {
        List<Verifier.Region> regions = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            Position p = positions.get(i);
            if ("inline".equals(p.kind())) {
                String before = DomUtil.allText(p.block().inlineHeaderParagraph);
                InlineEditInfo ie = inlineEdits.get(i);
                regions.add(ie == null ? Verifier.Region.unchanged(before)
                        : new Verifier.Region(before, ie.afterText(), ie.targetLines(),
                                ie.padded() ? SlotEdit.PADDED : SlotEdit.SUBSTITUTED));
                continue;
            }
            int before = regions.size();
            for (Element e : p.block().headerParas) {
                String beforeText = DomUtil.allText(e);
                HeaderEditInfo he = headerEdits.get(i);
                regions.add(he == null ? Verifier.Region.unchanged(beforeText)
                        : new Verifier.Region(beforeText, he.afterText(), he.targetLines(), SlotEdit.SUBSTITUTED));
            }
            Map<Integer, BulletEditInfo> bulletsForPos = bulletEdits.getOrDefault(i, Map.of());
            List<Element> bulletParas = p.block().bulletParas;
            for (int j = 0; j < bulletParas.size(); j++) {
                String beforeText = DomUtil.allText(bulletParas.get(j));
                BulletEditInfo be = bulletsForPos.get(j);
                regions.add(be == null ? Verifier.Region.unchanged(beforeText)
                        : new Verifier.Region(beforeText, be.afterText(), be.targetLines(),
                                be.padded() ? SlotEdit.PADDED : SlotEdit.SUBSTITUTED));
            }
            for (Element e : p.block().strayParas) {
                regions.add(Verifier.Region.unchanged(DomUtil.allText(e)));
            }
            if (regions.size() == before) {
                regions.add(Verifier.Region.unchanged(""));
            }
        }
        return regions;
    }

    /** The position's own overall [firstLine, lastLine], combining its first and last anchor
     * entry's spans; null if either end couldn't be anchored. */
    private static int[] combinedSpan(Map<Integer, int[]> entrySpans, int[] range) {
        int[] first = entrySpans.get(range[0]);
        int[] last = entrySpans.get(range[1]);
        if (first == null || last == null) {
            return null;
        }
        return new int[] {first[0], last[1]};
    }

    private static List<Position> detectPositionsOnly(Path docx) throws Exception {
        DocxPackage pkg = DocxPackage.open(docx);
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
        List<Section> sections = SectionDetector.detect(doc);
        Section projectsSection = sections.stream().filter(s -> "projects".equals(s.role())).findFirst()
                .orElseThrow(() -> new IllegalStateException("no projects section detected"));
        return PositionBuilder.build(projectsSection, slotByElement::containsKey, slotByElement, Map.of());
    }

    private static int bodyChildIndex(Document doc, Element target) {
        Element body = DomUtil.firstChild(doc.getDocumentElement(), "body");
        List<Element> children = DomUtil.elementChildren(body);
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i) == target) {
                return i;
            }
        }
        throw new IllegalStateException("paragraph is not a direct child of the document body");
    }

    private static Element resolveBodyChild(Document doc, int index) {
        Element body = DomUtil.firstChild(doc.getDocumentElement(), "body");
        return DomUtil.elementChildren(body).get(index);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
