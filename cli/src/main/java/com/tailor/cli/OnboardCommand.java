package com.tailor.cli;

import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code tailor onboard <upload.docx> <outDir>} — PHASE2_SPEC.md section 2.4.
 * Writes {@code normalized.docx}, {@code preview.pdf} and {@code onboard.json}
 * (rejected uploads write only {@code onboard.json}).
 */
@Command(name = "onboard", description = "Gate, normalize, and preview an upload (spec section 2.4).")
public final class OnboardCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Uploaded .docx")
    private Path uploadPath;

    @Parameters(index = "1", description = "Output directory")
    private Path outDir;

    @Option(names = "--skip-calibration", description = "Reserved; calibration always runs for now.")
    private boolean skipCalibration;

    @Override
    public Integer call() {
        try {
            byte[] upload = Files.readAllBytes(uploadPath);
            Renderer renderer = new LibreOfficeRenderer();
            FontMap fontMap = FontMap.loadDefault();
            OnboardPipeline pipeline = new OnboardPipeline(renderer, fontMap);

            OnboardReport report = pipeline.run(upload, outDir);
            if (report.accepted()) {
                System.out.printf("accepted pages=%d shrink_pt=%.1f editable=%d%n",
                        report.pages(), report.shrinkPt(), report.editableCount());
                return 0;
            }
            System.out.println("rejected reason=" + report.reason() + " message=" + report.message());
            return 1;
        } catch (Exception e) {
            System.err.println("onboard failed: " + e);
            return 2;
        }
    }
}
