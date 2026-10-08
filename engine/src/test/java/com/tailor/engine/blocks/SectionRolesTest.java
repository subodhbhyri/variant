package com.tailor.engine.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailor.engine.CorpusPaths;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.onboard.OnboardReport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * PHASE6_SPEC.md revision 3 (role overrides): one resolver decides every section's role. With no overrides it is
 * exactly the vocabulary's answer, so nothing else in the engine changes; an override for a heading is honoured by
 * section detection, block analysis and position numbering.
 */
class SectionRolesTest {

    private static final String SYNTHETIC = "projects_synthetic.docx";

    private static Path fixture() {
        return CorpusPaths.phase3FixturesDir().resolve(SYNTHETIC);
    }

    private static List<Section> detect(Path docx, SectionRoles roles) throws Exception {
        DocxPackage pkg = DocxPackage.open(docx);
        return SectionDetector.detect(SafeXml.parse(pkg.readPart("word/document.xml")), roles);
    }

    private static Map<String, String> rolesOf(List<Section> sections) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Section s : sections) {
            out.put(s.heading(), s.role());
        }
        return out;
    }

    /** A copy of the synthetic fixture whose "Projects" heading reads "Selected Work" (no vocabulary word at all). */
    private static Path withHeadingRenamed(String from, String to) throws Exception {
        DocxPackage pkg = DocxPackage.open(fixture());
        String xml = new String(pkg.readPart("word/document.xml"), StandardCharsets.UTF_8);
        String marker = ">" + from + "<";
        assertTrue(xml.contains(marker), "the fixture has a heading run reading " + from);
        pkg.writePart("word/document.xml", xml.replace(marker, ">" + to + "<").getBytes(StandardCharsets.UTF_8));
        Path out = Files.createTempFile("renamed-heading", ".docx");
        pkg.save(out);
        return out;
    }

    @Test
    void withNoOverridesTheAnswerIsExactlyTheVocabularys() throws Exception {
        for (String heading : List.of("Projects", "PROJECTS", "Work Experience", "Education", "Technical Skills",
                "Selected Work", "Open Source Contributions", "Internship", "Awards")) {
            assertEquals(Vocab.roleOf(heading).orElse("other"), SectionRoles.NONE.resolve(heading), heading);
            assertEquals(Vocab.roleOf(heading).isPresent(), SectionRoles.NONE.isHeadingText(heading), heading);
        }
        assertTrue(SectionRoles.of(null).isEmpty());
        assertTrue(SectionRoles.of(Map.of()).isEmpty());
        assertEquals(rolesOf(detect(fixture(), SectionRoles.NONE)), rolesOf(detect(fixture(), SectionRoles.of(Map.of()))));
    }

    @Test
    void anOverrideAppliesToItsHeadingIgnoringCaseAndSpacing() {
        SectionRoles roles = SectionRoles.of(Map.of("Selected   Work", "projects"));
        assertEquals("projects", roles.resolve("selected work"));
        assertEquals("projects", roles.resolve("  SELECTED WORK "));
        assertEquals("other", roles.resolve("Hobbies"));
        assertEquals("experience", roles.resolve("Experience"));
        assertTrue(roles.isHeadingText("Selected Work"), "a named heading counts as a heading even with no vocabulary word");
    }

    @Test
    void anOverrideCanReplaceTheVocabularysSuggestion() {
        SectionRoles roles = SectionRoles.of(Map.of("Projects", "other", "Education", "experience"));
        assertEquals("other", roles.resolve("Projects"));
        assertEquals("experience", roles.resolve("EDUCATION"));
        assertEquals("experience", roles.resolve("Work Experience"));
    }

    @Test
    void badRolesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> SectionRoles.of(Map.of("Projects", "hobby")));
        assertThrows(IllegalArgumentException.class, () -> SectionRoles.parse("Projects"));
        assertThrows(IllegalArgumentException.class, () -> SectionRoles.parse("=projects"));
        assertThrows(IllegalArgumentException.class, () -> SectionRoles.parse("Projects=nonsense"));
        Map.Entry<String, String> parsed = SectionRoles.parse("Selected Work = Projects");
        assertEquals("Selected Work", parsed.getKey());
        assertEquals("projects", parsed.getValue());
    }

    @Test
    void sectionDetectionFollowsAnOverrideOfAKnownHeading() throws Exception {
        Map<String, String> before = rolesOf(detect(fixture(), SectionRoles.NONE));
        assertEquals("projects", before.get("Projects"));

        Map<String, String> after = rolesOf(detect(fixture(), SectionRoles.of(Map.of("Projects", "other"))));
        assertEquals("other", after.get("Projects"));
        assertEquals(before.keySet(), after.keySet(), "the same sections, only the role changed");
        for (String heading : before.keySet()) {
            if (!heading.equals("Projects")) {
                assertEquals(before.get(heading), after.get(heading), heading);
            }
        }
    }

    @Test
    void anOverrideGivesAHeadingWithNoVocabularyWordTheRoleTheUserChose() throws Exception {
        Path renamed = withHeadingRenamed("Projects", "Selected Work");

        Map<String, String> plain = rolesOf(detect(renamed, SectionRoles.NONE));
        assertEquals("other", plain.get("Selected Work"), "no vocabulary word: found by its formatting, but only as an 'other' section");

        Map<String, String> chosen = rolesOf(detect(renamed, SectionRoles.of(Map.of("Selected Work", "projects"))));
        assertEquals("projects", chosen.get("Selected Work"));
        assertEquals("experience", chosen.get("Experience"));
    }

    @Test
    void blockAnalysisFindsThePositionsOfTheSectionTheUserNamed() throws Exception {
        Path renamed = withHeadingRenamed("Projects", "Selected Work");

        BlocksReport plain = BlocksAnalyzer.analyze(renamed);
        assertNull(plain.projectsSection(), "without the override there is no projects section");
        assertTrue(plain.positions().isEmpty());

        BlocksReport chosen = BlocksAnalyzer.analyze(renamed, SectionRoles.of(Map.of("Selected Work", "projects")));
        assertEquals("Selected Work", chosen.projectsSection());
        BlocksReport original = BlocksAnalyzer.analyze(fixture());
        assertEquals(original.positions().size(), chosen.positions().size(), "same positions as the unrenamed resume");
        assertFalse(chosen.positions().isEmpty());
    }

    @Test
    void theOnboardReportKeepsTheChoiceAndIsUnchangedWithoutOne() throws Exception {
        OnboardReport report = OnboardReport.accepted(1, 0.0, 0, 0, 0, List.of(), 0, List.of(), "x");
        ObjectMapper mapper = new ObjectMapper();
        String without = mapper.writeValueAsString(report);
        assertFalse(without.contains("sectionRoles"), "no overrides: not a byte more in onboard.json");

        OnboardReport chosen = report.withSectionRoles(Map.of("Selected Work", "projects"));
        Path file = Files.createTempFile("onboard", ".json");
        chosen.writeTo(file);
        assertEquals(Map.of("Selected Work", "projects"), OnboardReport.readFrom(file).sectionRoles());
        assertNull(report.withSectionRoles(Map.of()).sectionRoles());
        assertEquals(without, mapper.writeValueAsString(report.withSectionRoles(Map.of())));
        // An onboard.json from before this field existed still reads.
        Path old = Files.createTempFile("onboard-old", ".json");
        Files.writeString(old, without);
        assertNull(OnboardReport.readFrom(old).sectionRoles());
    }
}
