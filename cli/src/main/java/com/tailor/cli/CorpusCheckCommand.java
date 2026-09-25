package com.tailor.cli;

import com.tailor.engine.check.CorpusCheck;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code tailor corpus-check <corpusDir> <goldenDir> [--skip-t11]} — step 1.6.
 * Runs every acceptance test on every resume and prints a pass/fail table,
 * then the details of anything that failed. Exit code 0 only if all pass.
 */
@Command(name = "corpus-check", description = "Run the Phase 1 acceptance tests on a corpus and print a report.")
public final class CorpusCheckCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Folder of .docx resumes")
    private Path corpusDir;

    @Parameters(index = "1", description = "Folder of golden .json files (same base names)")
    private Path goldenDir;

    @Option(names = "--skip-t11", description = "Skip the second calibration pass (T11), about 40% faster.")
    private boolean skipT11;

    @Override
    public Integer call() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        System.out.println("LibreOffice " + renderer.version() + " — checking " + corpusDir + " against " + goldenDir);
        long start = System.currentTimeMillis();

        List<CorpusCheck.ResumeResult> results =
                new CorpusCheck(renderer, FontMap.loadDefault(), !skipT11).run(corpusDir, goldenDir);

        int nameWidth = Math.max(8, results.stream().mapToInt(r -> Math.min(30, r.name().length())).max().orElse(8));
        StringBuilder header = new StringBuilder(pad("resume", nameWidth));
        for (String t : CorpusCheck.TESTS) header.append(' ').append(pad(t, 4));
        System.out.println();
        System.out.println(header);
        System.out.println("-".repeat(header.length()));

        int failures = 0;
        for (CorpusCheck.ResumeResult r : results) {
            StringBuilder row = new StringBuilder(pad(trim(r.name(), nameWidth), nameWidth));
            for (String t : CorpusCheck.TESTS) {
                CorpusCheck.Outcome o = r.outcomes().get(t);
                String cell = o == null ? "--" : !o.pass() ? "FAIL" : o.detail().startsWith("skipped") ? "skip" : "ok";
                row.append(' ').append(pad(cell, 4));
            }
            if (r.outcomes().containsKey("ERROR")) row.append("  ERROR");
            System.out.println(row);
            if (!r.allPass()) failures++;
        }

        System.out.println();
        for (CorpusCheck.ResumeResult r : results) {
            r.outcomes().forEach((t, o) -> {
                if (!o.pass()) System.out.println(r.name() + " " + t + ": " + o.detail());
            });
        }

        long secs = (System.currentTimeMillis() - start) / 1000;
        System.out.println((failures == 0 ? "ALL PASS" : failures + " resume(s) with failures")
                + " — " + results.size() + " resumes in " + secs + "s");
        return failures == 0 ? 0 : 1;
    }

    private static String pad(String s, int w) {
        return s.length() >= w ? s : s + " ".repeat(w - s.length());
    }

    private static String trim(String s, int w) {
        return s.length() <= w ? s : s.substring(0, w);
    }
}