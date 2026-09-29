package com.tailor.cli;

import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.match.Embedder;
import com.tailor.engine.match.FakeEmbedder;
import com.tailor.engine.match.JdParser;
import com.tailor.engine.match.JobDescription;
import com.tailor.engine.match.MatchBatchSummary;
import com.tailor.engine.match.MatchRunner;
import com.tailor.engine.match.MiniLmEmbedder;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code tailor match-batch <onboarded.docx> <variants.json> <library.json> <jdFolder> <outDir>
 * [--embedder fake|minilm]} — P5-T8 operator tooling. Runs {@code match} for every {@code .txt}
 * file in {@code jdFolder} (sorted by filename, for a deterministic report and cache order) and
 * writes {@code summary.md} ({@link MatchBatchSummary}). Each job description's own {@code
 * resume-1.docx}/{@code resume-1.pdf} land in their own subfolder, named after the {@code .txt}
 * file.
 */
@Command(name = "match-batch",
        description = "Runs match for every job description in a folder and writes a summary (spec P5-T8).")
public final class MatchBatchCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "An already-onboarded (normalized) .docx")
    private Path onboardedPath;

    @Parameters(index = "1", description = "variants.json (Phase 4 tailor generate output)")
    private Path variantsJsonPath;

    @Parameters(index = "2", description = "library.json (Phase 3 contract)")
    private Path libraryJsonPath;

    @Parameters(index = "3", description = "Folder of plain-text job descriptions (*.txt)")
    private Path jdFolder;

    @Parameters(index = "4", description = "Output directory")
    private Path outDir;

    @Option(names = "--embedder", description = "fake (fixtures/tests) or minilm (production)", defaultValue = "fake")
    private String embedderName;

    @Override
    public Integer call() {
        try {
            List<Path> jdFiles;
            try (Stream<Path> stream = Files.list(jdFolder)) {
                jdFiles = stream.filter(p -> p.getFileName().toString().endsWith(".txt")).sorted().toList();
            }
            if (jdFiles.isEmpty()) {
                System.err.println("match-batch failed: no .txt files in " + jdFolder);
                return 1;
            }

            Renderer renderer = new LibreOfficeRenderer();
            FontMap fontMap = FontMap.loadDefault();
            Path workDir = Files.createTempDirectory("match-batch-cli");
            SkillsDictionary skills = SkillsDictionary.loadDefault();

            MiniLmEmbedder miniLm = null;
            Embedder embedder;
            if ("fake".equals(embedderName)) {
                embedder = new FakeEmbedder();
            } else if ("minilm".equals(embedderName)) {
                miniLm = MiniLmEmbedder.loadFromEnv();
                embedder = miniLm;
            } else {
                System.err.println("match-batch failed: --embedder must be fake or minilm (got " + embedderName + ")");
                return 1;
            }

            try {
                MatchRunner.Context ctx = MatchRunner.buildContext(
                        onboardedPath, variantsJsonPath, libraryJsonPath, skills, embedder, renderer, fontMap, workDir);
                Files.createDirectories(outDir);

                List<JobDescription> processedJds = new ArrayList<>();
                List<String> processedNames = new ArrayList<>();
                List<MatchBatchSummary.Row> rows = new ArrayList<>();

                for (Path jdFile : jdFiles) {
                    String jdText = Files.readString(jdFile);
                    JdParser.ValidationResult validation = JdParser.validate(jdText);
                    if (!validation.accepted()) {
                        System.err.println("skipping " + jdFile.getFileName() + ": " + validation.reason());
                        continue;
                    }
                    String baseName = stripExt(jdFile.getFileName().toString());
                    Path jdOutDir = outDir.resolve(baseName);
                    Files.createDirectories(jdOutDir);
                    Path resume1Docx = jdOutDir.resolve("resume-1.docx");

                    MatchRunner.Result result;
                    try {
                        result = MatchRunner.runOne(ctx, jdText, workDir, resume1Docx);
                    } catch (IllegalStateException e) {
                        System.err.println(jdFile.getFileName() + ": " + e.getMessage());
                        continue;
                    }
                    Path resume1Pdf = renderer.render(result.resume1Docx(), workDir);
                    Files.copy(resume1Pdf, jdOutDir.resolve("resume-1.pdf"), StandardCopyOption.REPLACE_EXISTING);

                    rows.add(MatchBatchSummary.rowFor(baseName, result, processedJds, processedNames, embedder));
                    processedJds.add(result.jd());
                    processedNames.add(baseName);
                }

                Files.writeString(outDir.resolve("summary.md"), MatchBatchSummary.toMarkdown(rows));
                System.out.println("match-batch ok -> " + outDir);
                return 0;
            } finally {
                if (miniLm != null) {
                    miniLm.close();
                }
            }
        } catch (Exception e) {
            System.err.println("match-batch failed: " + e);
            return 1;
        }
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
