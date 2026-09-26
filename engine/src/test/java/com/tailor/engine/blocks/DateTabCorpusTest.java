package com.tailor.engine.blocks;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.docx.XmlSerialize;
import com.tailor.engine.fonts.FontMap;
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
 * P3-T5 (PHASE3_SPEC.md section 9, revised after the Opus review of the first
 * measured result): converting every tab-date header in the corpus must not
 * move anything vertically, and must not move any non-header line at all —
 * only the converted header line's own characters may drift horizontally,
 * bounded by twip quantization (5 twips = 0.25pt).
 */
class DateTabCorpusTest {

    private static final double VERTICAL_TOLERANCE_PT = 0.05;
    private static final double NON_HEADER_TOLERANCE_PT = 0.05;
    private static final double HEADER_HORIZONTAL_TOLERANCE_PT = 0.25;
    private static final double LINE_THRESHOLD_PT = 3.0; // same "same line?" band PdfLines/DateEdgeMeasurer use

    @Test
    void convertingEveryTabDateHeaderStaysWithinTolerance() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        OnboardPipeline onboard = new OnboardPipeline(renderer, FontMap.loadDefault());

        StringBuilder failures = new StringBuilder();
        int resumesWithTabDateHeaders = 0;

        for (Path docx : CorpusPaths.corpusDocx()) {
            String base = stripExt(docx.getFileName().toString());
            Path workDir = Files.createTempDirectory("date-tab-corpus-" + base);

            OnboardReport onboardReport = onboard.run(Files.readAllBytes(docx), workDir);
            if (!onboardReport.accepted()) {
                failures.append(base).append(": failed to onboard, reason=").append(onboardReport.reason()).append('\n');
                continue;
            }
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
            Section projectsSection = sections.stream().filter(s -> "projects".equals(s.role())).findFirst().orElse(null);
            if (projectsSection == null) {
                continue;
            }
            List<Position> positions = PositionBuilder.build(projectsSection, slotByElement::containsKey, slotByElement);

            List<Element> tabDateHeaders = new ArrayList<>();
            for (Position p : positions) {
                if ("paragraph".equals(p.kind()) && p.swappable() && p.header() != null
                        && "tab".equals(p.header().dateMode())) {
                    tabDateHeaders.add(p.block().headerParas.get(0));
                }
            }
            if (tabDateHeaders.isEmpty()) {
                continue;
            }
            resumesWithTabDateHeaders++;

            Path pdfBefore = renderer.render(normalizedDocx, workDir);
            List<DateEdgeMeasurer.GlyphPosition> before = DateEdgeMeasurer.allGlyphPositions(pdfBefore);
            int leftMargin = DateTabConverter.leftMarginTwips(doc);

            List<Double> headerLineYs = new ArrayList<>();
            int converted = 0;
            for (Element header : tabDateHeaders) {
                var measured = DateEdgeMeasurer.measureRightEdge(pdfBefore, DomUtil.allText(header), leftMargin);
                if (measured.isEmpty()) {
                    failures.append(base).append(": could not measure a tab-date header's edge\n");
                    continue;
                }
                if (DateTabConverter.rightAlignDateTab(doc, header, measured.get().rightEdgeTwips())) {
                    converted++;
                    headerLineYs.add(measured.get().lineY());
                }
            }
            if (converted == 0) {
                continue;
            }

            pkg.writePart("word/document.xml", XmlSerialize.toBytes(doc));
            Path convertedDocx = workDir.resolve("date-tab-converted.docx");
            pkg.save(convertedDocx);
            Path pdfAfter = renderer.render(convertedDocx, workDir);
            List<DateEdgeMeasurer.GlyphPosition> after = DateEdgeMeasurer.allGlyphPositions(pdfAfter);

            if (before.size() != after.size()) {
                failures.append(base).append(": glyph count changed ").append(before.size())
                        .append(" -> ").append(after.size()).append(" (date-tab conversion must not change text)\n");
                continue;
            }

            double maxVerticalAnywhere = 0;
            double maxNonHeaderLineMovement = 0;
            double maxHeaderHorizontal = 0;
            for (int i = 0; i < before.size(); i++) {
                var b = before.get(i);
                var a = after.get(i);
                double dx = Math.abs(b.x() - a.x());
                double dy = Math.abs(b.y() - a.y());
                maxVerticalAnywhere = Math.max(maxVerticalAnywhere, dy);

                boolean onConvertedHeaderLine = headerLineYs.stream()
                        .anyMatch(y -> Math.abs(b.y() - y) <= LINE_THRESHOLD_PT);
                if (onConvertedHeaderLine) {
                    maxHeaderHorizontal = Math.max(maxHeaderHorizontal, dx);
                } else {
                    maxNonHeaderLineMovement = Math.max(maxNonHeaderLineMovement, Math.max(dx, dy));
                }
            }

            System.out.printf("%s: %d tab-date header(s) converted; max vertical=%.4fpt, "
                            + "max non-header-line=%.4fpt, max header horizontal=%.4fpt%n",
                    base, converted, maxVerticalAnywhere, maxNonHeaderLineMovement, maxHeaderHorizontal);

            if (maxVerticalAnywhere > VERTICAL_TOLERANCE_PT) {
                failures.append(base).append(": vertical movement ").append(maxVerticalAnywhere)
                        .append("pt > ").append(VERTICAL_TOLERANCE_PT).append("pt\n");
            }
            if (maxNonHeaderLineMovement > NON_HEADER_TOLERANCE_PT) {
                failures.append(base).append(": non-header-line movement ").append(maxNonHeaderLineMovement)
                        .append("pt > ").append(NON_HEADER_TOLERANCE_PT).append("pt\n");
            }
            if (maxHeaderHorizontal > HEADER_HORIZONTAL_TOLERANCE_PT) {
                failures.append(base).append(": header-line horizontal movement ").append(maxHeaderHorizontal)
                        .append("pt > ").append(HEADER_HORIZONTAL_TOLERANCE_PT).append("pt\n");
            }
        }

        System.out.println("resumes with tab-date headers: " + resumesWithTabDateHeaders);
        System.out.println("FAILURES:\n" + (failures.isEmpty() ? "(none)" : failures));
        assertTrue(failures.isEmpty(), "P3-T5 failures:\n" + failures);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
