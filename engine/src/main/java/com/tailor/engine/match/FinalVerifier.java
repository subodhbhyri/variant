package com.tailor.engine.match;

import com.tailor.engine.blocks.LibraryProject;
import com.tailor.engine.blocks.Position;
import com.tailor.engine.calibrate.BatchValidator;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.generate.ModelResponse.BulletCandidate;
import com.tailor.engine.generate.SectionPositions;
import com.tailor.engine.measure.AnchorMeasurer;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.slots.BulletText;
import com.tailor.engine.slots.DocxBulletDetection;
import com.tailor.engine.slots.Slot;
import com.tailor.engine.verify.SlotEdit;
import com.tailor.engine.verify.VerifyReport;
import com.tailor.engine.verify.Verifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * PHASE5_SPEC.md section 5 (step 5.6, Opus follow-up): after {@link ResumeRenderer}'s two
 * verified stages, one more check of the finished document against the true original — every job
 * slot and every swapped position, anchored together as one {@link Verifier#verifyRegions} pass.
 * A stage-by-stage 0.5pt bound at each of N steps doesn't itself bound the total drift across all
 * N; this pass measures the whole thing at once, end to end.
 *
 * <p>Recomputes independently rather than reusing {@link ResumeRenderer}'s own stage-1 working
 * data: the chosen job/project bullet texts are re-derived from {@code resume}/{@code library}
 * (deterministic, so identical), and the PADDED/SUBSTITUTED classification for every bullet is
 * re-run through {@link BatchValidator} against the true baseline — the same deterministic,
 * render-based computation {@link com.tailor.engine.blocks.BlockSwapper} and stage 1 each used, so
 * it agrees with what they actually did without needing either to expose their internals. Header
 * and inline-paragraph "after" text is read directly off the finished document (padding filler
 * normalizes to nothing in extracted text, so this is padding-neutral) rather than re-derived,
 * since header stack-fit truncation is {@code BlockSwapper}'s own search and isn't replicated here.
 */
final class FinalVerifier {

    private FinalVerifier() {
    }

    static ResumeRenderer.RenderResult verify(Path normalizedDocx, Path finalDocx, OnboardReport report,
            AssembledResume resume, Map<String, BulletCandidate> jobCandidatesById, List<LibraryProject> library,
            Renderer renderer, FontMap fontMap, Path workDir) throws Exception {
        SectionPositions basePositions = SectionPositions.detect(normalizedDocx, report);
        SectionPositions finalPositions = SectionPositions.detect(finalDocx, report);
        if (basePositions.jobPositions().isEmpty()) {
            return ResumeRenderer.RenderResult.failed("no job position detected");
        }
        Map<String, LibraryProject> libraryById = new LinkedHashMap<>();
        for (LibraryProject p : library) {
            libraryById.put(p.id(), p);
        }
        Map<String, AssembledResume.ProjectAssignment> assignmentByPosition = new LinkedHashMap<>();
        for (AssembledResume.ProjectAssignment a : resume.projects()) {
            assignmentByPosition.put(a.position(), a);
        }

        List<Slot> allSlots = DocxBulletDetection.detect(normalizedDocx);
        List<String> originalTexts = allSlots.stream().map(Slot::text).toList();
        Path basePdf = renderer.render(normalizedDocx, workDir);
        List<PdfLines.Line> baseLines = PdfLines.extract(basePdf);
        Map<Integer, Integer> lineCounts = new HashMap<>(AnchorMeasurer.measure(baseLines, originalTexts));

        // --- job bullets ---------------------------------------------------------------------
        Position job0 = basePositions.jobPositions().get(0);
        List<Integer> jobSlotIndices = basePositions.bulletSlotIndices(job0);
        if (jobSlotIndices.size() != resume.job().size()) {
            return ResumeRenderer.RenderResult.failed("job has " + jobSlotIndices.size()
                    + " bullet slots but the resume has " + resume.job().size() + " job entries");
        }
        Map<Integer, BulletText> chosenBySlotIndex = new LinkedHashMap<>();
        for (int i = 0; i < jobSlotIndices.size(); i++) {
            String candidateId = resume.job().get(i);
            if (candidateId == null) {
                continue;
            }
            int slotIndex = jobSlotIndices.get(i);
            Integer targetLines = lineCounts.get(slotIndex);
            BulletCandidate candidate = jobCandidatesById.get(candidateId);
            if (candidate == null || targetLines == null) {
                return ResumeRenderer.RenderResult.failed("job slot " + slotIndex + ": could not re-derive its chosen text");
            }
            String text = candidate.variants().get(String.valueOf(targetLines));
            if (text == null) {
                return ResumeRenderer.RenderResult.failed(
                        "candidate " + candidateId + " has no variant at " + targetLines + " line(s)");
            }
            chosenBySlotIndex.put(slotIndex, new BulletText(text, List.of()));
        }

        // --- project bullets, headers and inline positions ------------------------------------
        List<Position> projectPositions = basePositions.projectPositions();
        List<Position> finalProjectPositions = finalPositions.projectPositions();
        List<HeaderRegion> headerRegions = new ArrayList<>();
        for (int i = 0; i < projectPositions.size(); i++) {
            Position p = projectPositions.get(i);
            String posId = "P" + i;
            AssembledResume.ProjectAssignment assignment = assignmentByPosition.get(posId);
            if (assignment == null) {
                continue; // untouched position: the layout check still protects its unchanged lines
            }
            LibraryProject project = libraryById.get(assignment.project());
            if (project == null) {
                return ResumeRenderer.RenderResult.failed("no library project with id " + assignment.project());
            }
            Position finalP = finalProjectPositions.get(i);

            if ("inline".equals(p.kind())) {
                String before = DomUtil.allText(p.block().inlineHeaderParagraph);
                String after = DomUtil.allText(finalP.block().inlineHeaderParagraph);
                int[] span = AnchorMeasurer.measureSpans(baseLines, List.of(before)).get(0);
                if (span == null) {
                    return ResumeRenderer.RenderResult.failed(posId + ": could not anchor its own text on the baseline");
                }
                int targetLines = span[1] - span[0] + 1;
                headerRegions.add(new HeaderRegion(bodyIndex(p.block().inlineHeaderParagraph), before, after,
                        targetLines, null)); // edit resolved below, once the final render is available
                continue;
            }

            Element header = p.block().headerParas.get(0);
            String before = DomUtil.allText(header);
            String after = DomUtil.allText(finalP.block().headerParas.get(0));
            int[] span = AnchorMeasurer.measureSpans(baseLines, List.of(before)).get(0);
            if (span == null) {
                return ResumeRenderer.RenderResult.failed(posId + ": could not anchor its own header on the baseline");
            }
            int targetLines = span[1] - span[0] + 1;
            headerRegions.add(new HeaderRegion(bodyIndex(header), before, after, targetLines, SlotEdit.SUBSTITUTED));

            List<Integer> bulletSlotIndices = basePositions.bulletSlotIndices(p);
            for (int j = 0; j < bulletSlotIndices.size(); j++) {
                int slotIndex = bulletSlotIndices.get(j);
                Integer targetBulletLines = lineCounts.get(slotIndex);
                if (targetBulletLines == null || j >= assignment.bullets().size()) {
                    return ResumeRenderer.RenderResult.failed(posId + " bullet " + j + ": could not re-derive its chosen text");
                }
                int bulletIndex = assignment.bullets().get(j);
                String text = project.bullets().get(bulletIndex).get(String.valueOf(targetBulletLines));
                if (text == null) {
                    return ResumeRenderer.RenderResult.failed(
                            posId + " bullet " + j + ": project " + project.id() + " has no variant at "
                                    + targetBulletLines + " line(s)");
                }
                chosenBySlotIndex.put(slotIndex, new BulletText(text, List.of()));
            }
        }

        // --- classify every chosen bullet as FITS or FITS_WITH_PADDING, against the baseline ---
        Map<Integer, List<BulletText>> candidatesPerSlot = new LinkedHashMap<>();
        for (var e : chosenBySlotIndex.entrySet()) {
            candidatesPerSlot.put(e.getKey(), List.of(e.getValue()));
        }
        List<BatchValidator.CandidateResult> results =
                BatchValidator.validate(normalizedDocx, renderer, lineCounts, Map.of(), candidatesPerSlot, workDir);
        Map<Integer, SlotEdit> bulletEdits = new LinkedHashMap<>();
        for (BatchValidator.CandidateResult r : results) {
            if (r.outcome() == BatchValidator.Outcome.FITS) {
                bulletEdits.put(r.slotIndex(), SlotEdit.SUBSTITUTED);
            } else if (r.outcome() == BatchValidator.Outcome.FITS_WITH_PADDING) {
                bulletEdits.put(r.slotIndex(), SlotEdit.PADDED);
            } else {
                return ResumeRenderer.RenderResult.failed("slot " + r.slotIndex() + ": " + r.outcome()
                        + " (unexpected on a final document that already passed its own stage's verify)");
            }
        }

        // --- resolve inline positions' PADDED/SUBSTITUTED by measuring the finished document ---
        List<HeaderRegion> resolvedHeaderRegions = new ArrayList<>(headerRegions.size());
        boolean needsFinalRender = headerRegions.stream().anyMatch(h -> h.edit() == null);
        List<PdfLines.Line> finalLines = needsFinalRender
                ? PdfLines.extract(renderer.render(finalDocx, workDir)) : null;
        for (HeaderRegion h : headerRegions) {
            if (h.edit() != null) {
                resolvedHeaderRegions.add(h);
                continue;
            }
            int[] span = AnchorMeasurer.measureSpans(finalLines, List.of(h.after())).get(0);
            if (span == null) {
                return ResumeRenderer.RenderResult.failed("could not anchor an inline position's text on the final render");
            }
            int measured = span[1] - span[0] + 1;
            SlotEdit edit = measured < h.targetLines() ? SlotEdit.PADDED : SlotEdit.SUBSTITUTED;
            resolvedHeaderRegions.add(new HeaderRegion(h.bodyIndex(), h.before(), h.after(), h.targetLines(), edit));
        }

        // --- assemble one ordered region list (bullets + headers/inline, by true document order) ---
        List<IndexedRegion> ordered = new ArrayList<>();
        for (Slot s : allSlots) {
            BulletText chosen = chosenBySlotIndex.get(s.index());
            Verifier.Region region = chosen == null ? Verifier.Region.unchanged(s.text())
                    : new Verifier.Region(s.text(), chosen.text(), lineCounts.get(s.index()), bulletEdits.get(s.index()));
            ordered.add(new IndexedRegion(bodyIndex(s.element()), region));
        }
        for (HeaderRegion h : resolvedHeaderRegions) {
            ordered.add(new IndexedRegion(h.bodyIndex(),
                    new Verifier.Region(h.before(), h.after(), h.targetLines(), h.edit())));
        }
        ordered.sort(Comparator.comparingInt(IndexedRegion::bodyIndex));
        List<Verifier.Region> regions = ordered.stream().map(IndexedRegion::region).toList();

        VerifyReport verifyReport = Verifier.verifyRegions(normalizedDocx, finalDocx, renderer, fontMap, regions, workDir);
        if (!verifyReport.ok()) {
            return ResumeRenderer.RenderResult.failed("final whole-document check: " + detailOf(verifyReport));
        }
        return ResumeRenderer.RenderResult.ok(finalDocx);
    }

    private record HeaderRegion(int bodyIndex, String before, String after, int targetLines, SlotEdit edit) {
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

    private static String detailOf(VerifyReport r) {
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
                return "region " + e.getKey() + " line count " + e.getValue().measured() + " != " + e.getValue().target();
            }
        }
        return "verify failed";
    }
}
