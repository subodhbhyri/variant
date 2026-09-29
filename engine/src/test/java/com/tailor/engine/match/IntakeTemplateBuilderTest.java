package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.generate.Intake;
import com.tailor.engine.generate.IntakeSection;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * P5-T8 operator tooling: {@code tailor intake-template} on {@code
 * fixtures/phase3/projects_synthetic.docx} (the same fixture {@code shapes.json} was measured
 * on) gives one DETAILED, empty-raw-text section per job and per swappable project position —
 * P0-P3, never the locked P4, never a {@code project-new-N}.
 */
@Tag("corpus")
class IntakeTemplateBuilderTest {

    @Test
    void oneSectionPerJobAndSwappableProjectPositionNoLockedNoAdded() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        OnboardPipeline onboard = new OnboardPipeline(renderer, com.tailor.engine.fonts.FontMap.loadDefault());
        Path outDir = Files.createTempDirectory("intake-template-test");
        byte[] upload = Files.readAllBytes(CorpusPaths.phase3FixturesDir().resolve("projects_synthetic.docx"));
        OnboardReport report = onboard.run(upload, outDir);
        assertTrue(report.accepted(), "fixture failed to onboard: " + report.reason());
        Path normalizedDocx = outDir.resolve("normalized.docx");

        Intake intake = IntakeTemplateBuilder.build(normalizedDocx, report);

        List<String> ids = intake.sections().stream().map(IntakeSection::id).toList();
        assertEquals(List.of("job-0", "project-0", "project-1", "project-2", "project-3"), ids);

        for (IntakeSection s : intake.sections()) {
            assertEquals("DETAILED", s.mode(), s.id() + ": mode");
            assertEquals("", s.rawText(), s.id() + ": raw_text must start empty");
        }

        // P0-P2 are paragraph-kind: their header fields must be pre-filled from the resume.
        for (String id : List.of("project-0", "project-1", "project-2")) {
            IntakeSection s = intake.sections().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
            assertTrue(s.fields() != null && s.fields().title() != null && !s.fields().title().isBlank(),
                    id + ": title must be pre-filled from the resume's own header");
        }

        // job-0's own header fields must also be pre-filled.
        IntakeSection job0 = intake.sections().get(0);
        assertTrue(job0.fields() != null && job0.fields().title() != null && !job0.fields().title().isBlank());

        assertFalse(ids.contains("project-4"), "P4 is locked (not swappable) and must not get a section");
        assertTrue(ids.stream().noneMatch(id -> id.contains("new")), "no added projects");
    }
}
