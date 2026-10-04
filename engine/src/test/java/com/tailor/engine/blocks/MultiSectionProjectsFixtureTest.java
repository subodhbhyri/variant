package com.tailor.engine.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.CorpusPaths;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.generate.Intake;
import com.tailor.engine.generate.IntakeSection;
import com.tailor.engine.generate.SectionPositions;
import com.tailor.engine.golden.Phase3Fixtures;
import com.tailor.engine.match.IntakeTemplateBuilder;
import com.tailor.engine.match.Shapes;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * {@code fixtures/phase3/projects_synthetic_multi_section.docx}: a headerless bullets section
 * ("Open-Source Contributions", vocabulary-matched to {@code role: "projects"}, PHASE3_SPEC.md
 * section 2) ahead of the real "Projects" section, reproducing the private-run bug where {@code
 * BlockSwapper}/{@code BlocksAnalyzer}/{@code SectionPositions} each took only the <b>first</b>
 * {@code "projects"}-role section and so never saw "Projects"' own 4 real, swappable positions.
 * {@code tailor blocks}, {@code tailor intake-template}, a real swap and a measured {@link
 * Shapes} must all still find and use exactly those 4 positions, and "Open-Source Contributions"
 * itself must come out of every one of those untouched.
 */
@Tag("corpus")
class MultiSectionProjectsFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private static final String OPEN_SOURCE_HEADING = "Open-Source Contributions";
    private static final String PROJECTS_HEADING = "Projects";

    @Test
    void blocksIntakeTemplateSwapAndShapesAllUseOnlyTheProjectsSectionsPositions() throws Exception {
        Path fixturesDir = CorpusPaths.phase3FixturesDir();
        Phase3Fixtures.Expected expected = Phase3Fixtures.loadExpected(fixturesDir.resolve("expected.json"));

        Renderer renderer = new LibreOfficeRenderer();
        FontMap fontMap = FontMap.loadDefault();
        OnboardPipeline onboard = new OnboardPipeline(renderer, fontMap);
        Path outDir = Files.createTempDirectory("multi-section-fixture");
        byte[] upload = Files.readAllBytes(fixturesDir.resolve("projects_synthetic_multi_section.docx"));
        OnboardReport onboardReport = onboard.run(upload, outDir);
        assertTrue(onboardReport.accepted(), "fixture failed to onboard: " + onboardReport.reason());
        Path normalizedDocx = outDir.resolve("normalized.docx");

        // --- 1. tailor blocks: both projects-role sections detected, each with its own positions,
        //        and the flattened list is Open-Source's 1 (non-swappable) then Projects' 5. -------
        BlocksReport blocks = BlocksAnalyzer.analyze(normalizedDocx);
        List<String> headingsAndRoles = blocks.sections().stream()
                .map(sec -> sec.heading() + "/" + sec.role()).toList();
        assertEquals(List.of("Experience/experience", OPEN_SOURCE_HEADING + "/projects",
                PROJECTS_HEADING + "/projects", "Education/other"), headingsAndRoles, "sections");

        assertEquals(2, blocks.projectSections().size(), "number of projects-role sections");
        BlocksReport.ProjectSectionReport openSource = blocks.projectSections().get(0);
        BlocksReport.ProjectSectionReport projects = blocks.projectSections().get(1);
        assertEquals(OPEN_SOURCE_HEADING, openSource.heading());
        assertEquals(PROJECTS_HEADING, projects.heading());
        assertEquals(1, openSource.positions().size(), "Open-Source Contributions' own position count");
        assertFalse(openSource.positions().get(0).swappable(), "the headerless bullet list is never swappable");
        assertEquals("no_header", openSource.positions().get(0).reason());

        assertEquals(expected.positions().size(), projects.positions().size(), "Projects' own position count");
        for (int i = 0; i < expected.positions().size(); i++) {
            BlocksFixtureTest.assertPositionMatches(
                    "Projects.position[" + i + "]", expected.positions().get(i), projects.positions().get(i));
        }

        // positions() flattens every projects-role section in document order: Open-Source's 1,
        // then Projects' 5 -- nothing from either section is dropped.
        assertEquals(1 + expected.positions().size(), blocks.positions().size(), "flattened position count");

        // --- 2. tailor intake-template: only Projects' swappable positions get a section; the
        //        global index is the flattened one, so they start at project-1, not project-0,
        //        because Open-Source's own (non-swappable) position occupies global index 0. -----
        Intake intake = IntakeTemplateBuilder.build(normalizedDocx, onboardReport);
        List<String> projectIds = intake.sections().stream()
                .map(IntakeSection::id).filter(id -> id.startsWith("project-")).toList();
        assertEquals(List.of("project-1", "project-2", "project-3", "project-4"), projectIds,
                "only Projects' swappable positions get an intake section");

        List<IntakeTemplateBuilder.SectionSummary> summaries =
                IntakeTemplateBuilder.summarize(normalizedDocx, onboardReport);
        assertEquals(2, summaries.size());
        assertEquals(new IntakeTemplateBuilder.SectionSummary(OPEN_SOURCE_HEADING, 1, 0), summaries.get(0),
                "Open-Source Contributions has a position but none of it is swappable");
        assertEquals(new IntakeTemplateBuilder.SectionSummary(PROJECTS_HEADING, 5, 4), summaries.get(1),
                "Projects has 4 swappable positions (P4 stays locked, same as the single-section fixture)");

        // --- 3. a real swap: global index 1 is Projects' own old P0 (Open-Source occupies 0) -----
        LibraryProject.Library library = MAPPER.readValue(
                fixturesDir.resolve("library.json").toFile(), LibraryProject.Library.class);
        LibraryProject quill = library.projects().stream()
                .filter(p -> "quill".equals(p.id())).findFirst().orElseThrow();

        String openSourceTextBefore = openSourceBulletsText(normalizedDocx);

        DocxPackage basePkg = DocxPackage.open(normalizedDocx);
        Path workDir = Files.createTempDirectory("multi-section-swap");
        Path swappedDocx = workDir.resolve("swapped.docx");
        BlockSwapper.Result result = BlockSwapper.swap(basePkg, 1, quill, renderer, fontMap, workDir, swappedDocx);
        assertEquals(SwapOutcome.OK, result.outcome(), "swap into Projects' own first position");

        String openSourceTextAfter = openSourceBulletsText(swappedDocx);
        assertEquals(openSourceTextBefore, openSourceTextAfter,
                "Open-Source Contributions must stay completely untouched by a swap into Projects");

        // --- 4. a measured Shapes: every swappable position's home is "Projects", never
        //        Open-Source Contributions -- so match/assembly can only ever place a project
        //        there. --------------------------------------------------------------------------
        Shapes shapes = Shapes.measure(normalizedDocx, onboardReport);
        assertEquals(4, shapes.positions().size(), "swappable positions measured for match");
        for (Shapes.PositionShape ps : shapes.positions()) {
            assertEquals(PROJECTS_HEADING, ps.section(), ps.id() + " must belong to Projects");
        }
        assertTrue(shapes.lockedPositions().stream().anyMatch(ps -> OPEN_SOURCE_HEADING.equals(ps.section())),
                "Open-Source Contributions' own position is still reported, just never swappable");

        // Belt and suspenders on the raw SectionPositions this all comes from.
        SectionPositions positions = SectionPositions.detect(normalizedDocx, onboardReport);
        assertEquals(List.of(OPEN_SOURCE_HEADING, PROJECTS_HEADING, PROJECTS_HEADING, PROJECTS_HEADING,
                PROJECTS_HEADING, PROJECTS_HEADING), positions.projectPositionSections());
    }

    private static String openSourceBulletsText(Path docx) throws Exception {
        DocxPackage pkg = DocxPackage.open(docx);
        Document doc = SafeXml.parse(pkg.readPart("word/document.xml"));
        List<Section> sections = SectionDetector.detect(doc);
        Section openSource = sections.stream().filter(s -> OPEN_SOURCE_HEADING.equals(s.heading()))
                .findFirst().orElseThrow();
        StringBuilder sb = new StringBuilder();
        for (Element p : openSource.paragraphs()) {
            sb.append(DomUtil.allText(p)).append('\n');
        }
        return sb.toString();
    }
}
