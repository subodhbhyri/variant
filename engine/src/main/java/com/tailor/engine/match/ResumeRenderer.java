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
import com.tailor.engine.generate.SkillsDictionary;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * PHASE5_SPEC.md section 5 (step 5.6): renders an {@link AssembledResume} onto a normalized
 * document in three verified stages, producing one final assembled document.
 *
 * <p>Stage 1 substitutes the job's own bullet slots (Phase 1 substitution + padding) and verifies
 * that in one {@link Verifier#verify} pass covering the whole document (every bullet slot,
 * touched or not) against the true original baseline — the same pattern used by
 * {@code NewTextVerifierCorpusTest}/{@code VerifierCorpusTest}. Stage 2 chains one
 * {@link BlockSwapper#swap} per project position (Phase 3 swaps: positions, stack fit, date
 * tabs), the same pattern {@code SwapCommand} uses — each call is independently, fully
 * self-verified against its own immediate input. Stage 3 ({@link FinalVerifier}) checks the
 * finished document once more, in one {@link Verifier#verifyRegions} pass spanning every job slot
 * and every swapped position anchored together against the true original baseline — a 0.5pt bound
 * held at each of stages 1-2's several steps doesn't itself bound the total drift across all of
 * them; this pass measures the whole thing at once, end to end. Anything that doesn't pass any
 * stage is dropped (never shown) and reported with a detail string.
 */
public final class ResumeRenderer {

    /** PHASE5_SPEC.md section 5: at most 3 re-solves after a swap first fails verification at a
     * given position, before that position is given up on (left at its own original content). */
    private static final int MAX_RESOLVES = 3;

    public record RenderResult(boolean ok, String detail, Path outputDocx) {
        static RenderResult ok(Path outputDocx) {
            return new RenderResult(true, null, outputDocx);
        }

        static RenderResult failed(String detail) {
            return new RenderResult(false, detail, null);
        }
    }

    /** {@code resume} is the resume actually rendered — its {@code projects()} reflect every
     * re-solve, and its {@code degraded()} lists any position given up on — or null when {@code
     * render} failed outright (no feasible assignment at all, or the final whole-document verify
     * failed: PHASE5_SPEC.md section 5's "a resume is dropped" case, never retried). */
    public record FailSoftResult(RenderResult render, AssembledResume resume) {
    }

    private ResumeRenderer() {
    }

    /**
     * PHASE5_SPEC.md section 5 (fail-soft assembly): renders resume #1 like {@link #render}, but
     * when a project's swap into a position fails verification, that (position, project) pairing
     * is marked infeasible and the whole project assignment is re-solved (same best-first rule,
     * {@link Assembler#assignProjects}) excluding it — up to {@value #MAX_RESOLVES} times at any
     * one position. A position that still can't be filled after that is dropped from the
     * assignment entirely (rendered with its own original, unswapped content) and recorded under
     * {@link AssembledResume#degraded()} with the swap's own failure detail as the reason. Only
     * the final whole-document {@link FinalVerifier} failing, or no feasible assignment existing
     * at all, drops the resume outright (never retried).
     */
    public static FailSoftResult renderFailSoft(Path normalizedDocx, OnboardReport report, Shapes shapes,
            List<String> job, List<LibraryProject> library, JobDescription jd, SkillsDictionary skills,
            Embedder embedder, Map<String, BulletCandidate> jobCandidatesById, Renderer renderer, FontMap fontMap,
            Path workDir, Path outputDocx) throws Exception {
        AssembledResume jobOnly = new AssembledResume(job, List.of(), null, null);
        Path jobStageOut = workDir.resolve("job-slots-" + System.nanoTime() + ".docx");
        RenderResult jobStage =
                substituteJobSlots(normalizedDocx, report, jobOnly, jobCandidatesById, renderer, fontMap, workDir,
                        jobStageOut);
        if (!jobStage.ok()) {
            return new FailSoftResult(jobStage, null);
        }

        Map<String, LibraryProject> byId = new LinkedHashMap<>();
        for (LibraryProject p : library) {
            byId.put(p.id(), p);
        }

        Path[] current = {jobStageOut};
        Function<AssembledResume.ProjectAssignment, String> attemptSwap = assignment -> {
            LibraryProject project = byId.get(assignment.project());
            Integer positionIndex = parsePositionIndex(assignment.position());
            LibraryProject selected = new LibraryProject(project.id(), project.title(), project.detail(),
                    project.links(), project.date(), selectedBullets(project, assignment), project.homeSection());
            try {
                DocxPackage basePkg = DocxPackage.open(current[0]);
                Path stepOut = workDir.resolve("step-" + assignment.position() + "-" + System.nanoTime() + ".docx");
                BlockSwapper.Result result =
                        BlockSwapper.swap(basePkg, positionIndex, selected, renderer, fontMap, workDir, stepOut);
                if (result.outcome() != SwapOutcome.OK) {
                    return result.outcome() + (result.detail() == null ? "" : " (" + result.detail() + ")");
                }
                current[0] = stepOut;
                return null;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        };

        List<AssembledResume.Degraded> degraded = new ArrayList<>();
        List<AssembledResume.ProjectAssignment> projects;
        try {
            projects = resolveAssignment(shapes, library, jd, skills, embedder, () -> current[0] = jobStageOut,
                    attemptSwap, degraded);
        } catch (CompletionException e) {
            throw (Exception) e.getCause();
        }
        if (projects == null) {
            return new FailSoftResult(RenderResult.failed("no feasible project assignment for every position"),
                    null);
        }

        AssembledResume forVerify = new AssembledResume(job, projects, null, null);
        RenderResult finalCheck = FinalVerifier.verify(normalizedDocx, current[0], report, forVerify,
                jobCandidatesById, library, renderer, fontMap, workDir);
        if (!finalCheck.ok()) {
            return new FailSoftResult(finalCheck, forVerify);
        }
        Files.copy(current[0], outputDocx, StandardCopyOption.REPLACE_EXISTING);
        AssembledResume finalResume = new AssembledResume(job, projects, null, null, degraded);
        return new FailSoftResult(RenderResult.ok(outputDocx), finalResume);
    }

    /**
     * PHASE5_SPEC.md section 5's re-solve/degrade bookkeeping, isolated from the real
     * DocxPackage/BlockSwapper I/O so it's directly testable (P5-T14) with a fake {@code
     * attemptSwap}: tries every position's assignment, in order, via {@code attemptSwap} (null
     * = that swap succeeded; non-null = its failure detail); on the first failure, marks that
     * (position, project) pairing infeasible and re-solves the whole assignment (same best-first
     * rule, excluding it) — {@code onPassStart} runs once per fresh attempt, before any swap in
     * it. A position still failing after {@value #MAX_RESOLVES} re-solves is dropped from the
     * assignment (appended to {@code degradedOut}) and the remaining positions are re-solved
     * without it. Returns null if some active position has no feasible project at all.
     */
    static List<AssembledResume.ProjectAssignment> resolveAssignment(Shapes shapes, List<LibraryProject> library,
            JobDescription jd, SkillsDictionary skills, Embedder embedder, Runnable onPassStart,
            Function<AssembledResume.ProjectAssignment, String> attemptSwap,
            List<AssembledResume.Degraded> degradedOut) {
        Set<String> infeasible = new LinkedHashSet<>();
        Map<String, String> degradedReason = new LinkedHashMap<>();
        Map<String, String> degradedProject = new LinkedHashMap<>();

        while (true) {
            List<Shapes.PositionShape> active =
                    shapes.positions().stream().filter(p -> !degradedReason.containsKey(p.id())).toList();
            List<AssembledResume.ProjectAssignment> projects = Assembler.attachStacks(
                    Assembler.assignProjects(active, library, jd, skills, embedder, Set.of(), null, infeasible),
                    library, jd, skills);
            if (projects.size() != active.size()) {
                return null;
            }

            onPassStart.run();
            AssembledResume.ProjectAssignment failedAssignment = null;
            String failedDetail = null;
            for (AssembledResume.ProjectAssignment assignment : projects) {
                String detail = attemptSwap.apply(assignment);
                if (detail != null) {
                    failedAssignment = assignment;
                    failedDetail = detail;
                    break;
                }
            }

            if (failedAssignment == null) {
                for (var e : degradedReason.entrySet()) {
                    degradedOut.add(new AssembledResume.Degraded(
                            e.getKey(), degradedProject.get(e.getKey()), e.getValue()));
                }
                return projects;
            }

            infeasible.add(failedAssignment.position() + "=" + failedAssignment.project());
            String prefix = failedAssignment.position() + "=";
            long failuresAtPosition = infeasible.stream().filter(p -> p.startsWith(prefix)).count();
            if (failuresAtPosition > MAX_RESOLVES) {
                degradedReason.put(failedAssignment.position(), failedDetail);
                degradedProject.put(failedAssignment.position(), failedAssignment.project());
            }
        }
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
            // BlockSwapper.swap() always takes a project's bullets in their own natural order
            // (project.bullets().get(j) for the position's j-th bullet slot) — it has no notion
            // of Assembler's own computed selection/ordering (assignment.bullets(), e.g. forge's
            // [0, 2, 1] for the platform fixture). Reordering/subsetting the bullets list here,
            // before the swap, is how that selection actually reaches the document.
            LibraryProject selected = new LibraryProject(project.id(), project.title(), project.detail(),
                    project.links(), project.date(), selectedBullets(project, assignment), project.homeSection());
            DocxPackage basePkg = DocxPackage.open(current);
            Path stepOut = workDir.resolve("step-" + assignment.position() + "-" + System.nanoTime() + ".docx");
            BlockSwapper.Result result =
                    BlockSwapper.swap(basePkg, positionIndex, selected, renderer, fontMap, workDir, stepOut);
            if (result.outcome() != SwapOutcome.OK) {
                String detail = result.detail() == null ? "" : " (" + result.detail() + ")";
                return RenderResult.failed(
                        assignment.position() + "=" + assignment.project() + ": " + result.outcome() + detail);
            }
            current = stepOut;
        }

        RenderResult finalCheck = FinalVerifier.verify(normalizedDocx, current, report, resume, jobCandidatesById,
                library, renderer, fontMap, workDir);
        if (!finalCheck.ok()) {
            return finalCheck;
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

    private static List<Map<String, String>> selectedBullets(LibraryProject project,
            AssembledResume.ProjectAssignment assignment) {
        List<Map<String, String>> out = new ArrayList<>(assignment.bullets().size());
        for (int idx : assignment.bullets()) {
            out.add(project.bullets().get(idx));
        }
        return out;
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
