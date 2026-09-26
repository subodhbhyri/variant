package com.tailor.engine.blocks;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.CorpusPaths;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.golden.Phase3Fixtures;
import com.tailor.engine.measure.AnchorMeasurer;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.numbering.NumberingResolver;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.slots.BulletDetector;
import com.tailor.engine.slots.Slot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * P3-T3 (PHASE3_SPEC.md section 9): every library project (fixtures/phase3/library.json)
 * into every swappable fixture position must give the outcome {@code expected.json}'s
 * {@code swaps} table records; for {@code OK}, {@code stack_items_kept} and {@code
 * padded_bullets} must match where the fixture gives a concrete value (its "not_shown"
 * sentinel means: don't check that field), and every line outside the swapped position
 * must stay within 0.5pt.
 */
class BlockSwapFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private static final double LAYOUT_TOLERANCE_PT = 0.5;

    @Test
    void everyLibraryProjectIntoEverySwappablePositionMatchesExpected() throws Exception {
        Path fixturesDir = CorpusPaths.phase3FixturesDir();
        Phase3Fixtures.Expected expected = Phase3Fixtures.loadExpected(fixturesDir.resolve("expected.json"));
        LibraryProject.Library library = MAPPER.readValue(
                fixturesDir.resolve("library.json").toFile(), LibraryProject.Library.class);

        Renderer renderer = new LibreOfficeRenderer();
        OnboardPipeline onboard = new OnboardPipeline(renderer, FontMap.loadDefault());
        Path outDir = Files.createTempDirectory("swap-fixture-onboard");
        byte[] upload = Files.readAllBytes(fixturesDir.resolve("projects_synthetic.docx"));
        OnboardReport onboardReport = onboard.run(upload, outDir);
        assertTrue(onboardReport.accepted(), "fixture failed to onboard: " + onboardReport.reason());
        Path normalizedDocx = outDir.resolve("normalized.docx");

        List<Position> originalPositions = detectPositions(normalizedDocx);
        Path baselinePdf = renderer.render(normalizedDocx, outDir);
        List<PdfLines.Line> baselineLines = PdfLines.extract(baselinePdf);

        StringBuilder failures = new StringBuilder();
        int checked = 0;

        for (LibraryProject project : library.projects()) {
            for (int posIndex = 0; posIndex < 4; posIndex++) { // P0..P3; P4 isn't swappable
                String key = project.id() + "->P" + posIndex;
                Phase3Fixtures.ExpectedSwap exp = expected.swaps().get(key);
                if (exp == null) {
                    continue;
                }
                checked++;
                DocxPackage basePkg = DocxPackage.open(normalizedDocx);
                Path workDir = Files.createTempDirectory("swap-" + key.replace("->", "-"));
                Path outputDocx = workDir.resolve("swapped.docx");
                BlockSwapper.Result result =
                        BlockSwapper.swap(basePkg, posIndex, project, renderer, workDir, outputDocx);

                if (!exp.outcome().equals(result.outcome().name())) {
                    failures.append(key).append(": expected outcome ").append(exp.outcome())
                            .append(", got ").append(result.outcome()).append('\n');
                    continue;
                }
                if (!"OK".equals(exp.outcome())) {
                    continue;
                }

                if (exp.stackItemsKept() != null && !exp.stackItemsKept().isTextual()) {
                    int expectedKept = exp.stackItemsKept().asInt();
                    Integer got = result.stackItemsKept();
                    if (got == null || got != expectedKept) {
                        failures.append(key).append(": expected stack_items_kept=").append(expectedKept)
                                .append(", got ").append(got).append('\n');
                    }
                }
                if (exp.paddedBullets() != null) {
                    Integer got = result.paddedBullets();
                    if (got == null || !got.equals(exp.paddedBullets())) {
                        failures.append(key).append(": expected padded_bullets=").append(exp.paddedBullets())
                                .append(", got ").append(got).append('\n');
                    }
                }

                List<Position> afterPositions = detectPositions(outputDocx);
                Path pdfAfter = renderer.render(outputDocx, workDir);
                List<PdfLines.Line> linesAfter = PdfLines.extract(pdfAfter);
                int[] spanBefore = positionSpan(baselineLines, originalPositions.get(posIndex));
                int[] spanAfter = positionSpan(linesAfter, afterPositions.get(posIndex));
                LayoutDiff.Result diff =
                        LayoutDiff.checkOutsideMovement(baselineLines, spanBefore, linesAfter, spanAfter);
                if (!diff.ok()) {
                    failures.append(key).append(": ").append(diff.problem()).append('\n');
                } else if (diff.maxMovement() > LAYOUT_TOLERANCE_PT) {
                    failures.append(key).append(": moved a fixed line by ").append(diff.maxMovement()).append("pt\n");
                }
            }
        }

        System.out.println("swap combinations checked: " + checked);
        System.out.println("FAILURES:\n" + (failures.isEmpty() ? "(none)" : failures));
        assertTrue(failures.isEmpty(), "P3-T3 failures:\n" + failures);
    }

    /**
     * The position's own [firstLine, lastLine] span. Anchoring the header and the last bullet
     * separately (rather than one big concatenated string) matters for a numbered-list bullet:
     * its rendered line carries a glyph character the list numbering draws, which never appears
     * in the paragraph's own {@code <w:t>} text — so an exact concatenated-text match would
     * never find it, while {@code contains()} on each paragraph's own text, independently, still
     * does. Passing both texts in one {@code measureSpans} call also keeps the last-bullet search
     * from matching an earlier, unrelated occurrence: it only starts looking after the header's
     * own span.
     */
    private static int[] positionSpan(List<PdfLines.Line> lines, Position position) {
        if ("inline".equals(position.kind())) {
            String text = DomUtil.allText(position.block().inlineHeaderParagraph);
            return AnchorMeasurer.measureSpans(lines, List.of(text)).get(0);
        }
        String headerText = DomUtil.allText(position.block().headerParas.get(0));
        List<Element> bullets = position.block().bulletParas;
        String lastBulletText = DomUtil.allText(bullets.get(bullets.size() - 1));
        Map<Integer, int[]> spans = AnchorMeasurer.measureSpans(lines, List.of(headerText, lastBulletText));
        int[] headerSpan = spans.get(0);
        int[] lastBulletSpan = spans.get(1);
        if (headerSpan == null || lastBulletSpan == null) {
            return null;
        }
        return new int[] {headerSpan[0], lastBulletSpan[1]};
    }

    private static List<Position> detectPositions(Path docx) throws Exception {
        DocxPackage pkg = DocxPackage.open(docx);
        Document doc = SafeXml.parse(pkg.readPart("word/document.xml"));
        Element numberingRoot = pkg.hasPart("word/numbering.xml")
                ? SafeXml.parse(pkg.readPart("word/numbering.xml")).getDocumentElement() : null;
        Element stylesRoot = pkg.hasPart("word/styles.xml")
                ? SafeXml.parse(pkg.readPart("word/styles.xml")).getDocumentElement() : null;
        NumberingResolver resolver = new NumberingResolver(numberingRoot, stylesRoot);
        List<Slot> slots = BulletDetector.detect(doc, resolver);
        Map<Element, Slot> slotByElement = new IdentityHashMap<>();
        for (Slot s : slots) {
            slotByElement.put(s.element(), s);
        }
        List<Section> sections = SectionDetector.detect(doc);
        Section projectsSection = sections.stream().filter(s -> "projects".equals(s.role())).findFirst()
                .orElseThrow(() -> new IllegalStateException("no projects section detected"));
        return PositionBuilder.build(projectsSection, slotByElement::containsKey, slotByElement);
    }
}
