package com.tailor.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.blocks.BlockSwapper;
import com.tailor.engine.blocks.LibraryProject;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.golden.Phase3Fixtures;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code tailor project-check fixtures/phase3} — PHASE3_SPEC.md section 8: runs P3-T3 (every
 * library project into every swappable fixture position) and prints the swap table.
 */
@Command(name = "project-check", description = "Runs P3-T3 (fixture swaps) and prints the swap table.")
public final class ProjectCheckCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "fixtures/phase3 directory")
    private Path fixturesDir;

    @Override
    public Integer call() {
        try {
            ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            Phase3Fixtures.Expected expected = Phase3Fixtures.loadExpected(fixturesDir.resolve("expected.json"));
            LibraryProject.Library library = mapper.readValue(
                    fixturesDir.resolve("library.json").toFile(), LibraryProject.Library.class);

            Renderer renderer = new LibreOfficeRenderer();
            OnboardPipeline onboard = new OnboardPipeline(renderer, FontMap.loadDefault());
            Path outDir = Files.createTempDirectory("project-check");
            byte[] upload = Files.readAllBytes(fixturesDir.resolve("projects_synthetic.docx"));
            OnboardReport onboardReport = onboard.run(upload, outDir);
            if (!onboardReport.accepted()) {
                System.err.println("project-check failed: fixture did not onboard: " + onboardReport.reason());
                return 1;
            }
            Path normalizedDocx = outDir.resolve("normalized.docx");

            System.out.printf("%-16s %-4s %-20s %-20s %s%n", "project", "pos", "expected", "got", "result");
            System.out.println("-".repeat(80));

            int total = 0;
            int passed = 0;
            for (LibraryProject project : library.projects()) {
                for (int posIndex = 0; posIndex < 4; posIndex++) {
                    String key = project.id() + "->P" + posIndex;
                    Phase3Fixtures.ExpectedSwap exp = expected.swaps().get(key);
                    if (exp == null) {
                        continue;
                    }
                    total++;
                    DocxPackage basePkg = DocxPackage.open(normalizedDocx);
                    Path workDir = Files.createTempDirectory("project-check-" + key.replace("->", "-"));
                    Path outputDocx = workDir.resolve("swapped.docx");
                    BlockSwapper.Result result =
                            BlockSwapper.swap(basePkg, posIndex, project, renderer, workDir, outputDocx);

                    boolean ok = exp.outcome().equals(result.outcome().name());
                    if (ok) {
                        passed++;
                    }
                    System.out.printf("%-16s %-4s %-20s %-20s %s%n", project.id(), "P" + posIndex,
                            exp.outcome(), result.outcome(), ok ? "ok" : "MISMATCH");
                }
            }

            System.out.println("-".repeat(80));
            System.out.println(passed + "/" + total + " matched expected outcome");
            return passed == total ? 0 : 1;
        } catch (Exception e) {
            System.err.println("project-check failed: " + e);
            return 1;
        }
    }
}
