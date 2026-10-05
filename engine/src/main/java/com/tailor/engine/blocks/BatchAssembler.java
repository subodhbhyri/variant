package com.tailor.engine.blocks;

import com.tailor.engine.calibrate.BatchValidator;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.docx.XmlSerialize;
import com.tailor.engine.edit.Padder;
import com.tailor.engine.edit.Substituter;
import com.tailor.engine.generate.ModelResponse;
import com.tailor.engine.generate.SectionPositions;
import com.tailor.engine.measure.AnchorMeasurer;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.measure.StoredBaseline;
import com.tailor.engine.numbering.NumberingResolver;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.slots.BulletDetector;
import com.tailor.engine.slots.BulletText;
import com.tailor.engine.slots.DocxBulletDetection;
import com.tailor.engine.slots.Slot;
import com.tailor.engine.verify.SlotEdit;
import com.tailor.engine.verify.Verifier;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * PHASE5_SPEC.md section 5.1 (steps B1-B4): assembles resume #1 in one batched pass instead of one
 * verified swap per position. Every line break depends only on its own paragraph's text and width,
 * so each position's header, bullets and inline block can be measured on the normalized document
 * directly, and the positions' results don't depend on each other. That makes the work batchable:
 *
 * <ul>
 *   <li>B1: every measurement comes from the stored baseline ({@link StoredBaseline}), not a render;
 *   <li>B3: a job bullet whose target slot has the same width and formatting as the slots its
 *       variant was validated in is not re-validated;
 *   <li>B4: stack-fit searches for all positions advance together, one probe document per round, and
 *       inline blocks are measured in the same first round;
 *   <li>the bullet validation for every project and unskipped job bullet runs as one batch, and the
 *       finished document is checked once, against the stored baseline, with that check's own render
 *       delivered as the PDF.
 * </ul>
 *
 * <p>Returns {@code ok=false} with a reason whenever anything it can't do in one pass comes up
 * (a bullet too long, a header that doesn't fit, a failed whole-document check); the caller then
 * runs the per-position fail-soft path, so nothing unverified is ever delivered.
 */
public final class BatchAssembler {

    /** Stage timing, implemented by {@code PipelineTiming} in the match package. */
    public interface Timing {
        <T> T time(String name, Callable<T> work) throws Exception;

        void note(String name);
    }

    /** The whole-document check of the finished assembly; production runs the stored-baseline verifier. */
    @FunctionalInterface
    public interface Check {
        Verified run(Path assembledDocx, List<Verifier.Region> regions, Path workDir) throws Exception;
    }

    /** {@code pdf} is the assembled document's own render, the one that was checked. */
    public record Verified(boolean ok, String detail, Path pdf) {
    }

    /** One project in one position. {@code positionIndex} is the position's index in project order; the
     * project's bullets are already the selected ones, in the order the assignment uses. */
    public record Plan(int positionIndex, LibraryProject project) {
    }

    /** When {@code ok}, {@code outputDocx} and {@code pdf} are set; otherwise {@code detail} says why. */
    public record Result(boolean ok, String detail, Path outputDocx, Path pdf) {
    }

    private record Opened(DocxPackage pkg, Document doc, List<Slot> slots) {
    }

    /**
     * One planned position, measured on the normalized document. A header position has {@code inline}
     * false: its header and bullet slots are placed. An inline position has {@code inline} true: its one
     * paragraph carries the header and bullets, rewritten by {@link InlineRenderer}.
     */
    private record Planned(int index, LibraryProject project, boolean inline, String originalText, int targetLines,
                           boolean hasDetail, boolean isTab, Double dateEdge, int bodyIndex, List<Integer> bulletSlots,
                           Map<Integer, String> inlineRewrites, InlineRenderer.HeaderRewrite inlineHeader) {
    }

    /** One header to probe in a round: a planned position with the detail to try (null for none). */
    private record HeaderProbe(Planned planned, String detail) {
    }

    /** One round's measurements: header lines per probe, and the inline blocks' rewritten texts and lines. */
    private record ProbeResult(List<Integer> headerLines, List<String> inlineTexts, List<Integer> inlineLines) {
    }

    private record IndexedRegion(int bodyIndex, Verifier.Region region) {
    }

    private static final Timing NO_TIMING = new Timing() {
        @Override
        public <T> T time(String name, Callable<T> work) throws Exception {
            return work.call();
        }

        @Override
        public void note(String name) {
        }
    };

    private BatchAssembler() {
    }

    public static Result assemble(Path normalizedDocx, StoredBaseline baseline, Path baselinePdf, OnboardReport report,
            List<String> job, Map<String, ModelResponse.BulletCandidate> jobCandidatesById, List<Plan> plans,
            Renderer renderer, Check check, Path workDir, Path outputDocx, Timing timing) throws Exception {
        Timing t = timing == null ? NO_TIMING : timing;

        SectionPositions positions = SectionPositions.detect(normalizedDocx, report);
        if (positions.jobPositions().isEmpty()) {
            return failed("no job position detected");
        }
        List<Integer> jobSlotIndices = positions.bulletSlotIndices(positions.jobPositions().get(0));
        if (jobSlotIndices.size() != job.size()) {
            return failed("job has " + jobSlotIndices.size() + " bullet slots but the resume has " + job.size()
                    + " job entries");
        }
        List<Position> projectPositions = positions.projectPositions();
        List<Slot> allSlots = DocxBulletDetection.detect(normalizedDocx);
        Map<Integer, Integer> lineCounts = new HashMap<>(
                AnchorMeasurer.measure(baseline.lines(), allSlots.stream().map(Slot::text).toList()));

        Opened base = open(normalizedDocx);
        Map<Integer, Slot> baseSlotByIndex = new HashMap<>();
        for (Slot s : base.slots()) {
            baseSlotByIndex.put(s.index(), s);
        }

        // --- job bullets: the variant for each job slot's own line count ---------------------------
        Map<Integer, BulletText> chosen = new LinkedHashMap<>();
        for (int i = 0; i < jobSlotIndices.size(); i++) {
            String id = job.get(i);
            if (id == null) {
                continue;
            }
            int slot = jobSlotIndices.get(i);
            Integer targetLines = lineCounts.get(slot);
            if (targetLines == null) {
                return failed("job slot " + slot + " could not be measured on the baseline");
            }
            ModelResponse.BulletCandidate candidate = jobCandidatesById.get(id);
            if (candidate == null) {
                return failed("no stored job candidate with id " + id);
            }
            String text = candidate.variants().get(String.valueOf(targetLines));
            if (text == null) {
                return failed("candidate " + id + " has no variant at " + targetLines + " line(s)");
            }
            chosen.put(slot, new BulletText(text, List.of()));
        }

        // --- project positions: targets, variants, date-tab edges, inline rewrites -----------------
        List<Planned> planned = new ArrayList<>();
        for (Plan plan : plans) {
            int index = plan.positionIndex();
            if (index < 0 || index >= projectPositions.size()) {
                return failed("position P" + index + " is not in the normalized document");
            }
            Position p = projectPositions.get(index);
            LibraryProject project = plan.project();
            for (LibraryProject.Link link : project.links() == null ? List.<LibraryProject.Link>of() : project.links()) {
                if (!LinkValidator.isValid(link.url())) {
                    return failed("P" + index + " has an invalid link");
                }
            }
            if (project.bullets().size() < p.bullets()) {
                return failed("P" + index + " needs " + p.bullets() + " bullets; " + project.id() + " has "
                        + project.bullets().size());
            }
            if ("inline".equals(p.kind())) {
                Planned inline = planInline(index, p, project, baseline,
                        lineCounts);
                if (inline == null) {
                    return failed("P" + index + " inline block could not be planned");
                }
                planned.add(inline);
                continue;
            }
            Element header = p.block().headerParas.get(0);
            Document pdoc = header.getOwnerDocument();
            String originalHeader = DomUtil.allText(header);
            int[] span = AnchorMeasurer.measureSpans(baseline.lines(), List.of(originalHeader)).get(0);
            if (span == null) {
                return failed("P" + index + " header could not be anchored on the baseline");
            }
            boolean hasDetail = HeaderTemplate.build(header).stream()
                    .anyMatch(tok -> tok.kind() == HeaderToken.Kind.DETAIL);
            boolean isTab = "tab".equals(p.header().dateMode());
            Double dateEdge = null;
            if (isTab) {
                int leftMargin = DateTabConverter.leftMarginTwips(pdoc);
                dateEdge = DateEdgeMeasurer.measureRightEdge(baselinePdf, originalHeader, leftMargin)
                        .map(DateEdgeMeasurer.Measurement::rightEdgeTwips).orElse(null);
            }

            List<Element> bulletParas = p.block().bulletParas;
            List<Integer> bulletSlots = positions.bulletSlotIndices(p);
            if (bulletSlots.size() != bulletParas.size()) {
                return failed("P" + index + " has a bullet with no detected slot");
            }
            for (int j = 0; j < bulletSlots.size(); j++) {
                int slot = bulletSlots.get(j);
                Integer targetLines = lineCounts.get(slot);
                if (targetLines == null) {
                    return failed("P" + index + " bullet " + j + " could not be measured on the baseline");
                }
                Map<String, String> variants = project.bullets().get(j);
                String text = variants == null ? null : variants.get(String.valueOf(targetLines));
                if (text == null) {
                    return failed("P" + index + " bullet " + j + " has no variant at " + targetLines + " line(s)");
                }
                chosen.put(slot, new BulletText(text, List.of()));
            }
            planned.add(new Planned(index, project, false, originalHeader, span[1] - span[0] + 1, hasDetail, isTab,
                    dateEdge, BlockSwapper.bodyChildIndex(pdoc, header), bulletSlots, null, null));
        }

        // --- B3 + B1: validate bullets, skipping those already validated in an equivalent slot --------
        Set<Integer> editableJob = new HashSet<>();
        for (OnboardReport.SlotReport sr : report.slots()) {
            if (sr.editable() && jobSlotIndices.contains(sr.index())) {
                editableJob.add(sr.index());
            }
        }
        Map<Integer, SlotEdit> edits = new HashMap<>();
        Map<Integer, Integer> pads = new HashMap<>();
        Map<Integer, List<BulletText>> toValidate = new LinkedHashMap<>();
        int jobSkipped = 0;
        int jobRendered = 0;
        for (Map.Entry<Integer, BulletText> e : chosen.entrySet()) {
            boolean isJob = jobSlotIndices.contains(e.getKey());
            if (isJob && validatedInEquivalentSlot(e.getKey(), editableJob, lineCounts, baseSlotByIndex)) {
                edits.put(e.getKey(), SlotEdit.SUBSTITUTED);
                jobSkipped++;
            } else {
                toValidate.put(e.getKey(), List.of(e.getValue()));
                if (isJob) {
                    jobRendered++;
                }
            }
        }
        t.note("bullet validation: " + toValidate.size() + " to render (job " + jobRendered + ", project "
                + (toValidate.size() - jobRendered) + "); " + jobSkipped
                + " job bullet(s) skipped as validated in an equivalent slot");
        List<BatchValidator.CandidateResult> results = t.time("bullet validation",
                () -> BatchValidator.validate(normalizedDocx, renderer, lineCounts, Map.of(), toValidate, workDir));
        for (BatchValidator.CandidateResult r : results) {
            switch (r.outcome()) {
                case FITS -> edits.put(r.slotIndex(), SlotEdit.SUBSTITUTED);
                case FITS_WITH_PADDING -> {
                    edits.put(r.slotIndex(), SlotEdit.PADDED);
                    pads.put(r.slotIndex(), lineCounts.get(r.slotIndex()) - r.measuredLines());
                }
                default -> {
                    return failed("slot " + r.slotIndex() + ": " + r.outcome());
                }
            }
        }

        // --- B4: stack fit for every position, and the inline blocks, in shared rounds ----------
        Map<Integer, String> detailByIndex = new LinkedHashMap<>();
        Map<Integer, List<String>> itemsByIndex = new HashMap<>();
        Map<Integer, int[]> searchByIndex = new LinkedHashMap<>();    // index -> {lo, hi, best}
        Set<Integer> noDetailPending = new LinkedHashSet<>();
        Set<String> otherTexts = new HashSet<>(allSlots.stream().map(Slot::text).toList());
        List<Planned> inlinePlans = new ArrayList<>();
        for (Planned pl : planned) {
            otherTexts.add(pl.originalText());
            if (pl.inline()) {
                inlinePlans.add(pl);
                continue;
            }
            if (!pl.hasDetail()) {
                detailByIndex.put(pl.index(), null);
            } else if (pl.project().detail() == null || !pl.project().detail().contains(",")) {
                detailByIndex.put(pl.index(), pl.project().detail());
            } else {
                List<String> items = List.of(pl.project().detail().split(",\\s*"));
                itemsByIndex.put(pl.index(), items);
                searchByIndex.put(pl.index(), new int[] {1, items.size(), -1});
            }
        }
        ProbeResult inlineMeasure = null;
        int round = 0;
        while (true) {
            List<HeaderProbe> probes = new ArrayList<>();
            List<Integer> mids = new ArrayList<>();
            for (Planned pl : planned) {
                int[] s = searchByIndex.get(pl.index());
                if (s == null || s[0] > s[1]) {
                    continue;
                }
                int mid = (s[0] + s[1]) / 2;
                probes.add(new HeaderProbe(pl, String.join(", ", itemsByIndex.get(pl.index()).subList(0, mid))));
                mids.add(mid);
            }
            List<HeaderProbe> noDetailProbes = new ArrayList<>();
            for (Planned pl : planned) {
                if (noDetailPending.contains(pl.index())) {
                    noDetailProbes.add(new HeaderProbe(pl, null));
                }
            }
            probes.addAll(noDetailProbes);
            boolean needInline = round == 0 && !inlinePlans.isEmpty();
            if (probes.isEmpty() && !needInline) {
                break;
            }
            round++;
            final List<HeaderProbe> roundProbes = probes;
            ProbeResult measured = t.time("stack fit round " + round,
                    () -> measureProbes(normalizedDocx, roundProbes, inlinePlans, otherTexts, renderer, workDir));
            if (measured == null) {
                return failed("stack fit probes are not uniquely anchored");
            }
            if (inlineMeasure == null) {
                inlineMeasure = measured;
            }
            int headerCount = 0;
            for (int i = 0; i < probes.size(); i++) {
                HeaderProbe probe = probes.get(i);
                int lines = measured.headerLines().get(i);
                int target = probe.planned().targetLines();
                if (probe.detail() == null && noDetailPending.contains(probe.planned().index())) {
                    if (lines > target) {
                        return failed("P" + probe.planned().index() + " header is too long even without its detail");
                    }
                    detailByIndex.put(probe.planned().index(), null);
                    noDetailPending.remove(probe.planned().index());
                    continue;
                }
                int[] s = searchByIndex.get(probe.planned().index());
                int mid = mids.get(headerCount++);
                if (lines <= target) {
                    s[2] = mid;
                    s[0] = mid + 1;
                } else {
                    s[1] = mid - 1;
                }
            }
            // A search that has run out of prefixes settles here: its best prefix, or else a no-detail probe.
            for (Planned pl : planned) {
                int[] s = searchByIndex.get(pl.index());
                if (s == null || s[0] <= s[1]) {
                    continue;
                }
                searchByIndex.remove(pl.index());
                if (s[2] > 0) {
                    detailByIndex.put(pl.index(), String.join(", ", itemsByIndex.get(pl.index()).subList(0, s[2])));
                } else {
                    noDetailPending.add(pl.index());
                }
            }
        }
        if (inlineMeasure == null && !inlinePlans.isEmpty()) {
            return failed("inline blocks could not be measured");
        }

        // --- inline blocks: fit or pad, from the same round's measurement -------------------------
        Map<Integer, Integer> inlinePads = new HashMap<>();
        Map<Integer, SlotEdit> inlineEdits = new HashMap<>();
        Map<Integer, String> inlineNewText = new HashMap<>();
        for (int k = 0; k < inlinePlans.size(); k++) {
            Planned pl = inlinePlans.get(k);
            int lines = inlineMeasure.inlineLines().get(k);
            if (lines == Integer.MAX_VALUE || lines > pl.targetLines()) {
                return failed("P" + pl.index() + " inline block: BULLET_TOO_LONG");
            }
            inlineNewText.put(pl.index(), inlineMeasure.inlineTexts().get(k));
            inlineEdits.put(pl.index(), lines < pl.targetLines() ? SlotEdit.PADDED : SlotEdit.SUBSTITUTED);
            if (lines < pl.targetLines()) {
                inlinePads.put(pl.index(), pl.targetLines() - lines);
            }
        }

        // --- assemble the finished document once ----------------------------------------------
        Opened fin = stagedCopy(normalizedDocx, workDir);
        RelationshipWriter rels = RelationshipWriter.forPackage(fin.pkg());
        Map<Integer, Slot> finSlotByIndex = new HashMap<>();
        for (Slot s : fin.slots()) {
            finSlotByIndex.put(s.index(), s);
        }
        Map<Integer, String> finalText = new HashMap<>();
        Path assembled = t.time("assemble", () -> {
            for (Map.Entry<Integer, BulletText> e : chosen.entrySet()) {
                Slot s = finSlotByIndex.get(e.getKey());
                Substituter.substitute(s.element(), e.getValue());
                Integer pad = pads.get(e.getKey());
                if (pad != null && pad > 0) {
                    Padder.pad(s.element(), pad);
                }
            }
            for (Planned pl : planned) {
                if (pl.inline()) {
                    Element paragraph = BlockSwapper.resolveBodyChild(fin.doc(), pl.bodyIndex());
                    InlineTemplate.Model model = InlineTemplate.build(paragraph);
                    InlineRenderer.render(paragraph, model, pl.inlineHeader(), pl.inlineRewrites(), rels::addHyperlink);
                    finalText.put(pl.index(), DomUtil.allText(paragraph));
                    Integer pad = inlinePads.get(pl.index());
                    if (pad != null) {
                        Padder.pad(paragraph, pad);
                    }
                    continue;
                }
                Element header = BlockSwapper.resolveBodyChild(fin.doc(), pl.bodyIndex());
                List<HeaderToken> template = HeaderTemplate.build(header);
                HeaderRenderer.NewFields fields = new HeaderRenderer.NewFields(pl.project().title(),
                        detailByIndex.get(pl.index()), BlockSwapper.toNewLinks(pl.project().links()),
                        pl.project().date());
                HeaderRenderer.render(header, template, fields, rels::addHyperlink);
                if (pl.isTab() && pl.dateEdge() != null) {
                    DateTabConverter.rightAlignDateTab(fin.doc(), header, pl.dateEdge());
                }
                finalText.put(pl.index(), DomUtil.allText(header));
            }
            rels.flush();
            fin.pkg().writePart("word/document.xml", XmlSerialize.toBytes(fin.doc()));
            Path out = workDir.resolve("batched-" + System.nanoTime() + ".docx");
            fin.pkg().save(out);
            return out;
        });

        // --- one whole-document check, against the stored baseline ----------------------------
        List<IndexedRegion> ordered = new ArrayList<>();
        for (Slot s : allSlots) {
            BulletText c = chosen.get(s.index());
            Verifier.Region region = c == null ? Verifier.Region.unchanged(s.text())
                    : new Verifier.Region(s.text(), c.text(), lineCounts.get(s.index()), edits.get(s.index()));
            ordered.add(new IndexedRegion(BlockSwapper.bodyChildIndex(s.element().getOwnerDocument(), s.element()),
                    region));
        }
        for (Planned pl : planned) {
            if (pl.inline()) {
                ordered.add(new IndexedRegion(pl.bodyIndex(), new Verifier.Region(pl.originalText(),
                        inlineNewText.get(pl.index()), pl.targetLines(), inlineEdits.get(pl.index()))));
            } else {
                ordered.add(new IndexedRegion(pl.bodyIndex(), new Verifier.Region(pl.originalText(),
                        finalText.get(pl.index()), pl.targetLines(), SlotEdit.SUBSTITUTED)));
            }
        }
        ordered.sort(Comparator.comparingInt(IndexedRegion::bodyIndex));
        List<Verifier.Region> regions = ordered.stream().map(IndexedRegion::region).toList();

        Verified verified = t.time("combined verification", () -> check.run(assembled, regions, workDir));
        if (!verified.ok()) {
            return failed("final whole-document check: " + verified.detail());
        }
        Files.copy(assembled, outputDocx, StandardCopyOption.REPLACE_EXISTING);
        return new Result(true, null, outputDocx, verified.pdf());
    }

    /** An inline block's plan: its rewritten bullets and header, and the line count it must keep. */
    private static Planned planInline(int index, Position p, LibraryProject project,
            StoredBaseline baseline, Map<Integer, Integer> lineCounts) {
        Element paragraph = p.block().inlineHeaderParagraph;
        String originalText = DomUtil.allText(paragraph);
        int[] span = AnchorMeasurer.measureSpans(baseline.lines(), List.of(originalText)).get(0);
        if (span == null) {
            return null;
        }
        InlineTemplate.Model model = InlineTemplate.build(paragraph);
        Map<Integer, String> rewrites = new LinkedHashMap<>();
        for (int j = 0; j < model.groups().size(); j++) {
            int targetL = model.groups().get(j).segments().size();
            Map<String, String> variants = project.bullets().get(j);
            String text = variants == null ? null : variants.get(String.valueOf(targetL));
            if (text == null) {
                return null;
            }
            rewrites.put(j, text);
        }
        List<HeaderRenderer.NewLink> links = BlockSwapper.toNewLinks(project.links());
        InlineRenderer.HeaderRewrite headerRewrite =
                new InlineRenderer.HeaderRewrite(project.title(), links.isEmpty() ? null : links.get(0));
        return new Planned(index, project, true, originalText, span[1] - span[0] + 1, false, false, null,
                BlockSwapper.bodyChildIndex(paragraph.getOwnerDocument(), paragraph), List.of(), rewrites, headerRewrite);
    }

    /**
     * Renders every probe into one document and measures it: each header probe's own line count, and
     * each inline block's rewritten text and line count. Returns null when two probed texts, or a
     * probed text and another paragraph's text, are equal, since the anchor can't tell them apart.
     */
    private static ProbeResult measureProbes(Path normalizedDocx, List<HeaderProbe> probes, List<Planned> inlinePlans,
            Set<String> otherTexts, Renderer renderer, Path workDir) throws Exception {
        Opened o = open(normalizedDocx);
        List<String> headerTexts = new ArrayList<>();
        for (HeaderProbe probe : probes) {
            Planned pl = probe.planned();
            Element header = BlockSwapper.resolveBodyChild(o.doc(), pl.bodyIndex());
            List<HeaderToken> template = HeaderTemplate.build(header);
            HeaderRenderer.NewFields fields = new HeaderRenderer.NewFields(pl.project().title(), probe.detail(),
                    BlockSwapper.toNewLinks(pl.project().links()), pl.project().date());
            HeaderRenderer.render(header, template, fields, url -> "rIdProbe");
            if (pl.isTab() && pl.dateEdge() != null) {
                DateTabConverter.rightAlignDateTab(o.doc(), header, pl.dateEdge());
            }
            headerTexts.add(DomUtil.allText(header));
        }
        List<String> inlineTexts = new ArrayList<>();
        for (Planned pl : inlinePlans) {
            Element paragraph = BlockSwapper.resolveBodyChild(o.doc(), pl.bodyIndex());
            InlineRenderer.render(paragraph, InlineTemplate.build(paragraph), pl.inlineHeader(),
                    pl.inlineRewrites(), url -> "rIdProbe");
            inlineTexts.add(DomUtil.allText(paragraph));
        }
        List<String> all = new ArrayList<>(headerTexts);
        all.addAll(inlineTexts);
        if (new HashSet<>(all).size() != all.size()) {
            return null;
        }
        for (String text : all) {
            if (otherTexts.contains(text)) {
                return null;
            }
        }
        o.pkg().writePart("word/document.xml", XmlSerialize.toBytes(o.doc()));
        Path probeDocx = BlockSwapper.saveTemp(o.pkg(), workDir, "batch-probe");
        List<PdfLines.Line> lines = PdfLines.extract(renderer.render(probeDocx, workDir));
        List<Integer> headerLines = new ArrayList<>();
        for (String text : headerTexts) {
            headerLines.add(linesOf(lines, text));
        }
        List<Integer> inlineLines = new ArrayList<>();
        for (String text : inlineTexts) {
            inlineLines.add(linesOf(lines, text));
        }
        return new ProbeResult(headerLines, inlineTexts, inlineLines);
    }

    /** A text's line count on a render, {@link Integer#MAX_VALUE} when it can't be anchored (as BlockSwapper). */
    private static int linesOf(List<PdfLines.Line> lines, String text) {
        int[] span = AnchorMeasurer.measureSpans(lines, List.of(text)).get(0);
        return span == null ? Integer.MAX_VALUE : span[1] - span[0] + 1;
    }

    /**
     * PHASE5_SPEC.md section 5.1 (B3): a job variant was validated in some editable job slot with
     * the same line count; if every such slot has the same width and formatting as {@code slot}, the
     * variant fits here by the same measurement, so it needs no render of its own.
     */
    private static boolean validatedInEquivalentSlot(int slot, Set<Integer> editableJob, Map<Integer, Integer> lineCounts,
            Map<Integer, Slot> baseSlotByIndex) throws TransformerException {
        Integer lines = lineCounts.get(slot);
        if (lines == null || !editableJob.contains(slot)) {
            return false;
        }
        String mine = signature(baseSlotByIndex.get(slot));
        boolean sawValidation = false;
        for (int other : editableJob) {
            if (lineCounts.get(other) == null || !lineCounts.get(other).equals(lines)) {
                continue;
            }
            sawValidation = true;
            if (!mine.equals(signature(baseSlotByIndex.get(other)))) {
                return false;
            }
        }
        return sawValidation;
    }

    /** A slot's paragraph with its text removed, plus its section's page setup: equal strings mean
     * equal text width and formatting, so equal line breaks. */
    private static String signature(Slot slot) throws TransformerException {
        Element copy = (Element) slot.element().cloneNode(true);
        NodeList texts = copy.getElementsByTagNameNS("*", "t");
        for (int i = 0; i < texts.getLength(); i++) {
            texts.item(i).setTextContent("");
        }
        Element body = DomUtil.firstChild(slot.element().getOwnerDocument().getDocumentElement(), "body");
        Element sectPr = DomUtil.firstChild(body, "sectPr");
        return serialize(copy) + "|" + (sectPr == null ? "" : serialize(sectPr));
    }

    private static String serialize(Node node) throws TransformerException {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter out = new StringWriter();
        transformer.transform(new DOMSource(node), new StreamResult(out));
        return out.toString();
    }

    /**
     * The per-position path writes its job-slot stage out and re-parses that file before any swap
     * edits it, so every later write goes through one serialize-and-parse round trip more than the
     * normalized document's own parse. The XML declaration's {@code standalone} flag depends on that
     * history, so the batched path takes the same round trip to produce the same bytes.
     */
    private static Opened stagedCopy(Path normalizedDocx, Path workDir) throws Exception {
        Opened first = open(normalizedDocx);
        first.pkg().writePart("word/document.xml", XmlSerialize.toBytes(first.doc()));
        Path staged = workDir.resolve("batched-stage-" + System.nanoTime() + ".docx");
        first.pkg().save(staged);
        return open(staged);
    }

    private static Opened open(Path docx) throws Exception {
        DocxPackage pkg = DocxPackage.open(docx);
        Document doc = SafeXml.parse(pkg.readPart("word/document.xml"));
        Element numberingRoot = pkg.hasPart("word/numbering.xml")
                ? SafeXml.parse(pkg.readPart("word/numbering.xml")).getDocumentElement() : null;
        Element stylesRoot = pkg.hasPart("word/styles.xml")
                ? SafeXml.parse(pkg.readPart("word/styles.xml")).getDocumentElement() : null;
        List<Slot> slots = BulletDetector.detect(doc, new NumberingResolver(numberingRoot, stylesRoot));
        return new Opened(pkg, doc, slots);
    }

    private static Result failed(String detail) {
        return new Result(false, detail, null, null);
    }
}
