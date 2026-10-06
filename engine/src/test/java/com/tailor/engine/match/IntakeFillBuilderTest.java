package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.Map;
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
        assertEquals("Beacon Analytics: real-time analytics platform for retail", beacon.fields().title(),
                "the title is the dataset's full fields.title; only matching compares the part before the colon");
        assertEquals("Kotlin, Spark, Delta Lake, Airflow", beacon.fields().detail());
        assertEquals(1, beacon.fields().links().size());
        assertEquals("https://github.com/example/beacon", beacon.fields().links().get(0).url());
        assertTrue(beacon.rawText().startsWith("Built a real-time analytics dashboard"));

        IntakeSection ledger = sectionById(result.intake(), "project-1");
        assertEquals("DETAILED", ledger.mode());
        assertEquals("ledger sync: payments reconciliation", ledger.fields().title(),
                "match is case-insensitive, but the title set is the dataset's own casing");
        assertTrue(ledger.rawText().startsWith("Built a reconciliation service"));

        IntakeSection orphan = sectionById(result.intake(), "project-2");
        assertEquals("DETAILED", orphan.mode(), "unmatched section keeps intake-template's own mode");
        assertEquals("", orphan.rawText(), "unmatched section is left unchanged");

        assertEquals(List.of("project-2"), result.unmatchedSectionIds());
        assertEquals(List.of("Unused"), result.unmatchedDatasetNames(),
                "the map key (display name) is what's reported, never the title");
    }

    /** {@code --add-unmatched}: a dataset matching no existing section becomes an added project
     * instead of being merely reported. */
    @Test
    void addUnmatchedTurnsAnOrphanDatasetIntoAnAddedProject() throws Exception {
        Path dir = CorpusPaths.phase5FixturesDir().resolve("intake_fill");
        Intake intake = IntakeIO.load(dir.resolve("intake_template_sample.json"));
        ProjectDataset.ProjectDatasets datasets = MAPPER.readValue(
                dir.resolve("project_datasets_sample.json").toFile(), ProjectDataset.ProjectDatasets.class);

        IntakeFillBuilder.Result result = IntakeFillBuilder.fill(intake, datasets, true);

        assertEquals(List.of(), result.unmatchedDatasetNames(), "the orphan dataset is no longer unmatched");

        IntakeSection added = sectionById(result.intake(), "project-new-1");
        assertEquals("project", added.kind());
        assertEquals("DETAILED", added.mode());
        assertEquals("Unmatched Dataset: nothing uses this", added.fields().title());
        assertTrue(added.rawText().startsWith("orphan dataset text"));

        for (IntakeSection s : result.intake().sections()) {
            IntakeValidator.Result v = IntakeValidator.validate(s, 0);
            assertTrue(v.accepted(), s.id() + ": " + v.reason() + " - " + v.message());
        }
    }

    /** More orphan datasets than {@code MAX_ADDED_PROJECTS} allows: the ones that fit become
     * added projects, the rest stay reported as unmatched rather than silently dropped. */
    @Test
    void addUnmatchedStopsAtTheAddedProjectCapAndStillReportsTheRest() {
        Intake intake = new Intake(List.of());
        Map<String, ProjectDataset> projects = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= IntakeValidator.MAX_ADDED_PROJECTS + 2; i++) {
            projects.put("Orphan " + i, new ProjectDataset(
                    new IntakeFields("Orphan Project " + i, null, List.of(), null), "text " + i));
        }
        ProjectDataset.ProjectDatasets datasets = new ProjectDataset.ProjectDatasets(projects);

        IntakeFillBuilder.Result result = IntakeFillBuilder.fill(intake, datasets, true);

        long addedCount = result.intake().sections().stream()
                .filter(s -> s.id().startsWith("project-new-")).count();
        assertEquals(IntakeValidator.MAX_ADDED_PROJECTS, addedCount);
        assertEquals(2, result.unmatchedDatasetNames().size(),
                "datasets beyond the cap must stay reported, not silently dropped");
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
        ProjectDataset dataset = new ProjectDataset(
                new IntakeFields("Big Project: too much text", null, List.of(), null), longText);
        ProjectDataset.ProjectDatasets datasets =
                new ProjectDataset.ProjectDatasets(Map.of("Big Project", dataset));

        IntakeFillBuilder.Result result = IntakeFillBuilder.fill(intake, datasets);
        IntakeSection filled = result.intake().sections().get(0);
        assertEquals(longText, filled.rawText());

        IntakeValidator.Result v = IntakeValidator.validate(filled, 0);
        assertFalse(v.accepted(), "1,500+ word raw_text must be refused");
        assertEquals("RAW_TEXT_TOO_LONG", v.reason());
    }

    @Test
    void aMissingProjectsKeyGivesAClearErrorNotAnException() {
        Intake intake = new Intake(List.of());
        ProjectDataset.ProjectDatasets malformed = new ProjectDataset.ProjectDatasets(null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> IntakeFillBuilder.fill(intake, malformed));
        assertTrue(e.getMessage().contains(IntakeFillBuilder.EXPECTED_SHAPE),
                "error must name the expected shape: " + e.getMessage());
    }

    @Test
    void anEntryMissingFieldsTitleGivesAClearErrorNotAnException() {
        Intake intake = new Intake(List.of());
        ProjectDataset badEntry = new ProjectDataset(null, "some text");
        ProjectDataset.ProjectDatasets malformed =
                new ProjectDataset.ProjectDatasets(Map.of("Broken Entry", badEntry));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> IntakeFillBuilder.fill(intake, malformed));
        assertTrue(e.getMessage().contains("Broken Entry"));
        assertTrue(e.getMessage().contains(IntakeFillBuilder.EXPECTED_SHAPE));
    }

    private static IntakeSection sectionById(Intake intake, String id) {
        return intake.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }
}
