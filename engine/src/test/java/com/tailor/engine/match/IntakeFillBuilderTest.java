package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.CorpusPaths;
import com.tailor.engine.generate.Intake;
import com.tailor.engine.generate.IntakeFields;
import com.tailor.engine.generate.IntakeIO;
import com.tailor.engine.generate.IntakeSection;
import com.tailor.engine.generate.IntakeValidator;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** P5-T8 operator tooling: {@code tailor intake-fill}'s matching, mode-setting and
 * unmatched-reporting, on fixtures/phase5/intake_fill. */
class IntakeFillBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void matchesProjectsByTitleBeforeColonCaseInsensitiveAndSetsJobsExistingOnly() throws Exception {
        Path dir = CorpusPaths.phase5FixturesDir().resolve("intake_fill");
        Intake intake = IntakeIO.load(dir.resolve("intake_template_sample.json"));
        ProjectDataset.ProjectDatasets datasets = MAPPER.readValue(
                dir.resolve("project_datasets_sample.json").toFile(), ProjectDataset.ProjectDatasets.class);

        IntakeFillBuilder.Result result = IntakeFillBuilder.fill(intake, datasets);

        IntakeSection job0 = sectionById(result.intake(), "job-0");
        assertEquals("EXISTING_ONLY", job0.mode(), "job sections must become EXISTING_ONLY");

        IntakeSection beacon = sectionById(result.intake(), "project-0");
        assertEquals("DETAILED", beacon.mode());
        assertEquals("Beacon Analytics", beacon.fields().title(), "title is the part before the colon");
        assertEquals("Kotlin, Spark, Delta Lake, Airflow", beacon.fields().detail());
        assertEquals(1, beacon.fields().links().size());
        assertEquals("https://github.com/example/beacon", beacon.fields().links().get(0).url());
        assertTrue(beacon.rawText().startsWith("Built a real-time analytics dashboard"));

        IntakeSection ledger = sectionById(result.intake(), "project-1");
        assertEquals("DETAILED", ledger.mode());
        assertEquals("ledger sync", ledger.fields().title(),
                "match is case-insensitive, but the title set is the dataset's own casing");
        assertTrue(ledger.rawText().startsWith("Built a reconciliation service"));

        IntakeSection orphan = sectionById(result.intake(), "project-2");
        assertEquals("DETAILED", orphan.mode(), "unmatched section keeps intake-template's own mode");
        assertEquals("", orphan.rawText(), "unmatched section is left unchanged");

        assertEquals(List.of("project-2"), result.unmatchedSectionIds());
        assertEquals(List.of("Unmatched Dataset: nothing uses this"), result.unmatchedDatasetTitles());
    }

    @Test
    void everyFilledSectionPassesIntakeValidator() throws Exception {
        Path dir = CorpusPaths.phase5FixturesDir().resolve("intake_fill");
        Intake intake = IntakeIO.load(dir.resolve("intake_template_sample.json"));
        ProjectDataset.ProjectDatasets datasets = MAPPER.readValue(
                dir.resolve("project_datasets_sample.json").toFile(), ProjectDataset.ProjectDatasets.class);

        IntakeFillBuilder.Result result = IntakeFillBuilder.fill(intake, datasets);
        for (IntakeSection s : result.intake().sections()) {
            IntakeValidator.Result v = IntakeValidator.validate(s, 0);
            assertTrue(v.accepted(), s.id() + ": " + v.reason() + " - " + v.message());
        }
    }

    @Test
    void overLongRawTextFailsIntakeValidator() {
        Intake intake = new Intake(List.of(new IntakeSection(
                "project-0", "project", "DETAILED", new IntakeFields("Big Project", null, List.of(), null), "")));
        String longText = "word ".repeat(1501).strip();
        ProjectDataset.ProjectDatasets datasets = new ProjectDataset.ProjectDatasets(
                List.of(new ProjectDataset("Big Project: too much text", null, List.of(), longText)));

        IntakeFillBuilder.Result result = IntakeFillBuilder.fill(intake, datasets);
        IntakeSection filled = result.intake().sections().get(0);
        assertEquals(longText, filled.rawText());

        IntakeValidator.Result v = IntakeValidator.validate(filled, 0);
        assertFalse(v.accepted(), "1,500+ word raw_text must be refused");
        assertEquals("RAW_TEXT_TOO_LONG", v.reason());
    }

    private static IntakeSection sectionById(Intake intake, String id) {
        return intake.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }
}
