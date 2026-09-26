package com.tailor.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.blocks.BlocksAnalyzer;
import com.tailor.engine.blocks.BlocksReport;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** {@code tailor blocks <onboarded.docx>} — PHASE3_SPEC.md section 8: sections, roles, positions, shapes, reasons. */
@Command(name = "blocks", description = "Sections, roles, positions and shapes (spec section 3).")
public final class BlocksCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "An already-onboarded (normalized) .docx")
    private Path onboardedPath;

    @Override
    public Integer call() {
        try {
            BlocksReport report = BlocksAnalyzer.analyze(onboardedPath);
            ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            return 0;
        } catch (Exception e) {
            System.err.println("blocks failed: " + e);
            return 1;
        }
    }
}
