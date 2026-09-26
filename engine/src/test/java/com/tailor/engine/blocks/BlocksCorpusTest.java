package com.tailor.engine.blocks;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.golden.Phase3Fixtures;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** P3-T2 (PHASE3_SPEC.md section 9): all 9 corpus resumes' sections/positions match golden/phase3_blocks.json. */
class BlocksCorpusTest {

    @Test
    void allNineMatchGoldenBlocks() throws Exception {
        Map<String, Phase3Fixtures.Expected> golden =
                Phase3Fixtures.loadGolden(CorpusPaths.goldenDir().resolve("phase3_blocks.json"));

        Renderer renderer = new LibreOfficeRenderer();
        FontMap fontMap = FontMap.loadDefault();
        OnboardPipeline onboard = new OnboardPipeline(renderer, fontMap);

        StringBuilder failures = new StringBuilder();
        for (Path docx : CorpusPaths.corpusDocx()) {
            String base = stripExt(docx.getFileName().toString());
            Phase3Fixtures.Expected expected = golden.get(base);
            if (expected == null) {
                failures.append(base).append(": no golden entry\n");
                continue;
            }

            Path outDir = Files.createTempDirectory("blocks-corpus-" + base);
            byte[] upload = Files.readAllBytes(docx);
            OnboardReport onboardReport = onboard.run(upload, outDir);
            if (!onboardReport.accepted()) {
                failures.append(base).append(": failed to onboard, reason=").append(onboardReport.reason()).append('\n');
                continue;
            }

            BlocksReport report = BlocksAnalyzer.analyze(outDir.resolve("normalized.docx"));

            if (expected.sections().size() != report.sections().size()) {
                failures.append(base).append(": section count got=").append(report.sections().size())
                        .append(" want=").append(expected.sections().size()).append('\n');
            } else {
                for (int i = 0; i < expected.sections().size(); i++) {
                    var es = expected.sections().get(i);
                    var gs = report.sections().get(i);
                    if (!es.heading().equals(gs.heading()) || !es.role().equals(gs.role())) {
                        failures.append(base).append(": section[").append(i).append("] got=(")
                                .append(gs.heading()).append(',').append(gs.role()).append(") want=(")
                                .append(es.heading()).append(',').append(es.role()).append(")\n");
                    }
                }
            }

            if (!java.util.Objects.equals(expected.projectsSection(), report.projectsSection())) {
                failures.append(base).append(": projects_section got=").append(report.projectsSection())
                        .append(" want=").append(expected.projectsSection()).append('\n');
            }

            if (expected.positions().size() != report.positions().size()) {
                failures.append(base).append(": position count got=").append(report.positions().size())
                        .append(" want=").append(expected.positions().size()).append('\n');
            } else {
                for (int i = 0; i < expected.positions().size(); i++) {
                    try {
                        BlocksFixtureTest.assertPositionMatches(
                                base + " position[" + i + "]", expected.positions().get(i), report.positions().get(i));
                    } catch (AssertionError e) {
                        failures.append(e.getMessage()).append('\n');
                    }
                }
            }

            System.out.println(base + ": " + report.sections().size() + " sections, "
                    + report.positions().size() + " positions in \"" + report.projectsSection() + "\"");
        }

        System.out.println("FAILURES:\n" + (failures.isEmpty() ? "(none)" : failures));
        assertTrue(failures.isEmpty(), "P3-T2 failures:\n" + failures);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
