package com.tailor.engine.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.RunFlags;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.fonts.FontNormalizer;
import com.tailor.engine.numbering.NumberingResolver;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.slots.BulletDetector;
import com.tailor.engine.slots.BulletText;
import com.tailor.engine.slots.EmphasisSpan;
import com.tailor.engine.slots.Slot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Step 1.4's own tests are structural (DOM-only): they check the edit
 * primitives produce the XML shape spec section 5 describes. They do NOT
 * render or measure line counts — that needs step 1.5's measurement code
 * (PdfLines/AnchorMeasurer), so the render-based checks from the test table
 * (T4 round-trip line counts, T9 blank layout, T10 pad layout) run once step
 * 1.5 exists, per the build order in section 12. This is a scope choice, not
 * a skipped requirement — flagging it explicitly rather than silently
 * narrowing what step 1.4 claims to verify.
 */
class EditPrimitivesCorpusTest {

    /** Substituting every supported bullet with its own text+emphasis reproduces that text exactly. */
    @Test
    void substituteRoundTripPreservesTextAndStructure() throws Exception {
        StringBuilder failures = new StringBuilder();
        int checked = 0;

        for (ResumeSlots rs : allNormalizedCorpusSlots()) {
            for (Slot slot : rs.slots()) {
                if (!slot.supported()) {
                    continue;
                }
                checked++;
                Element p = slot.element();
                boolean hadNumPr = DomUtil.firstChild(DomUtil.firstChild(p, "pPr"), "numPr") != null;

                Substituter.substitute(p, new BulletText(slot.text(), slot.emphasis()));

                String tag = rs.name() + "[" + slot.index() + "]";
                String after = DomUtil.allText(p);
                if (!after.equals(slot.text())) {
                    failures.append(tag).append(": text changed after round-trip\n");
                }
                boolean hasNumPrAfter = DomUtil.firstChild(DomUtil.firstChild(p, "pPr"), "numPr") != null;
                if (hadNumPr != hasNumPrAfter) {
                    failures.append(tag).append(": numPr presence changed (").append(hadNumPr)
                            .append(" -> ").append(hasNumPrAfter).append(")\n");
                }
                for (Element run : DomUtil.descendants(p, "r")) {
                    List<Element> ts = DomUtil.descendants(run, "t");
                    if (ts.size() != 1) {
                        failures.append(tag).append(": a run has ").append(ts.size())
                                .append(" <w:t> children, expected 1\n");
                    } else if (!"preserve".equals(DomUtil.attr(ts.get(0), "space"))) {
                        failures.append(tag).append(": <w:t> missing xml:space=preserve\n");
                    }
                }
                List<EmphasisSpan> reExtracted = extractEmphasis(p);
                if (!mergeAdjacent(reExtracted).equals(mergeAdjacent(slot.emphasis()))) {
                    failures.append(tag).append(": emphasis changed after round-trip: got ")
                            .append(reExtracted).append(" expected ").append(slot.emphasis()).append('\n');
                }
            }
        }

        System.out.println("substitute round-trip: checked " + checked + " supported bullets");
        System.out.println("FAILURES:\n" + (failures.isEmpty() ? "(none)" : failures));
        assertTrue(failures.isEmpty(), failures.toString());
    }

    /** Blanking keeps numPr, paints the mark white, and produces exactly lineCount lines of content. */
    @Test
    void blankProducesCorrectLineStructure() throws Exception {
        ResumeSlots rs = allNormalizedCorpusSlots().stream()
                .filter(r -> r.name().equals("resume_EHR"))
                .findFirst().orElseThrow();
        Slot slot = rs.slots().stream().filter(Slot::supported).findFirst().orElseThrow();
        Element p = slot.element();
        int lineCount = 2;

        Blanker.blank(p, lineCount);

        Element pPr = DomUtil.firstChild(p, "pPr");
        assertNotNull(DomUtil.firstChild(pPr, "numPr"), "numPr should survive blanking");

        Element markRPr = DomUtil.firstChild(pPr, "rPr");
        Element color = DomUtil.firstChild(markRPr, "color");
        assertEquals("FFFFFF", DomUtil.attr(color, "val"), "paragraph mark should be painted white");

        List<Element> runs = DomUtil.descendants(p, "r");
        assertEquals(1, runs.size(), "blank content should be one run");
        List<Element> breaks = DomUtil.descendants(runs.get(0), "br");
        List<Element> texts = DomUtil.descendants(runs.get(0), "t");
        assertEquals(lineCount - 1, breaks.size(), "should have lineCount-1 breaks");
        assertEquals(lineCount, texts.size(), "should have lineCount non-breaking-space texts");
        for (Element t : texts) {
            assertEquals("\u00A0", DomUtil.textContent(t), "each blank line should be a non-breaking space");
        }
    }

    /** Padding appends breaks + non-breaking spaces to the paragraph's last run. */
    @Test
    void padAppendsToLastRun() throws Exception {
        ResumeSlots rs = allNormalizedCorpusSlots().stream()
                .filter(r -> r.name().equals("resume_EHR"))
                .findFirst().orElseThrow();
        Slot slot = rs.slots().stream().filter(Slot::supported).findFirst().orElseThrow();
        Element p = slot.element();

        Substituter.substitute(p, new BulletText("Short one-line replacement.", List.of()));
        List<Element> runsBefore = DomUtil.descendants(p, "r");
        Element lastRunBefore = runsBefore.get(runsBefore.size() - 1);
        int breaksBefore = DomUtil.descendants(lastRunBefore, "br").size();

        Padder.pad(p, 2);

        List<Element> runsAfter = DomUtil.descendants(p, "r");
        assertEquals(runsBefore.size(), runsAfter.size(), "padding should not add new runs");
        Element lastRunAfter = runsAfter.get(runsAfter.size() - 1);
        assertEquals(breaksBefore + 2, DomUtil.descendants(lastRunAfter, "br").size());
        List<Element> texts = DomUtil.descendants(lastRunAfter, "t");
        assertEquals("\u00A0", DomUtil.textContent(texts.get(texts.size() - 1)));
        assertEquals("\u00A0", DomUtil.textContent(texts.get(texts.size() - 2)));
    }

    /** Spec 4.4 + 5: editing an unsupported bullet must fail loudly, never silently substitute. */
    @Test
    void editingUnsupportedBulletThrows() throws Exception {
        ResumeSlots rs = allNormalizedCorpusSlots().stream()
                .filter(r -> r.name().equals("My_resume1"))
                .findFirst().orElseThrow();
        Slot hyperlinkSlot = rs.slots().stream()
                .filter(s -> s.unsupportedReasons().contains("hyperlink"))
                .findFirst().orElseThrow(() -> new AssertionError("expected an unsupported hyperlink bullet"));

        assertThrows(UnsupportedBulletException.class,
                () -> Substituter.substitute(hyperlinkSlot.element(),
                        new BulletText("anything", List.of())));
        assertThrows(UnsupportedBulletException.class,
                () -> Blanker.blank(hyperlinkSlot.element(), 2));
    }

    // --- test plumbing -----------------------------------------------------

    private record ResumeSlots(String name, List<Slot> slots) {
    }

    private static List<ResumeSlots> allNormalizedCorpusSlots() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        FontNormalizer normalizer = new FontNormalizer(FontMap.loadDefault(), renderer);
        Path workDir = Files.createTempDirectory("edit-primitives-test");

        List<ResumeSlots> out = new ArrayList<>();
        for (Path docx : CorpusPaths.corpusDocx()) {
            String base = stripExt(docx.getFileName().toString());
            Path normalized = workDir.resolve(base + "-normalized.docx");
            normalizer.normalize(docx, normalized, 1, workDir);

            DocxPackage pkg = DocxPackage.open(normalized);
            Document documentXml = SafeXml.parse(pkg.readPart("word/document.xml"));
            Element numberingRoot = pkg.hasPart("word/numbering.xml")
                    ? SafeXml.parse(pkg.readPart("word/numbering.xml")).getDocumentElement() : null;
            Element stylesRoot = pkg.hasPart("word/styles.xml")
                    ? SafeXml.parse(pkg.readPart("word/styles.xml")).getDocumentElement() : null;
            NumberingResolver resolver = new NumberingResolver(numberingRoot, stylesRoot);

            List<Slot> slots = BulletDetector.detect(documentXml, resolver);
            out.add(new ResumeSlots(base, slots));
        }
        return out;
    }

    /** Mirrors BulletDetector's private emphasis extraction, for re-checking after an edit. */
    private static List<EmphasisSpan> extractEmphasis(Element p) {
        List<EmphasisSpan> emphasis = new ArrayList<>();
        int pos = 0;
        for (Element r : DomUtil.descendants(p, "r")) {
            String t = DomUtil.allText(r);
            if (t.isEmpty()) {
                continue;
            }
            Element rPr = DomUtil.firstChild(r, "rPr");
            boolean bold = RunFlags.isOn(rPr, "b");
            boolean italic = RunFlags.isOn(rPr, "i");
            if (bold || italic) {
                emphasis.add(new EmphasisSpan(pos, pos + t.length(), bold, italic));
            }
            pos += t.length();
        }
        return emphasis;
    }

    /**
     * Canonical form for comparing emphasis: adjacent spans with the same
     * bold/italic flags merged into one. Substituter writes one run per word,
     * so "RAG agent" in bold comes back as three touching bold spans.
     */
    private static List<EmphasisSpan> mergeAdjacent(List<EmphasisSpan> spans) {
        List<EmphasisSpan> out = new ArrayList<>();
        for (EmphasisSpan s : spans) {
            if (!out.isEmpty()) {
                EmphasisSpan last = out.get(out.size() - 1);
                if (last.end() == s.start() && last.bold() == s.bold() && last.italic() == s.italic()) {
                    out.set(out.size() - 1, new EmphasisSpan(last.start(), s.end(), s.bold(), s.italic()));
                    continue;
                }
            }
            out.add(s);
        }
        return out;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}