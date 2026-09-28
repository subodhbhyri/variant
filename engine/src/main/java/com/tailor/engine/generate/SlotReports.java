package com.tailor.engine.generate;

import com.tailor.engine.calibrate.BatchCalibrator;
import com.tailor.engine.calibrate.ProseSource;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.layout.Locker;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.numbering.NumberingResolver;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.slots.DocxBulletDetection;
import com.tailor.engine.slots.Slot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;

/**
 * Rebuilds {@link OnboardReport.SlotReport}s (lines/editable/lockReason/hintChars, one per
 * bullet slot) for a docx that's already onboarded — the same construction {@code
 * OnboardPipeline} does at the end of onboarding, standalone, for {@code tailor generate}, which
 * takes an already-normalized docx rather than running the gate/font-normalize steps again.
 */
public final class SlotReports {

    private static final int CALIBRATION_PROSE_LENGTH = 900;

    private SlotReports() {
    }

    public static List<OnboardReport.SlotReport> build(Path normalizedDocx, Renderer renderer, Path workDir)
            throws Exception {
        List<Slot> slots = DocxBulletDetection.detect(normalizedDocx);
        Path baselinePdf = renderer.render(normalizedDocx, workDir);
        List<PdfLines.Line> lines = PdfLines.extract(baselinePdf);
        Map<Integer, String> glyphBySlotIndex = glyphsOf(normalizedDocx, slots);
        List<Locker.LockedSlot> locked = Locker.lock(slots, lines, glyphBySlotIndex);

        String prose = ProseSource.fromSlots(slots, CALIBRATION_PROSE_LENGTH);
        BatchCalibrator.Result calibration = BatchCalibrator.calibrate(normalizedDocx, renderer, prose, workDir);

        List<OnboardReport.SlotReport> slotReports = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            Locker.LockedSlot ls = locked.get(i);
            slotReports.add(new OnboardReport.SlotReport(
                    i, ls.slot().text(), calibration.lineCounts().get(i),
                    ls.editable(), ls.lockReason(), calibration.hints().get(i)));
        }
        return slotReports;
    }

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
}
