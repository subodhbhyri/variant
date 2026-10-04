package com.tailor.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.generate.Intake;
import com.tailor.engine.generate.IntakeIO;
import com.tailor.engine.generate.IntakeSection;
import com.tailor.engine.generate.IntakeValidator;
import com.tailor.engine.match.IntakeFillBuilder;
import com.tailor.engine.match.ProjectDataset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code tailor intake-fill <intake.json> <datasets.json> <out.json>} — P5-T8 operator tooling.
 * Fills an {@code intake-template} output from the operator's own {@code project_datasets.json}
 * ({@link IntakeFillBuilder}): matches each project section to a dataset by title, sets {@code
 * mode: DETAILED} with its {@code raw_text}/fields, and sets every job section to {@code
 * EXISTING_ONLY}. Reports any project section or dataset left unmatched. Every filled section is
 * re-validated (PHASE4_SPEC.md section 1: the 1,500-word raw-text limit; links through {@code
 * LinkValidator}) before anything is written — a failure here refuses the whole file rather than
 * writing something {@code tailor generate} would only reject later.
 */
@Command(name = "intake-fill", description = "Fills an intake-template output from the operator's own project datasets.")
public final class IntakeFillCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Parameters(index = "0", description = "intake.json (an intake-template output)")
    private Path intakeJsonPath;

    @Parameters(index = "1", description = "project_datasets.json (the operator's own raw material per project)")
    private Path datasetsJsonPath;

    @Parameters(index = "2", description = "Output intake.json path")
    private Path outJson;

    @Override
    public Integer call() {
        try {
            Intake intake = IntakeIO.load(intakeJsonPath);

            ProjectDataset.ProjectDatasets datasets;
            try {
                datasets = MAPPER.readValue(datasetsJsonPath.toFile(), ProjectDataset.ProjectDatasets.class);
            } catch (Exception e) {
                System.err.println("intake-fill failed: " + datasetsJsonPath + " must have the shape "
                        + IntakeFillBuilder.EXPECTED_SHAPE);
                return 1;
            }

            IntakeFillBuilder.Result result;
            try {
                result = IntakeFillBuilder.fill(intake, datasets);
            } catch (IllegalArgumentException e) {
                System.err.println("intake-fill failed: " + e.getMessage());
                return 1;
            }

            for (IntakeSection section : result.intake().sections()) {
                IntakeValidator.Result v = IntakeValidator.validate(section, 0);
                if (!v.accepted()) {
                    System.err.println(
                            "intake-fill failed: " + section.id() + ": " + v.reason() + " - " + v.message());
                    return 1;
                }
            }

            if (!result.unmatchedSectionIds().isEmpty()) {
                System.out.println("unmatched project sections (no dataset found): "
                        + String.join(", ", result.unmatchedSectionIds()));
            }
            if (!result.unmatchedDatasetNames().isEmpty()) {
                System.out.println("unmatched datasets (no project section found): "
                        + String.join(", ", result.unmatchedDatasetNames()));
            }

            if (outJson.getParent() != null) {
                Files.createDirectories(outJson.getParent());
            }
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(outJson.toFile(), result.intake());

            System.out.println("intake-fill ok -> " + outJson);
            return 0;
        } catch (Exception e) {
            System.err.println("intake-fill failed: " + e);
            return 1;
        }
    }
}
