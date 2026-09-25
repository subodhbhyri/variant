package com.tailor.cli;

import com.tailor.engine.fonts.FontAudit;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.fonts.FontNormalizer;
import com.tailor.engine.fonts.NeedsUserException;
import com.tailor.engine.fonts.NormalizeResult;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code tailor normalize <in.docx> <out.docx>} — spec section 3. Step 1.2's
 * acceptance test (T1): all 9 corpus resumes must reach 1 page with a font
 * audit pass and the shrink/squeeze numbers matching golden/*.json.
 */
@Command(name = "normalize", description = "Normalize fonts and letter-spacing (spec section 3).")
public final class NormalizeCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Input .docx")
    private Path inPath;

    @Parameters(index = "1", description = "Output .docx (normalized)")
    private Path outPath;

    @Option(names = "--target-pages", defaultValue = "1", description = "Page count to reach (default 1).")
    private int targetPages;

    @Override
    public Integer call() {
        try {
            if (outPath.toAbsolutePath().getParent() != null) {
                Files.createDirectories(outPath.toAbsolutePath().getParent());
            }
            Path tmpDir = Files.createTempDirectory("normalize-render");

            Renderer renderer = new LibreOfficeRenderer();
            FontMap fontMap = FontMap.loadDefault();
            FontNormalizer normalizer = new FontNormalizer(fontMap, renderer);

            NormalizeResult result = normalizer.normalize(inPath, outPath, targetPages, tmpDir);
            Path pdf = renderer.render(outPath, tmpDir);
            var violations = new FontAudit(fontMap).audit(pdf);

            System.out.printf("squeeze_removed=%d shrink_pt=%.1f pages=%d%n",
                    result.squeezeRemoved(), result.shrinkPt(), result.pages());
            if (violations.isEmpty()) {
                System.out.println("font_audit=PASS");
                return 0;
            }
            System.out.println("font_audit=FAIL");
            for (var v : violations) {
                System.out.printf("  unmapped font=%s chars=%d sample=%s%n", v.font(), v.charCount(), v.sample());
            }
            return 1;

        } catch (NeedsUserException e) {
            System.out.println("result=NEEDS_USER " + e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println("normalize failed: " + e);
            return 1;
        }
    }
}
