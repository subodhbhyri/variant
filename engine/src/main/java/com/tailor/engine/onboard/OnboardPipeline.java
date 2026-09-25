package com.tailor.engine.onboard;

import com.tailor.engine.calibrate.BatchCalibrator;
import com.tailor.engine.calibrate.ProseSource;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.fonts.FontNormalizer;
import com.tailor.engine.gate.GateMessages;
import com.tailor.engine.gate.GateReason;
import com.tailor.engine.gate.GateResult;
import com.tailor.engine.gate.UploadGate;
import com.tailor.engine.layout.Locker;
import com.tailor.engine.layout.PageRule;
import com.tailor.engine.layout.TooManyPagesException;
import com.tailor.engine.layout.UnknownFonts;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.numbering.NumberingResolver;
import com.tailor.engine.render.RenderException;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.slots.DocxBulletDetection;
import com.tailor.engine.slots.Slot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.w3c.dom.Element;

/**
 * PHASE2_SPEC.md section 2.4: gate (2.1) -> normalize (Phase 1 sec 3 + 4.3) ->
 * page rule (4.1) -> detect -> lock (4.2) -> min-editable check -> calibrate
 * hints (Phase 1 7.3) -> write outputs.
 */
public final class OnboardPipeline {

    private static final int MAX_PAGES = 2;
    private static final int MIN_EDITABLE = 3;
    private static final int CALIBRATION_PROSE_LENGTH = 900;
    private static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(180);
    /** Bound on waiting for the background task to actually stop after being cancelled. */
    private static final Duration EXECUTOR_STOP_BOUND = Duration.ofSeconds(10);

    private final Renderer renderer;
    private final FontMap fontMap;
    private final Duration deadline;

    public OnboardPipeline(Renderer renderer, FontMap fontMap) {
        this(renderer, fontMap, DEFAULT_DEADLINE);
    }

    /** @param deadline overrides the 180s default (PHASE2_SPEC.md section 5) — for tests. */
    public OnboardPipeline(Renderer renderer, FontMap fontMap, Duration deadline) {
        this.renderer = renderer;
        this.fontMap = fontMap;
        this.deadline = deadline;
    }

    /** Everything the background task computes; {@code normalizedDocx}/{@code previewPdf} are
     * workDir-local paths, non-null only for an accepted outcome, copied to outDir by the
     * caller — never by the background task itself. */
    private record PipelineOutcome(OnboardReport report, Path normalizedDocx, Path previewPdf) {
        static PipelineOutcome rejected(String reason, String message) {
            return new PipelineOutcome(OnboardReport.rejected(reason, message), null, null);
        }
    }

    public OnboardReport run(byte[] upload, Path outDir) throws IOException, RenderException {
        Files.createDirectories(outDir);

        GateResult gate = UploadGate.check(upload);
        if (!gate.accepted()) {
            OnboardReport report = OnboardReport.rejected(gate.reason(), GateMessages.forReason(gate.reason()));
            report.writeTo(outDir.resolve("onboard.json"));
            return report;
        }

        // The whole rest of onboarding (unbounded: unknown fonts x candidates x shrink steps,
        // plus calibration) runs under a wall-clock deadline, on a background thread — the only
        // way to bound it regardless of *where* it's slow, since this isn't a simple loop we can
        // sprinkle elapsed-time checks into (PHASE2_SPEC.md section 5).
        //
        // The background task (runPipeline) never touches outDir — only workDir. If the deadline
        // fires, the calling thread has already stopped waiting on it; a background task that
        // hasn't noticed its interrupt yet and is still mid-flight must not be able to race the
        // PROCESSING_TIMEOUT write below by writing its own (stale) onboard.json afterward. Only
        // this method writes to outDir, and only after get() has returned.
        Path workDir = Files.createTempDirectory("onboard-work");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PipelineOutcome> future = executor.submit(() -> runPipeline(gate, workDir));
            PipelineOutcome outcome;
            try {
                outcome = future.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                OnboardReport report = OnboardReport.rejected(
                        GateReason.PROCESSING_TIMEOUT, GateMessages.forReason(GateReason.PROCESSING_TIMEOUT));
                report.writeTo(outDir.resolve("onboard.json"));
                return report;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                if (cause instanceof RenderException re) {
                    throw re;
                }
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new RenderException("onboarding failed", cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RenderException("onboarding interrupted", e);
            }
            return writeOutputs(outcome, outDir);
        } finally {
            executor.shutdownNow();
            try {
                executor.awaitTermination(EXECUTOR_STOP_BOUND.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Only now can nothing still be reading/writing under workDir (holds copies of the
            // user's resume at every intermediate stage) — delete it regardless of outcome.
            deleteRecursively(workDir);
        }
    }

    /** Copies the accepted outcome's artifacts into outDir and writes onboard.json — the only
     * outDir writes for a successful run, done by the calling thread after get() returns. */
    private static OnboardReport writeOutputs(PipelineOutcome outcome, Path outDir) throws IOException {
        if (outcome.normalizedDocx() != null) {
            Files.copy(outcome.normalizedDocx(), outDir.resolve("normalized.docx"), StandardCopyOption.REPLACE_EXISTING);
        }
        if (outcome.previewPdf() != null) {
            Files.copy(outcome.previewPdf(), outDir.resolve("preview.pdf"), StandardCopyOption.REPLACE_EXISTING);
        }
        outcome.report().writeTo(outDir.resolve("onboard.json"));
        return outcome.report();
    }

    private PipelineOutcome runPipeline(GateResult gate, Path workDir) throws IOException, RenderException {
        Path uploadDocx = workDir.resolve("upload.docx");
        DocxPackage sourcePkg = DocxPackage.fromGatedUpload(gate);
        sourcePkg.save(uploadDocx);

        Path normalizedDst = workDir.resolve("normalized.docx");
        PageRule pageRule = new PageRule(renderer);

        PageRule.Result pageResult;
        List<OnboardReport.FontSub> fontSubs = new ArrayList<>();
        List<String> unknownFonts = unknownFontsOf(sourcePkg);

        if (unknownFonts.isEmpty()) {
            try {
                pageResult = pageRule.apply(uploadDocx, fontMap, normalizedDst, workDir);
            } catch (TooManyPagesException e) {
                return PipelineOutcome.rejected(GateReason.TOO_MANY_PAGES, GateMessages.forReason(GateReason.TOO_MANY_PAGES));
            }
        } else {
            FontMap effectiveMap = fontMap;
            PageRule.Result resolved = null;
            for (String font : unknownFonts) {
                String family = UnknownFonts.familyOf(sourcePkg, font);
                List<String> candidates = UnknownFonts.candidatesForFamily(family);

                PageRule.Result trialResult = null;
                String chosenCandidate = null;
                for (String candidate : candidates) {
                    FontMap trialMap = effectiveMap.withAdditional(Map.of(font, candidate));
                    try {
                        trialResult = pageRule.apply(uploadDocx, trialMap, normalizedDst, workDir);
                        chosenCandidate = candidate;
                        effectiveMap = trialMap;
                        break;
                    } catch (TooManyPagesException tooMany) {
                        // try the next candidate
                    }
                }
                if (chosenCandidate == null) {
                    return PipelineOutcome.rejected(GateReason.NEEDS_USER, GateMessages.needsUser(MAX_PAGES));
                }
                // Only Phase 1 font-map pairs are metric-compatible (PHASE2_SPEC.md 4.3 point 4).
                fontSubs.add(new OnboardReport.FontSub(font, chosenCandidate, false));
                resolved = trialResult;
            }
            pageResult = resolved;
        }

        List<Slot> slots = DocxBulletDetection.detect(normalizedDst);
        List<PdfLines.Line> lines = PdfLines.extract(pageResult.pdf());
        Map<Integer, String> glyphBySlotIndex = glyphsOf(normalizedDst, slots);
        List<Locker.LockedSlot> locked = Locker.lock(slots, lines, glyphBySlotIndex);
        int editableCount = (int) locked.stream().filter(Locker.LockedSlot::editable).count();

        if (editableCount < MIN_EDITABLE) {
            return PipelineOutcome.rejected(GateReason.TOO_FEW_EDITABLE, GateMessages.tooFewEditable(editableCount));
        }

        String prose = ProseSource.fromSlots(slots, CALIBRATION_PROSE_LENGTH);
        BatchCalibrator.Result calibration;
        try {
            calibration = BatchCalibrator.calibrate(normalizedDst, renderer, prose, workDir);
        } catch (Exception e) {
            throw new RenderException("calibration failed during onboarding", e);
        }

        List<OnboardReport.SlotReport> slotReports = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            Locker.LockedSlot ls = locked.get(i);
            slotReports.add(new OnboardReport.SlotReport(
                    i, ls.slot().text(), calibration.lineCounts().get(i),
                    ls.editable(), ls.lockReason(), calibration.hints().get(i)));
        }

        OnboardReport report = OnboardReport.accepted(
                pageResult.pages(), pageResult.shrinkPt(), pageResult.squeezeRemoved(), pageResult.positionRemoved(),
                pageResult.trailingEmptyRemoved(), fontSubs, editableCount, slotReports, renderer.version());
        return new PipelineOutcome(report, normalizedDst, pageResult.pdf());
    }

    private List<String> unknownFontsOf(DocxPackage pkg) throws IOException {
        List<String> out = new ArrayList<>();
        for (String name : new FontNormalizer(fontMap, renderer).namedFonts(pkg)) {
            if (!fontMap.isKnown(name) && !UnknownFonts.isGlyphFont(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /** Each slot's own numbering-level glyph (PHASE2_SPEC.md 4.2), for {@link Locker}. */
    private static Map<Integer, String> glyphsOf(Path normalizedDocx, List<Slot> slots) throws IOException {
        DocxPackage pkg = DocxPackage.open(normalizedDocx);
        Element numberingRoot = pkg.hasPart("word/numbering.xml")
                ? SafeXml.parse(pkg.readPart("word/numbering.xml")).getDocumentElement() : null;
        Element stylesRoot = pkg.hasPart("word/styles.xml")
                ? SafeXml.parse(pkg.readPart("word/styles.xml")).getDocumentElement() : null;
        NumberingResolver numbering = new NumberingResolver(numberingRoot, stylesRoot);

        Map<Integer, String> glyphs = new HashMap<>();
        for (Slot s : slots) {
            numbering.resolveLvlText(s.element()).ifPresent(glyph -> glyphs.put(s.index(), glyph));
        }
        return glyphs;
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a)) // children before parents
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best effort; a leftover temp file here is not fatal
                        }
                    });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
