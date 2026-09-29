package com.tailor.engine.match;

import com.tailor.engine.blocks.BlockSwapper;
import com.tailor.engine.blocks.LibraryProject;
import com.tailor.engine.blocks.Position;
import com.tailor.engine.blocks.SwapOutcome;
import com.tailor.engine.calibrate.BatchValidator;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.docx.XmlSerialize;
import com.tailor.engine.edit.Padder;
import com.tailor.engine.edit.Substituter;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.generate.ModelResponse.BulletCandidate;
import com.tailor.engine.generate.SectionPositions;
import com.tailor.engine.measure.AnchorMeasurer;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.numbering.NumberingResolver;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.slots.BulletDetector;
import com.tailor.engine.slots.BulletText;
import com.tailor.engine.slots.DocxBulletDetection;
import com.tailor.engine.slots.Slot;
import com.tailor.engine.verify.SlotEdit;
import com.tailor.engine.verify.VerifyReport;
import com.tailor.engine.verify.Verifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * PHASE5_SPEC.md section 5 (step 5.6): renders an {@link AssembledResume} onto a normalized
 * document in two verified stages, producing one final assembled document.
 *
 * <p>Stage 1 substitutes the job's own bullet slots (Phase 1 substitution + padding) and verifies
 * that in one {@link Verifier#verify} pass covering the whole document (every bullet slot,
 * touched or not) against the true original baseline — the same pattern used by
 * {@code NewTextVerifierCorpusTest}/{@code VerifierCorpusTest}. Stage 2 chains one
 * {@link BlockSwapper#swap} per project position (Phase 3 swaps: positions, stack fit, date
 * tabs), the same pattern {@code SwapCommand} uses — each call is independently, fully
 * self-verified against its own immediate input. The result of stage 2 is one document that
 * passed the Verifier at every step of its construction; anything that doesn't is dropped
 * (never shown) and reported with a detail string.
 */
public final class ResumeRenderer {

    public record RenderResult(boolean ok, String detail, Path outputDocx) {
        static RenderResult ok(Path outputDocx) {
            return new RenderResult(true, null, outputDocx);
        }

        static RenderResult failed(String detail) {
            return new RenderResult(false, detail, null);
        }
    }

    private ResumeRenderer() {
    }

    public static RenderResult render(Path normalizedDocx, OnboardReport report, AssembledResume resume,
            Map<String, BulletCandidate> jobCandidatesById, List<LibraryProject> library, Renderer renderer,
            FontMap fontMap, Path workDir, Path outputDocx) throws Exception {
        Path jobStageOut = workDir.resolve("job-slots-" + System.nanoTime() + ".docx");
        RenderResult jobStage =
                substituteJobSlots(normalizedDocx, report, resume, jobCandidatesById, renderer, fontMap, workDir,
                        jobStageOut);
        if (!jobStage.ok()) {
            return jobStage;
        }

        Map<String, LibraryProject> byId = new LinkedHashMap<>();
        for (LibraryProject p : library) {
            byId.put(p.id(), p);
        }

        Path current = jobStageOut;
        for (AssembledResume.ProjectAssignment assignment : resume.projects()) {
            LibraryProject project = byId.get(assignment.project());
            if (project == null) {
                return RenderResult.failed("no library project with id " + assignment.project());
            }
            Integer positionIndex = parsePositionIndex(assignment.position());
            if (positionIndex == null) {
                return RenderResult.failed("position id must look like P0, P1, ... (got " + assignment.position() + ")");
            }
            DocxPackage basePkg = DocxPackage.open(current);
            Path stepOut = workDir.resolve("step-" + assignment.position() + "-" + System.nanoTime() + ".docx");
            BlockSwapper.Result result =
                    BlockSwapper.swap(basePkg, positionIndex, project, renderer, fontMap, workDir, stepOut);
            if (result.outcome() != SwapOutcome.OK) {
                String detail = result.detail() == null ? "" : " (" + result.detail() + ")";
                return RenderResult.failed(
                        assignment.position() + "=" + assignment.project() + ": " + result.outcome() + detail);
            }
            current = stepOut;
        }

        Files.copy(current, outputDocx, StandardCopyOption.REPLACE_EXISTING);
        return RenderResult.ok(outputDocx);
    }

    private static RenderResult substituteJobSlots(Path normalizedDocx, OnboardReport report, AssembledResume resume,
            Map<String, BulletCandidate> jobCandidatesById, Renderer renderer, FontMap fontMap, Path workDir,
            Path stageOut) throws Exception {
        SectionPositions positions = SectionPositions.detect(normalizedDocx, report);
        if (positions.jobPositions().isEmpty()) {
            return RenderResult.failed("no job position detected");
        }
        Position job0 = positions.jobPositions().get(0);
        List<Integer> jobSlotIndices = positions.bulletSlotIndices(job0);
        if (jobSlotIndices.size() != resume.job().size()) {
            return RenderResult.failed("job has " + jobSlotIndices.size() + " bullet slots but the resume has "
                    + resume.job().size() + " job entries");
        }

        List<Slot> allSlots = DocxBulletDetection.detect(normalizedDocx);
        List<String> originalTexts = allSlots.stream().map(Slot::text).toList();
        Path basePdf = renderer.render(normalizedDocx, workDir);
        Map<Integer, Integer> lineCounts =
                new HashMap<>(AnchorMeasurer.measure(PdfLines.extract(basePdf), originalTexts));

        Map<Integer, BulletText> chosenBySlotIndex = new LinkedHashMap<>();
        for (int i = 0; i < jobSlotIndices.size(); i++) {
            String candidateId = resume.job().get(i);
            if (candidateId == null) {
                continue; // keep the original bullet
            }
            int slotIndex = jobSlotIndices.get(i);
            Integer targetLines = lineCounts.get(slotIndex);
            if (targetLines == null) {
                return RenderResult.failed("job slot " + slotIndex + " could not be measured on the baseline render");
            }
            BulletCandidate candidate = jobCandidatesById.get(candidateId);
            if (candidate == null) {
                return RenderResult.failed("no stored job candidate with id " + candidateId);
            }
            String text = candidate.variants().get(String.valueOf(targetLines));
            if (text == null) {
                return RenderResult.failed(
                        "candidate " + candidateId + " has no variant at " + targetLines + " line(s)");
            }
            chosenBySlotIndex.put(slotIndex, new BulletText(text, List.of()));
        }

        Map<Integer, List<BulletText>> candidatesPerSlot = new LinkedHashMap<>();
        for (var e : chosenBySlotIndex.entrySet()) {
            candidatesPerSlot.put(e.getKey(), List.of(e.getValue()));
        }
        List<BatchValidator.CandidateResult> results =
                BatchValidator.validate(normalizedDocx, renderer, lineCounts, Map.of(), candidatesPerSlot, workDir);

        Map<Integer, Integer> padBySlotIndex = new LinkedHashMap<>();
        for (BatchValidator.CandidateResult r : results) {
            switch (r.outcome()) {
                case FITS -> {
                }
                case FITS_WITH_PADDING -> padBySlotIndex.put(r.slotIndex(), lineCounts.get(r.slotIndex()) - r.measuredLines());
                default -> {
                    return RenderResult.failed("job slot " + r.slotIndex() + ": " + r.outcome());
                }
            }
        }

        DocxPackage pkg = DocxPackage.open(normalizedDocx);
        Document doc = SafeXml.parse(pkg.readPart("word/document.xml"));
        Element numberingRoot = pkg.hasPart("word/numbering.xml")
                ? SafeXml.parse(pkg.readPart("word/numbering.xml")).getDocumentElement() : null;
        Element stylesRoot = pkg.hasPart("word/styles.xml")
                ? SafeXml.parse(pkg.readPart("word/styles.xml")).getDocumentElement() : null;
        List<Slot> fresh = BulletDetector.detect(doc, new NumberingResolver(numberingRoot, stylesRoot));

        List<String> assembledTexts = new ArrayList<>();
        Map<Integer, SlotEdit> edits = new LinkedHashMap<>();
        for (Slot s : fresh) {
            BulletText c = chosenBySlotIndex.get(s.index());
            if (c == null) {
                assembledTexts.add(s.text());
                continue;
            }
            Substituter.substitute(s.element(), c);
            Integer pad = padBySlotIndex.get(s.index());
            if (pad != null && pad > 0) {
                Padder.pad(s.element(), pad);
                edits.put(s.index(), SlotEdit.PADDED);
            } else {
                edits.put(s.index(), SlotEdit.SUBSTITUTED);
            }
            assembledTexts.add(c.text());
        }
        pkg.writePart("word/document.xml", XmlSerialize.toBytes(doc));
        pkg.save(stageOut);

        VerifyReport verifyReport =
                Verifier.verify(normalizedDocx, stageOut, renderer, fontMap, lineCounts, assembledTexts, edits, workDir);
        if (!verifyReport.ok()) {
            return RenderResult.failed("job slots: " + detailOf(verifyReport));
        }
        return RenderResult.ok(stageOut);
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
                return "slot " + e.getKey() + " line count " + e.getValue().measured() + " != " + e.getValue().target();
            }
        }
        return "verify failed";
    }

    private static Integer parsePositionIndex(String key) {
        if (key == null || key.length() < 2 || key.charAt(0) != 'P') {
            return null;
        }
        try {
            return Integer.parseInt(key.substring(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
