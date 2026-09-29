package com.tailor.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.match.AliasQueue;
import com.tailor.engine.match.AssembledResume;
import com.tailor.engine.match.Embedder;
import com.tailor.engine.match.FakeEmbedder;
import com.tailor.engine.match.JdParser;
import com.tailor.engine.match.JobDescription;
import com.tailor.engine.match.MatchRunner;
import com.tailor.engine.match.MiniLmEmbedder;
import com.tailor.engine.match.UnknownTerms;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code tailor match <onboarded.docx> <variants.json> <library.json> <jd.txt> <outDir>
 * [--embedder fake|minilm]} — PHASE5_SPEC.md section 9. Writes {@code match.json} (parsed JD,
 * the 1-3 resumes with labels and missing skills) and {@code resume-1.pdf} (verified).
 *
 * <p>{@code --embedder fake} is for fixtures and tests; {@code minilm} loads the real model from
 * {@code VARIANT_MODEL_DIR} (section 7). The cache decision (section 6) needs a store of
 * previously-parsed JD fingerprints per (user, library version) that nothing in the spec's CLI
 * surface names yet, so it's left out of {@code match.json} here rather than guessed at.
 */
@Command(name = "match", description = "Matches a job description to stored material and assembles resumes (spec section 9).")
public final class MatchCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Parameters(index = "0", description = "An already-onboarded (normalized) .docx")
    private Path onboardedPath;

    @Parameters(index = "1", description = "variants.json (Phase 4 tailor generate output)")
    private Path variantsJsonPath;

    @Parameters(index = "2", description = "library.json (Phase 3 contract)")
    private Path libraryJsonPath;

    @Parameters(index = "3", description = "Plain-text job description")
    private Path jdTextPath;

    @Parameters(index = "4", description = "Output directory")
    private Path outDir;

    @Option(names = "--embedder", description = "fake (fixtures/tests) or minilm (production)", defaultValue = "fake")
    private String embedderName;

    record MatchOutput(JobDescription jd, List<AssembledResume> resumes, List<String> missing) {
    }

    @Override
    public Integer call() {
        try {
            String jdText = Files.readString(jdTextPath);
            JdParser.ValidationResult validation = JdParser.validate(jdText);
            if (!validation.accepted()) {
                System.err.println("match failed: " + validation.reason() + " - " + validation.message());
                return 1;
            }

            Renderer renderer = new LibreOfficeRenderer();
            FontMap fontMap = FontMap.loadDefault();
            Path workDir = Files.createTempDirectory("match-cli");
            SkillsDictionary skills = SkillsDictionary.loadDefault();

            // Best-effort: queueing unknown terms for later operator review (section 7.1) must
            // never break the match itself — e.g. the read-only runtime sandbox has nowhere
            // writable for the queue's default location unless the operator configures one.
            try {
                AliasQueue.addAll(AliasQueue.resolvePath(), UnknownTerms.detect(jdText, skills));
            } catch (IOException e) {
                System.err.println("warning: could not update the alias queue: " + e);
            }

            MiniLmEmbedder miniLm = null;
            Embedder embedder;
            if ("fake".equals(embedderName)) {
                embedder = new FakeEmbedder();
            } else if ("minilm".equals(embedderName)) {
                miniLm = MiniLmEmbedder.loadFromEnv();
                embedder = miniLm;
            } else {
                System.err.println("match failed: --embedder must be fake or minilm (got " + embedderName + ")");
                return 1;
            }

            try {
                MatchRunner.Context ctx = MatchRunner.buildContext(
                        onboardedPath, variantsJsonPath, libraryJsonPath, skills, embedder, renderer, fontMap, workDir);

                Files.createDirectories(outDir);
                Path resume1Docx = outDir.resolve("resume-1.docx");
                MatchRunner.Result result;
                try {
                    result = MatchRunner.runOne(ctx, jdText, workDir, resume1Docx);
                } catch (IllegalStateException e) {
                    System.err.println("match failed: " + e.getMessage());
                    return 1;
                }

                Path resume1Pdf = renderer.render(result.resume1Docx(), workDir);
                Files.copy(resume1Pdf, outDir.resolve("resume-1.pdf"), StandardCopyOption.REPLACE_EXISTING);

                MatchOutput output = new MatchOutput(result.jd(), result.resumes(), result.missing());
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(outDir.resolve("match.json").toFile(), output);

                System.out.println("match ok -> " + outDir);
                return 0;
            } finally {
                if (miniLm != null) {
                    miniLm.close();
                }
            }
        } catch (Exception e) {
            System.err.println("match failed: " + e);
            return 1;
        }
    }
}
