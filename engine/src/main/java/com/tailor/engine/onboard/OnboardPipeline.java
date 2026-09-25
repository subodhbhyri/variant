package com.tailor.engine.onboard;

import com.tailor.engine.calibrate.BatchCalibrator;
import com.tailor.engine.calibrate.ProseSource;
import com.tailor.engine.docx.DocxPackage;
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
import com.tailor.engine.render.RenderException;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.slots.DocxBulletDetection;
import com.tailor.engine.slots.Slot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PHASE2_SPEC.md section 2.4: gate (2.1) -> normalize (Phase 1 sec 3 + 4.3) ->
 * page rule (4.1) -> detect -> lock (4.2) -> min-editable check -> calibrate
 * hints (Phase 1 7.3) -> write outputs.
 */
public final class OnboardPipeline {

    private static final int MAX_PAGES = 2;
    private static final int MIN_EDITABLE = 3;
    private static final int CALIBRATION_PROSE_LENGTH = 900;

    private final Renderer renderer;
    private final FontMap fontMap;

    public OnboardPipeline(Renderer renderer, FontMap fontMap) {
        this.renderer = renderer;
        this.fontMap = fontMap;
    }

    public OnboardReport run(byte[] upload, Path outDir) throws IOException, RenderException {
        Files.createDirectories(outDir);

        GateResult gate = UploadGate.check(upload);
        if (!gate.accepted()) {
            OnboardReport report = OnboardReport.rejected(gate.reason(), GateMessages.forReason(gate.reason()));
            report.writeTo(outDir.resolve("onboard.json"));
            return report;
        }

        Path workDir = Files.createTempDirectory("onboard-work");
        Path uploadDocx = workDir.resolve("upload.docx");
        DocxPackage sourcePkg = DocxPackage.fromGatedUpload(gate);
        sourcePkg.save(uploadDocx);

        Path normalizedDst = outDir.resolve("normalized.docx");
        PageRule pageRule = new PageRule(renderer);

        PageRule.Result pageResult;
        List<OnboardReport.FontSub> fontSubs = new ArrayList<>();
        List<String> unknownFonts = unknownFontsOf(sourcePkg);

        if (unknownFonts.isEmpty()) {
            try {
                pageResult = pageRule.apply(uploadDocx, fontMap, normalizedDst, workDir);
            } catch (TooManyPagesException e) {
                return rejectAndWrite(outDir, GateReason.TOO_MANY_PAGES, GateMessages.forReason(GateReason.TOO_MANY_PAGES));
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
                    return rejectAndWrite(outDir, GateReason.NEEDS_USER, GateMessages.needsUser(MAX_PAGES));
                }
                // Only Phase 1 font-map pairs are metric-compatible (PHASE2_SPEC.md 4.3 point 4).
                fontSubs.add(new OnboardReport.FontSub(font, chosenCandidate, false));
                resolved = trialResult;
            }
            pageResult = resolved;
        }

        List<Slot> slots = DocxBulletDetection.detect(normalizedDst);
        List<PdfLines.Line> lines = PdfLines.extract(pageResult.pdf());
        List<Locker.LockedSlot> locked = Locker.lock(slots, lines);
        int editableCount = (int) locked.stream().filter(Locker.LockedSlot::editable).count();

        if (editableCount < MIN_EDITABLE) {
            return rejectAndWrite(outDir, GateReason.TOO_FEW_EDITABLE, GateMessages.tooFewEditable(editableCount));
        }

        String prose = ProseSource.fromSlots(slots, CALIBRATION_PROSE_LENGTH);
        BatchCalibrator.Result calibration;
        try {
            calibration = BatchCalibrator.calibrate(normalizedDst, renderer, prose, workDir);
        } catch (Exception e) {
            throw new RenderException("calibration failed during onboarding", e);
        }

        Files.copy(pageResult.pdf(), outDir.resolve("preview.pdf"), StandardCopyOption.REPLACE_EXISTING);

        List<OnboardReport.SlotReport> slotReports = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            Locker.LockedSlot ls = locked.get(i);
            slotReports.add(new OnboardReport.SlotReport(
                    i, ls.slot().text(), calibration.lineCounts().get(i),
                    ls.editable(), ls.lockReason(), calibration.hints().get(i)));
        }

        OnboardReport report = OnboardReport.accepted(
                pageResult.pages(), pageResult.shrinkPt(), pageResult.squeezeRemoved(), pageResult.positionRemoved(),
                fontSubs, editableCount, slotReports, renderer.version());
        report.writeTo(outDir.resolve("onboard.json"));
        return report;
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

    private static OnboardReport rejectAndWrite(Path outDir, String reason, String message) throws IOException {
        OnboardReport report = OnboardReport.rejected(reason, message);
        report.writeTo(outDir.resolve("onboard.json"));
        return report;
    }
}
