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
 *   <li>B4: the first round checks every header and inline block for fit, with each header's detail
 *       as it stands (or without it, when a stack fit is still to be searched); the stack-fit
 *       searches then advance together, one probe document per round;
 *   <li>the bullets of every project and unskipped job slot are validated as one batch, and the
 *       finished document is checked once, against the stored baseline, with that check's own render
 *       delivered as the PDF.
 * </ul>
 *
 * <p>A project placement that can't be made (a header or inline block too long for its line, a
 * project bullet too long) comes back as a {@link Rejection}: the caller re-solves the assignment
 * without that pairing and batches again, as the fail-soft path does. Anything else the batch can't
 * do in one pass returns {@code ok=false} without a rejection, and the caller runs the per-position
 * path. Nothing unverified is ever delivered.
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

    /** A placement the batch can't make: the position id ("P0"), the project id, and why. */
    public record Rejection(String position, String project, String reason) {
    }

    /** When {@code ok}, {@code outputDocx} and {@code pdf} are set; otherwise {@code detail} says why, and
     * {@code rejection} is set when the cause is one project placement (see the class comment). */
    public record Result(boolean ok, String detail, Path outputDocx, Path pdf, Rejection rejection) {
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
        Map<Integer, Planned> ownerBySlot = new HashMap<>();
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
                Planned inline = planInline(index, p, project, baseline, lineCounts);
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
            Planned header1 = new Planned(index, project, false, originalHeader, span[1] - span[0] + 1, hasDetail,
                    isTab, dateEdge, BlockSwapper.bodyChildIndex(pdoc, header), bulletSlots, null, null);
            planned.add(header1);
            for (int slot : bulletSlots) {
                ownerBySlot.put(slot, header1);
            }
        }

        // --- B3: which job bullets need a render of their own ---------------------------------------
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

        // --- headers and inline blocks: each one's fit, in the first round ----------------------------
        Map<Integer, String> detailByIndex = new LinkedHashMap<>();
        Map<Integer, List<String>> itemsByIndex = new HashMap<>();
        Map<Integer, int[]> searchByIndex = new LinkedHashMap<>();    // index -> {lo, hi, best}
        Set<String> otherTexts = new HashSet<>(allSlots.stream().map(Slot::text).toList());
        List<Planned> inlinePlans = new ArrayList<>();
        List<HeaderProbe> round0 = new ArrayList<>();
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
            // Round one probes each header at the least it can be: with no detail where a stack fit is to be
            // searched (the search only ever shortens it), and as it stands otherwise.
            round0.add(new HeaderProbe(pl, searchByIndex.containsKey(pl.index()) ? null : detailByIndex.get(pl.index())));
        }
        ProbeResult first = t.time("header and inline check",
                () -> measureProbes(normalizedDocx, round0, inlinePlans, otherTexts, renderer, workDir));
        if (first == null) {
            return failed("probe texts are not uniquely anchored");
        }
        for (int i = 0; i < round0.size(); i++) {
            Planned pl = round0.get(i).planned();
            int lines = first.headerLines().get(i);
            if (lines > pl.targetLines()) {
                String reason = searchByIndex.containsKey(pl.index())
                        ? "header can't fit its line even without its detail (needs " + lines + ", has "
                                + pl.targetLines() + ")"
                        : "header with its detail can't fit its line (needs " + lines + ", has " + pl.targetLines() + ")";
                return rejected(pl, reason);
            }
        }
        Map<Integer, SlotEdit> inlineEdits = new HashMap<>();
        Map<Integer, Integer> inlinePads = new HashMap<>();
        Map<Integer, String> inlineNewText = new HashMap<>();
        for (int k = 0; k < inlinePlans.size(); k++) {
            Planned pl = inlinePlans.get(k);
            int lines = first.inlineLines().get(k);
            if (lines == Integer.MAX_VALUE || lines > pl.targetLines()) {
                return rejected(pl, "inline block can't fit its " + pl.targetLines() + " line(s)");
            }
            inlineNewText.put(pl.index(), first.inlineTexts().get(k));
            inlineEdits.put(pl.index(), lines < pl.targetLines() ? SlotEdit.PADDED : SlotEdit.SUBSTITUTED);
            if (lines < pl.targetLines()) {
                inlinePads.put(pl.index(), pl.targetLines() - lines);
            }
        }

        // --- B4: the stack-fit searches, advancing together ----------------------------------------
        // Each round probes every open search's midpoint prefix in one document; the fit is monotone in the prefix.
        int round = 0;
        while (!searchByIndex.isEmpty()) {
            List<HeaderProbe> probes = new ArrayList<>();
            List<Integer> mids = new ArrayList<>();
            for (Planned pl : planned) {
                int[] s = searchByIndex.get(pl.index());
                if (s == null) {
                    continue;
                }
                int mid = (s[0] + s[1]) / 2;
                probes.add(new HeaderProbe(pl, String.join(", ", itemsByIndex.get(pl.index()).subList(0, mid))));
                mids.add(mid);
            }
            round++;
            final List<HeaderProbe> roundProbes = probes;
            ProbeResult measured = t.time("stack fit round " + round,
                    () -> measureProbes(normalizedDocx, roundProbes, List.of(), otherTexts, renderer, workDir));
            if (measured == null) {
                return failed("stack fit probes are not uniquely anchored");
            }
            for (int i = 0; i < probes.size(); i++) {
                HeaderProbe probe = probes.get(i);
                int[] s = searchByIndex.get(probe.planned().index());
                int mid = mids.get(i);
                if (measured.headerLines().get(i) <= probe.planned().targetLines()) {
                    s[2] = mid;
                    s[0] = mid + 1;
                } else {
                    s[1] = mid - 1;
                }
            }
            // A search that has run out of prefixes settles here: its best prefix, or no detail (which round one
            // already measured to fit).
            for (Planned pl : planned) {
                int[] s = searchByIndex.get(pl.index());
                if (s == null || s[0] <= s[1]) {
                    continue;
                }
                searchByIndex.remove(pl.index());
                detailByIndex.put(pl.index(),
                        s[2] > 0 ? String.join(", ", itemsByIndex.get(pl.index()).subList(0, s[2])) : null);
            }
        }

        // --- bullets: one batch for every project bullet and unskipped job bullet ---------------------
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
                    Planned owner = ownerBySlot.get(r.slotIndex());
                    if (owner == null) {
                        return failed("job slot " + r.slotIndex() + ": " + r.outcome());
                    }
                    return rejected(owner, "bullet " + (owner.bulletSlots().indexOf(r.slotIndex()) + 1) + " is "
                            + r.outcome() + " at " + lineCounts.get(r.slotIndex()) + " line(s)");
                }
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
        return new Result(true, null, outputDocx, verified.pdf(), null);
    }

    /** An inline block's plan: its rewritten bullets and header, and the line count it must keep. */
    private static Planned planInline(int index, Position p, LibraryProject project, StoredBaseline baseline,
            Map<Integer, Integer> lineCounts) {
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
        if (all.isEmpty()) {
            return new ProbeResult(List.of(), List.of(), List.of());
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
        return new Result(false, detail, null, null, null);
    }

    /** A placement that can't be made: the batch stops here, naming the position and project. */
    private static Result rejected(Planned pl, String reason) {
        return new Result(false, "P" + pl.index() + " with " + pl.project().id() + ": " + reason, null, null,
                new Rejection("P" + pl.index(), pl.project().id(), reason));
    }
}
