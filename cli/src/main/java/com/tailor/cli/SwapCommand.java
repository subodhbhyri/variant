package com.tailor.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.blocks.BlockSwapper;
import com.tailor.engine.blocks.LibraryProject;
import com.tailor.engine.blocks.SwapOutcome;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code tailor swap <onboarded.docx> <library.json> <outDir> --place P3=quill [--place P0=relay …]}
 * — PHASE3_SPEC.md section 8. Applies each placement in turn (each {@code --place} chains onto
 * the previous step's output), so several positions can be filled in one call.
 */
@Command(name = "swap", description = "Puts library projects into positions (spec section 7).")
public final class SwapCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "An already-onboarded (normalized) .docx")
    private Path onboardedPath;

    @Parameters(index = "1", description = "library.json (Phase 4 contract)")
    private Path libraryPath;

    @Parameters(index = "2", description = "Output directory")
    private Path outDir;

    @Option(names = "--place", description = "Pn=projectId, repeatable", required = true)
    private Map<String, String> placements = new LinkedHashMap<>();

    @Override
    public Integer call() {
        try {
            ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            LibraryProject.Library library = mapper.readValue(libraryPath.toFile(), LibraryProject.Library.class);
            Map<String, LibraryProject> byId = new LinkedHashMap<>();
            for (LibraryProject p : library.projects()) {
                byId.put(p.id(), p);
            }

            Files.createDirectories(outDir);
            Renderer renderer = new LibreOfficeRenderer();
            Path workDir = Files.createTempDirectory("swap-cli");

            Path current = onboardedPath;
            for (var e : placements.entrySet()) {
                String posKey = e.getKey();
                Integer posIndex = parsePositionIndex(posKey);
                if (posIndex == null) {
                    System.err.println("swap failed: --place key must look like P0, P1, ... (got " + posKey + ")");
                    return 1;
                }
                LibraryProject project = byId.get(e.getValue());
                if (project == null) {
                    System.err.println("swap failed: no library project with id " + e.getValue());
                    return 1;
                }
                DocxPackage basePkg = DocxPackage.open(current);
                Path stepOutput = workDir.resolve("step-" + posKey + ".docx");
                BlockSwapper.Result result =
                        BlockSwapper.swap(basePkg, posIndex, project, renderer, workDir, stepOutput);
                if (result.outcome() != SwapOutcome.OK) {
                    System.err.println(posKey + "=" + e.getValue() + ": " + result.outcome());
                    return 1;
                }
                current = stepOutput;
            }

            Path finalOutput = outDir.resolve("swapped.docx");
            Files.copy(current, finalOutput, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("swap ok -> " + finalOutput);
            return 0;
        } catch (Exception e) {
            System.err.println("swap failed: " + e);
            return 1;
        }
    }

    private static Integer parsePositionIndex(String key) {
        if (key == null || key.length() < 2 || key.charAt(0) != 'P') {
            return null;
        }
        try {
            return Integer.parseInt(key.substring(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
