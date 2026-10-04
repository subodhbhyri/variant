package com.tailor.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.generate.Intake;
import com.tailor.engine.generate.SlotReports;
import com.tailor.engine.match.IntakeTemplateBuilder;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code tailor intake-template <onboarded.docx> <out.json>} — P5-T8 operator tooling. Writes an
 * {@code intake.json} pre-filled from the onboarded resume: one section per job and per swappable
 * project position, its current header fields, {@code mode: "DETAILED"}, and an empty {@code
 * raw_text} for the operator to fill in before running {@code tailor generate}.
 */
@Command(name = "intake-template", description = "Writes an intake.json pre-filled from an onboarded resume.")
public final class IntakeTemplateCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Parameters(index = "0", description = "An already-onboarded (normalized) .docx")
    private Path onboardedPath;

    @Parameters(index = "1", description = "Output intake.json path")
    private Path outJson;

    @Override
    public Integer call() {
        try {
            Renderer renderer = new LibreOfficeRenderer();
            Path workDir = Files.createTempDirectory("intake-template-cli");
            List<OnboardReport.SlotReport> slotReports = SlotReports.build(onboardedPath, renderer, workDir);
            int editableCount = (int) slotReports.stream().filter(OnboardReport.SlotReport::editable).count();
            OnboardReport report = OnboardReport.accepted(
                    0, 0.0, 0, 0, 0, List.of(), editableCount, slotReports, renderer.version());

            Intake intake = IntakeTemplateBuilder.build(onboardedPath, report);
            List<IntakeTemplateBuilder.SectionSummary> summaries =
                    IntakeTemplateBuilder.summarize(onboardedPath, report);

            int totalSwappable = 0;
            for (IntakeTemplateBuilder.SectionSummary s : summaries) {
                System.out.printf("project section %-30s positions=%d swappable=%d%n",
                        s.heading(), s.positionCount(), s.swappableCount());
                totalSwappable += s.swappableCount();
                if (s.positionCount() > 0 && s.swappableCount() == 0) {
                    System.out.println("warning: project section \"" + s.heading()
                            + "\" has no swappable position (every position there is locked/headerless)");
                }
            }
            if (totalSwappable == 0) {
                System.out.println("warning: no swappable project position found anywhere in this resume");
            }

            if (outJson.getParent() != null) {
                Files.createDirectories(outJson.getParent());
            }
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(outJson.toFile(), intake);

            System.out.println("intake-template ok -> " + outJson);
            return 0;
        } catch (Exception e) {
            System.err.println("intake-template failed: " + e);
            return 1;
        }
    }
}
