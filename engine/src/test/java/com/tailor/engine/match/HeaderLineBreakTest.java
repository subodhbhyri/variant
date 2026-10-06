package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.measure.AnchorMeasurer;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * PHASE3_SPEC.md section 4 (header line breaks): a rewritten header can only wrap after ", " or beside a
 * "|" separator, never inside an item or a URL. Checked on the real output of the fixture postings: the
 * header text, and the PDF lines it wraps onto. Also checks that each swapped header carries its
 * library project's full title, subtitle included. Corpus tag: renders through LibreOffice.
 */
@Tag("corpus")
class HeaderLineBreakTest {

    private static final List<String> POSTINGS = List.of("platform", "frontend", "data");
    private static final char NBSP = ' ';
    private static final char BULLET = '•';

    @Test
    void everySwappedHeaderWrapsOnlyAfterACommaOrBesideABarAndKeepsItsFullTitle() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        FontMap fontMap = FontMap.loadDefault();
        Path onboardDir = Files.createTempDirectory("linebreak-onboard");
        byte[] upload = Files.readAllBytes(CorpusPaths.phase3FixturesDir().resolve("projects_synthetic.docx"));
        OnboardReport report = new OnboardPipeline(renderer, fontMap).run(upload, onboardDir);
        Assumptions.assumeTrue(report.accepted(), "fixture failed to onboard: " + report.reason());

        Path fixtures = CorpusPaths.phase5FixturesDir();
        MatchRunner.Context ctx = MatchRunner.buildContext(onboardDir.resolve("normalized.docx"),
                fixtures.resolve("match_run").resolve("variants.json"),
                CorpusPaths.phase3FixturesDir().resolve("library.json"),
                SkillsDictionary.loadDefault(), new FakeEmbedder(), renderer, fontMap);
        Map<String, String> titleById = new HashMap<>();
        ctx.library().forEach(p -> titleById.put(p.id(), p.title()));

        int checked = 0;
        for (String name : POSTINGS) {
            String jdText = Files.readString(fixtures.resolve("jds").resolve(name + ".txt"));
            Path workDir = Files.createTempDirectory("linebreak-" + name);
            MatchRunner.Result result = MatchRunner.runOne(ctx, jdText, workDir,
                    Files.createTempDirectory("linebreak-out").resolve("resume-1.docx"));
            assertNull(result.renderFailureReason(), name + " failed: " + result.renderFailureReason());
            // The batched assembly carries its own checked PDF; a per-position result is rendered here, so the
            // rule is checked on the delivered document either way.
            Path pdf = result.resume1Pdf() != null ? result.resume1Pdf()
                    : renderer.render(result.resume1Docx(), Files.createTempDirectory("linebreak-pdf"));

            List<String> titles = new ArrayList<>();
            for (AssembledResume.ProjectAssignment a : result.resumes().get(0).projects()) {
                titles.add(titleById.get(a.project()));
            }
            List<String> paragraphs = titledParagraphs(result.resume1Docx(), titles);
            assertEquals(titles.size(), paragraphs.size(), name + ": every swapped header must be found in the document");

            List<PdfLines.Line> lines = PdfLines.extract(pdf);
            for (String paragraph : paragraphs) {
                checkHeader(name, paragraph, lines);
                checked++;
            }
        }
        assertTrue(checked > 0, "the fixtures must produce at least one swapped header");
    }

    /** The body paragraphs whose text begins with one of the given (full) library titles, in document order. */
    private static List<String> titledParagraphs(Path docx, List<String> titles) throws Exception {
        Document doc = SafeXml.parse(DocxPackage.open(docx).readPart("word/document.xml"));
        List<String> out = new ArrayList<>();
        for (Element p : DomUtil.descendants(doc.getDocumentElement(), "p")) {
            String paragraph = DomUtil.allText(p);
            for (String title : titles) {
                if (title != null && normalizedSpaces(paragraph).startsWith(normalizedSpaces(title))) {
                    out.add(paragraph);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Checks one swapped paragraph. Its header is the whole paragraph, or for an inline position the text
     * before its first bullet glyph (the bullets wrap normally and aren't part of the rule).
     */
    private static void checkHeader(String name, String paragraph, List<PdfLines.Line> lines) {
        int bullet = paragraph.indexOf(BULLET);
        String header = (bullet < 0 ? paragraph : paragraph.substring(0, bullet)).strip();

        // The rule is about where lines break, so it is checked on the lines the paragraph wraps onto, read back
        // against its text: every line break inside the header must fall after ", " or beside "|".
        int[] span = AnchorMeasurer.measureSpans(lines, List.of(paragraph)).get(0);
        assertNotNull(span, name + ": paragraph not found on the page: " + paragraph);
        List<Integer> originalIndex = new ArrayList<>();
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < paragraph.length(); i++) {
            String nfkc = Normalizer.normalize(String.valueOf(paragraph.charAt(i)), Normalizer.Form.NFKC)
                    .replace('‐', '-').replace('‑', '-');
            for (char ch : nfkc.toCharArray()) {
                if (!Character.isWhitespace(ch)) {
                    normalized.append(ch);
                    originalIndex.add(i);
                }
            }
        }
        StringBuilder onPage = new StringBuilder();
        for (int li = span[0]; li <= span[1]; li++) {
            onPage.append(lines.get(li).normalizedText());
        }
        assertEquals(normalized.toString(), onPage.toString(),
                name + ": the paragraph's lines don't read back as its text: " + paragraph);

        int headerLength = normalizedLength(header);
        int k = 0;
        for (int li = span[0]; li < span[1]; li++) {
            k += lines.get(li).normalizedText().length();
            if (k >= headerLength) {
                break; // the break is past the header: the bullets' own text, not part of this rule
            }
            int prev = originalIndex.get(k - 1);
            int next = originalIndex.get(k);
            String gap = paragraph.substring(prev + 1, next);
            assertFalse(gap.isEmpty(), name + ": a line breaks with no space at that point: " + header);
            assertTrue(gap.chars().allMatch(c -> c == ' '),
                    name + ": a line breaks at a no-break character (\"" + gap + "\"): " + header);
            char before = paragraph.charAt(prev);
            char after = paragraph.charAt(next);
            assertTrue(before == ',' || before == '|' || after == '|',
                    name + ": a line breaks outside \", \" and \"|\": " + header);
        }
    }

    /** Length of the normalized text of {@code s}: NFKC, hyphens folded, whitespace dropped. */
    private static int normalizedLength(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            String nfkc = Normalizer.normalize(String.valueOf(s.charAt(i)), Normalizer.Form.NFKC);
            for (char ch : nfkc.toCharArray()) {
                if (!Character.isWhitespace(ch)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static String normalizedSpaces(String s) {
        return s.replace(NBSP, ' ');
    }
}
