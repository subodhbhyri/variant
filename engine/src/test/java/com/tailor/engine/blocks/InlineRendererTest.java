package com.tailor.engine.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.docx.XmlSerialize;
import com.tailor.engine.fonts.FontMap;
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
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * P3-T6 (PHASE3_SPEC.md section 9): rebuilding the one bullet of Subodh's "LLM
 * Eval" inline block that has a manual mid-sentence {@code <w:br/>} (a
 * PDF-converter artifact — section 1), with its own text and the manual break
 * dropped, must keep it at 2 lines and move no OTHER line on the page
 * (measured 0.00pt in the spec — the spec's own wording: the rewritten
 * bullet's own lines aren't held to the original's exact character positions,
 * matching how the Verifier treats any edited slot). The other two bullets
 * and the header are left untouched (cloned verbatim): they don't exercise
 * rule 1, and rewriting them too would fold in unrelated natural-reflow noise
 * (their original text was typed character-by-character into separate runs
 * with no inter-letter kerning; reconstructing it as real words re-enables
 * kerning and can itself shift a natural word-wrap point — a real effect,
 * but not what this rule or this test is about).
 *
 * <p>Measurement mirrors {@code Verifier}: anchor the target bullet's own
 * text (before: original; after: rewritten) to find its line span, and
 * require every line outside that span — on both renders — to be the same
 * text at the same position. A second run proves the test isn't vacuously
 * passing: rebuilding with the rewrite rules deliberately violated (a bare
 * unformatted separator break, rule 1; the wrong, tiny formatting for the
 * rewritten bullet's own text, rule 2) must move or lose a fixed line the
 * test can detect. This environment's PDF layout turned out not to react
 * measurably to the break run's own font metrics alone (confirmed: neither
 * an absent rPr nor a grossly oversized one shifted anything — see {@link
 * InlineRenderer}), so the rule 2 violation is what the negative run relies
 * on to prove the methodology has teeth.
 */
class InlineRendererTest {

    private static final double FIXED_LINE_TOLERANCE_PT = 0.5;

    @Test
    void rebuildingOwnContentMovesNoLine() throws Exception {
        Result r = rebuild(false);
        System.out.printf("LLM Eval block, correct rebuild: lines %d -> %d, max fixed-line movement=%.4fpt%n",
                r.linesBefore, r.linesAfter, r.maxFixedLineMovement);
        assertEquals(2, r.linesBefore, "the bullet's own original line count");
        assertEquals(2, r.linesAfter, "rewriting must keep the bullet at 2 lines");
        assertNull(r.problem, r.problem);
        assertTrue(r.maxFixedLineMovement <= FIXED_LINE_TOLERANCE_PT,
                "correct rebuild moved a fixed line by " + r.maxFixedLineMovement + "pt");
    }

    @Test
    void rebuildingWithRuleViolationsMovesLines() throws Exception {
        Result r = rebuild(true);
        System.out.printf("LLM Eval block, rule-violating rebuild: lines %d -> %d, "
                + "max fixed-line movement=%.4fpt, problem=%s%n",
                r.linesBefore, r.linesAfter, r.maxFixedLineMovement, r.problem);
        boolean detected = r.problem != null || r.maxFixedLineMovement > FIXED_LINE_TOLERANCE_PT;
        assertTrue(detected, "the rule-violating rebuild should have moved a fixed line or lost one (it didn't"
                + " — the test would not have caught the regression it exists to catch)");
    }

    private static void assertNull(Object problem, String message) {
        if (problem != null) {
            throw new AssertionError(message);
        }
    }

    private record Result(int linesBefore, int linesAfter, double maxFixedLineMovement, String problem) {
    }

    private Result rebuild(boolean injectRuleViolation) throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        OnboardPipeline onboard = new OnboardPipeline(renderer, FontMap.loadDefault());
        Path docx = CorpusPaths.corpusDocx().stream()
                .filter(p -> p.getFileName().toString().equals("Subodh_Ashok_Bhyri.docx"))
                .findFirst().orElseThrow(() -> new AssertionError("Subodh_Ashok_Bhyri.docx not found in corpus"));
        Path workDir = Files.createTempDirectory("inline-rewrite-" + injectRuleViolation);

        OnboardReport onboardReport = onboard.run(Files.readAllBytes(docx), workDir);
        assertTrue(onboardReport.accepted(), "failed to onboard: " + onboardReport.reason());
        Path normalizedDocx = workDir.resolve("normalized.docx");

        DocxPackage pkg = DocxPackage.open(normalizedDocx);
        Document doc = SafeXml.parse(pkg.readPart("word/document.xml"));
        Element numberingRoot = pkg.hasPart("word/numbering.xml")
                ? SafeXml.parse(pkg.readPart("word/numbering.xml")).getDocumentElement() : null;
        Element stylesRoot = pkg.hasPart("word/styles.xml")
                ? SafeXml.parse(pkg.readPart("word/styles.xml")).getDocumentElement() : null;
        NumberingResolver resolver = new NumberingResolver(numberingRoot, stylesRoot);
        List<Slot> allSlots = BulletDetector.detect(doc, resolver);
        Map<Element, Slot> slotByElement = new IdentityHashMap<>();
        for (Slot s : allSlots) {
            slotByElement.put(s.element(), s);
        }

        List<Section> sections = SectionDetector.detect(doc);
        Section projectsSection = sections.stream().filter(s -> "projects".equals(s.role())).findFirst()
                .orElseThrow(() -> new AssertionError("no projects section detected"));
        List<Position> positions = PositionBuilder.build(projectsSection, slotByElement::containsKey, slotByElement);

        Element header = null;
        for (Position p : positions) {
            if ("inline".equals(p.kind())) {
                Element candidate = p.block().inlineHeaderParagraph;
                if (DomUtil.allText(candidate).contains("LLM Eval")) {
                    header = candidate;
                    break;
                }
            }
        }
        assertNotNull(header, "LLM Eval inline block not found among Subodh's positions");

        InlineTemplate.Model model = InlineTemplate.build(header);
        int brokenGroup = -1;
        for (int g = 0; g < model.groups().size(); g++) {
            if (model.groups().get(g).segments().size() > 1) {
                brokenGroup = g;
                break;
            }
        }
        assertTrue(brokenGroup >= 0, "expected a multi-segment (manually broken) bullet in the LLM Eval block");

        String originalOwnText = InlineTemplate.ownText(model.groups().get(brokenGroup));
        String rewriteText = stripLeadingGlyph(originalOwnText);
        String rewrittenFullText = "• " + rewriteText;

        Path pdfBefore = renderer.render(normalizedDocx, workDir);
        List<PdfLines.Line> linesBefore = PdfLines.extract(pdfBefore);
        Map<Integer, int[]> spanBefore = AnchorMeasurer.measureSpans(linesBefore, List.of(originalOwnText));
        int[] before = spanBefore.get(0);
        assertNotNull(before, "could not anchor the bullet's own original text before rewriting");

        InlineRenderer.render(header, model, Map.of(brokenGroup, rewriteText), injectRuleViolation);

        pkg.writePart("word/document.xml", XmlSerialize.toBytes(doc));
        Path rewrittenDocx = workDir.resolve("inline-rewritten.docx");
        pkg.save(rewrittenDocx);
        Path pdfAfter = renderer.render(rewrittenDocx, workDir);
        List<PdfLines.Line> linesAfter = PdfLines.extract(pdfAfter);
        Map<Integer, int[]> spanAfter = AnchorMeasurer.measureSpans(linesAfter, List.of(rewrittenFullText));
        int[] after = spanAfter.get(0);
        if (after == null) {
            return new Result(before[1] - before[0] + 1, -1, Double.NaN,
                    "could not anchor the rewritten bullet's text after rewriting");
        }

        int countBefore = before[1] - before[0] + 1;
        int countAfter = after[1] - after[0] + 1;

        List<PdfLines.Line> fixedBefore = linesExcept(linesBefore, before[0], before[1]);
        List<PdfLines.Line> fixedAfter = linesExcept(linesAfter, after[0], after[1]);

        if (fixedBefore.size() != fixedAfter.size()) {
            return new Result(countBefore, countAfter, Double.NaN,
                    "fixed line count changed: " + fixedBefore.size() + " -> " + fixedAfter.size());
        }
        double worst = 0;
        for (int i = 0; i < fixedBefore.size(); i++) {
            PdfLines.Line b = fixedBefore.get(i);
            PdfLines.Line a = fixedAfter.get(i);
            if (!b.normalizedText().equals(a.normalizedText())) {
                return new Result(countBefore, countAfter, Double.NaN,
                        "fixed line " + i + " text differs: [" + b.normalizedText() + "] vs [" + a.normalizedText() + "]");
            }
            if (b.pageIndex() != a.pageIndex()) {
                return new Result(countBefore, countAfter, Double.NaN, "fixed line " + i + " moved pages");
            }
            worst = Math.max(worst, Math.abs(b.y() - a.y()));
        }
        // The rewritten bullet's own first line must also stay where the page flow put it.
        worst = Math.max(worst, Math.abs(linesBefore.get(before[0]).y() - linesAfter.get(after[0]).y()));

        return new Result(countBefore, countAfter, worst, null);
    }

    private static List<PdfLines.Line> linesExcept(List<PdfLines.Line> lines, int fromInclusive, int toInclusive) {
        List<PdfLines.Line> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (i < fromInclusive || i > toInclusive) {
                out.add(lines.get(i));
            }
        }
        return out;
    }

    /** Strips the group's own leading glyph + one following space, since InlineRenderer's
     * rewrite path re-adds "• " itself — round-tripping "own content" must not double it. */
    private static String stripLeadingGlyph(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        if (i < s.length() && BlockDetector.GLYPHS.indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }
}
