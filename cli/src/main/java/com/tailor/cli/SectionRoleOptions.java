package com.tailor.cli;

import com.tailor.engine.blocks.SectionRoles;
import com.tailor.engine.onboard.OnboardReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Option;

/**
 * {@code --section-role "HEADING=role"}, repeatable (PHASE3_SPEC.md section 2): the role the user chose for a section
 * heading, one of projects, experience or other. Without it the heading's vocabulary decides, as always.
 */
final class SectionRoleOptions {

    @Option(names = "--section-role", paramLabel = "HEADING=role",
            description = "The role of a section: projects, experience or other. Repeatable. "
                    + "Example: --section-role \"Selected Work=projects\"")
    private List<String> assignments = new ArrayList<>();

    /** The roles given on the command line, heading to role (validated). */
    Map<String, String> fromOptions() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String a : assignments) {
            Map.Entry<String, String> e = SectionRoles.parse(a);
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** The roles stored in {@code onboard.json} beside {@code docx} (if any), overridden by those on the command line. */
    SectionRoles forDocx(Path docx) throws IOException {
        Map<String, String> merged = new LinkedHashMap<>();
        Path report = docx.toAbsolutePath().getParent().resolve("onboard.json");
        if (Files.exists(report)) {
            Map<String, String> stored = OnboardReport.readFrom(report).sectionRoles();
            if (stored != null) {
                merged.putAll(stored);
            }
        }
        merged.putAll(fromOptions());
        return SectionRoles.of(merged);
    }
}
