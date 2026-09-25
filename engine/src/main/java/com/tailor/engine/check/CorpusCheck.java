package com.tailor.engine.check;

import com.tailor.engine.calibrate.BatchCalibrator;
import com.tailor.engine.calibrate.BatchValidator;
import com.tailor.engine.calibrate.ProseSource;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.docx.XmlSerialize;
import com.tailor.engine.edit.Blanker;
import com.tailor.engine.edit.Padder;
import com.tailor.engine.edit.Substituter;
import com.tailor.engine.fonts.FontAudit;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.fonts.FontNormalizer;
import com.tailor.engine.fonts.NormalizeResult;
import com.tailor.engine.golden.GoldenBullet;
import com.tailor.engine.golden.GoldenResume;
import com.tailor.engine.golden.GoldenSpan;
import com.tailor.engine.measure.AnchorMeasurer;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.numbering.NumberingResolver;
import com.tailor.engine.render.RenderException;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.slots.BulletDetector;
import com.tailor.engine.slots.BulletText;
import com.tailor.engine.slots.DocxBulletDetection;
import com.tailor.engine.slots.EmphasisSpan;
import com.tailor.engine.slots.Slot;
import com.tailor.engine.verify.SlotEdit;
import com.tailor.engine.verify.Verifier;
import com.tailor.engine.verify.VerifyReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Step 1.6: runs the spec's acceptance tests (section 11) on every resume in a
 * corpus against its golden file, and collects one result per test per resume.
 *
 * <p>T5 (emphasis survives) is checked inside T4. T12 (hints within ±5 of
 * golden) is not run: the Java calibrator deliberately uses a different prose
 * source than the Python lab, so hints differ by design; calibration is
 * checked for self-consistency in the engine's unit tests instead.
 */
public final class CorpusCheck {

    public static final List<String> TESTS =
            List.of("T1", "T2", "T3", "T4", "T6", "T7", "T8", "T9", "T10", "T11");

    private static final String SHORT_SENTENCE = "Built a short one-line result.";
    private static final int PROSE_LENGTH = 900;

    public record Outcome(boolean pass, String detail) {
        static Outcome ok() {
            return new Outcome(true, "");
        }

        static Outcome fail(String detail) {
            return new Outcome(false, detail);
        }

        static Outcome skipped(String why) {
            return new Outcome(true, "skipped: " + why);
        }
    }

    public record ResumeResult(String name, Map<String, Outcome> outcomes) {
        public boolean allPass() {
            return outcomes.values().stream().allMatch(Outcome::pass);
        }
    }

    private final Renderer renderer;
    private final FontMap fontMap;
    private final boolean runDeterminism;

    public CorpusCheck(Renderer renderer, FontMap fontMap, boolean runDeterminism) {
        this.renderer = renderer;
        this.fontMap = fontMap;
        this.runDeterminism = runDeterminism;
    }

    public List<ResumeResult> run(Path corpusDir, Path goldenDir) throws Exception {
        List<Path> docs;
        try (var s = Files.list(corpusDir)) {
            docs = s.filter(p -> p.toString().endsWith(".docx")).sorted().toList();
        }
        List<ResumeResult> results = new ArrayList<>();
        for (Path docx : docs) {
            String name = stripExt(docx.getFileName().toString());
            Map<String, Outcome> outcomes = new LinkedHashMap<>();
            try {
                checkOne(docx, goldenDir.resolve(name + ".json"), outcomes);
            } catch (Exception e) {
                outcomes.put("ERROR", Outcome.fail(e.toString()));
            }
            results.add(new ResumeResult(name, outcomes));
        }
        return results;
    }

    private void checkOne(Path docx, Path goldenPath, Map<String, Outcome> out) throws Exception {
        GoldenResume golden = GoldenResume.load(goldenPath);
        Path work = Files.createTempDirectory("corpus-check-" + stripExt(docx.getFileName().toString()));
        CountingRenderer counting = new CountingRenderer(renderer);

        // T1 — normalization
        Path normalized = work.resolve("normalized.docx");
        NormalizeResult nr = new FontNormalizer(fontMap, counting).normalize(docx, normalized, 1, work);
        List<FontAudit.Violation> fontViolations =
                new FontAudit(fontMap).audit(counting.render(normalized, work));
        List<String> t1 = new ArrayList<>();
        if (nr.pages() != golden.pages()) t1.add("pages " + nr.pages() + " vs " + golden.pages());
        if (Math.abs(nr.shrinkPt() - golden.shrinkPt()) > 1e-9) t1.add("shrink " + nr.shrinkPt() + " vs " + golden.shrinkPt());
        if (nr.squeezeRemoved() != golden.squeezeRemoved()) t1.add("squeeze " + nr.squeezeRemoved() + " vs " + golden.squeezeRemoved());
        if (!fontViolations.isEmpty()) t1.add("fonts " + fontViolations);
        out.put("T1", t1.isEmpty() ? Outcome.ok() : Outcome.fail(String.join("; ", t1)));

        // T2 — detection
        List<Slot> slots = DocxBulletDetection.detect(normalized);
        out.put("T2", compareDetection(slots, golden.bullets()));

        // T3 — measurement (also the line counts every later test must keep)
        List<String> texts = slots.stream().map(Slot::text).toList();
        Map<Integer, Integer> lineCounts =
                new HashMap<>(AnchorMeasurer.measure(PdfLines.extract(counting.render(normalized, work)), texts));
        List<String> t3 = new ArrayList<>();
        for (int i = 0; i < golden.bullets().size() && i < slots.size(); i++) {
            Integer got = lineCounts.get(i);
            int want = golden.bullets().get(i).lines();
            if (got == null || got != want) t3.add("[" + i + "] " + got + " vs " + want);
        }
        out.put("T3", t3.isEmpty() ? Outcome.ok() : Outcome.fail(String.join(" ", t3)));

        // T4 — every supported bullet re-written with its own text and emphasis
        Map<Integer, SlotEdit> t4 = new LinkedHashMap<>();
        for (Slot s : slots) if (s.supported() && lineCounts.get(s.index()) != null) t4.put(s.index(), SlotEdit.SUBSTITUTED);
        out.put("T4", assembleAndVerify(normalized, lineCounts, t4, work, "t4", (s, L) -> {
            Substituter.substitute(s.element(), new BulletText(s.text(), s.emphasis()));
            return s.text();
        }));

        // T6 — genuinely new text; the validator picks what fits, the Verifier checks the result
        Map<Integer, List<BulletText>> newText = new LinkedHashMap<>();
        for (Slot s : slots) {
            if (s.supported() && lineCounts.get(s.index()) != null) {
                newText.put(s.index(), List.of(new BulletText(rotatedText(texts, s.index()), List.of())));
            }
        }
        Map<Integer, BulletText> fits = new LinkedHashMap<>();
        for (var r : BatchValidator.validate(normalized, counting, lineCounts, Map.of(), newText, work)) {
            if (r.outcome() == BatchValidator.Outcome.FITS) fits.put(r.slotIndex(), newText.get(r.slotIndex()).get(0));
        }
        if (fits.isEmpty()) {
            out.put("T6", Outcome.fail("no new-text candidate fit; nothing tested"));
        } else {
            Map<Integer, SlotEdit> t6 = new LinkedHashMap<>();
            for (Integer i : fits.keySet()) t6.put(i, SlotEdit.SUBSTITUTED);
            Outcome o = assembleAndVerify(normalized, lineCounts, t6, work, "t6", (s, L) -> {
                Substituter.substitute(s.element(), fits.get(s.index()));
                return fits.get(s.index()).text();
            });
            out.put("T6", o.pass() ? new Outcome(true, fits.size() + "/" + newText.size() + " fit") : o);
        }

        // Calibration, used by T7 and T11
        String prose = ProseSource.fromSlots(slots, PROSE_LENGTH);
        BatchCalibrator.Result cal = BatchCalibrator.calibrate(normalized, counting, prose, work);

        // T7 — hint + 60 chars must never be accepted
        Map<Integer, List<BulletText>> tooLong = new LinkedHashMap<>();
        for (var e : cal.hints().entrySet()) {
            tooLong.put(e.getKey(), List.of(new BulletText(
                    BatchCalibrator.wordPrefix(prose, e.getValue() + 60, e.getKey()), List.of())));
        }
        List<String> t7 = new ArrayList<>();
        for (var r : BatchValidator.validate(normalized, counting, cal.lineCounts(), cal.hints(), tooLong, work)) {
            if (r.outcome() == BatchValidator.Outcome.FITS || r.outcome() == BatchValidator.Outcome.FITS_WITH_PADDING) {
                t7.add("[" + r.slotIndex() + "] " + r.outcome());
            }
        }
        out.put("T7", t7.isEmpty() ? Outcome.ok() : Outcome.fail("accepted: " + String.join(" ", t7)));

        // T8 — a 40-character token is rejected without rendering
        int first = slots.stream().filter(Slot::supported).mapToInt(Slot::index).findFirst().orElse(-1);
        if (first < 0) {
            out.put("T8", Outcome.skipped("no supported slot"));
        } else {
            int before = counting.count();
            var r = BatchValidator.validate(normalized, counting, Map.of(first, 1), Map.of(first, 100),
                    Map.of(first, List.of(new BulletText("a".repeat(40), List.of()))), work);
            boolean ok = r.size() == 1 && r.get(0).outcome() == BatchValidator.Outcome.UNBREAKABLE_TOKEN
                    && counting.count() == before;
            out.put("T8", ok ? Outcome.ok() : Outcome.fail("outcome " + r + ", renders " + (counting.count() - before)));
        }

        // T9 — every second supported bullet deleted (blank of the same height)
        Map<Integer, SlotEdit> t9 = new LinkedHashMap<>();
        int k = 0;
        for (Slot s : slots) {
            if (s.supported() && lineCounts.get(s.index()) != null && k++ % 2 == 1) t9.put(s.index(), SlotEdit.BLANKED);
        }
        out.put("T9", assembleAndVerify(normalized, lineCounts, t9, work, "t9", (s, L) -> {
            Blanker.blank(s.element(), L);
            return null;
        }));

        // T10 — two-line bullets shortened to one line and padded
        Map<Integer, SlotEdit> t10 = new LinkedHashMap<>();
        for (Slot s : slots) {
            Integer L = lineCounts.get(s.index());
            if (s.supported() && L != null && L == 2) t10.put(s.index(), SlotEdit.PADDED);
        }
        out.put("T10", t10.isEmpty() ? Outcome.skipped("no two-line bullets")
                : assembleAndVerify(normalized, lineCounts, t10, work, "t10", (s, L) -> {
                    Substituter.substitute(s.element(), new BulletText(SHORT_SENTENCE, List.of()));
                    Padder.pad(s.element(), L - 1);
                    return SHORT_SENTENCE;
                }));

        // T11 — calibrating twice gives identical results
        if (!runDeterminism) {
            out.put("T11", Outcome.skipped("--skip-t11"));
        } else {
            BatchCalibrator.Result again = BatchCalibrator.calibrate(normalized, counting, prose, work);
            boolean same = again.hints().equals(cal.hints()) && again.lineCounts().equals(cal.lineCounts());
            out.put("T11", same ? Outcome.ok() : Outcome.fail("hints differ between runs"));
        }
    }

    // --- helpers -----------------------------------------------------------

    private interface SlotAction {
        /** Applies the edit to a freshly parsed slot; returns its new visible text (null if blanked). */
        String apply(Slot fresh, int targetLines);
    }

    private Outcome assembleAndVerify(Path normalized, Map<Integer, Integer> lineCounts,
                                      Map<Integer, SlotEdit> edits, Path work, String label,
                                      SlotAction action) throws Exception {
        DocxPackage pkg = DocxPackage.open(normalized);
        var doc = SafeXml.parse(pkg.readPart("word/document.xml"));
        var numbering = pkg.hasPart("word/numbering.xml")
                ? SafeXml.parse(pkg.readPart("word/numbering.xml")).getDocumentElement() : null;
        var styles = pkg.hasPart("word/styles.xml")
                ? SafeXml.parse(pkg.readPart("word/styles.xml")).getDocumentElement() : null;
        List<Slot> fresh = BulletDetector.detect(doc, new NumberingResolver(numbering, styles));

        List<String> assembled = new ArrayList<>();
        for (Slot s : fresh) {
            assembled.add(edits.containsKey(s.index()) ? action.apply(s, lineCounts.get(s.index())) : s.text());
        }
        pkg.writePart("word/document.xml", XmlSerialize.toBytes(doc));
        Path out = work.resolve(label + ".docx");
        pkg.save(out);

        VerifyReport r = Verifier.verify(normalized, out, renderer, fontMap, lineCounts, assembled, edits, work);
        if (r.ok()) {
            return Outcome.ok();
        }
        StringBuilder sb = new StringBuilder("pages ").append(r.pagesBefore()).append("->").append(r.pagesAfter())
                .append(", layout shift ").append(r.layoutShiftPt());
        if (r.layoutProblem() != null) sb.append(", ").append(r.layoutProblem());
        if (!r.fontViolations().isEmpty()) sb.append(", fonts ").append(r.fontViolations());
        r.lineChecks().forEach((i, c) -> {
            if (!c.ok()) sb.append(", [").append(i).append("] ").append(c.measured()).append(" vs ").append(c.target());
        });
        return Outcome.fail(sb.toString());
    }

    private static Outcome compareDetection(List<Slot> slots, List<GoldenBullet> golden) {
        if (slots.size() != golden.size()) {
            return Outcome.fail("slot count " + slots.size() + " vs " + golden.size());
        }
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            GoldenBullet g = golden.get(i);
            if (!s.locator().equals(g.locator())) bad.add("[" + i + "] locator");
            if (!s.text().equals(g.text())) bad.add("[" + i + "] text");
            if (s.supported() != g.supported()) bad.add("[" + i + "] supported");
            if (!s.unsupportedReasons().equals(g.unsupportedReasons())) bad.add("[" + i + "] reasons");
            if (!sameEmphasis(s.emphasis(), g.emphasis())) bad.add("[" + i + "] emphasis");
        }
        return bad.isEmpty() ? Outcome.ok() : Outcome.fail(String.join(" ", bad));
    }

    private static boolean sameEmphasis(List<EmphasisSpan> got, List<GoldenSpan> want) {
        if (got.size() != want.size()) return false;
        for (int i = 0; i < got.size(); i++) {
            EmphasisSpan a = got.get(i);
            GoldenSpan b = want.get(i);
            if (a.start() != b.start() || a.end() != b.end() || a.bold() != b.bold() || a.italic() != b.italic()) {
                return false;
            }
        }
        return true;
    }

    /** Other bullets' text starting after slot i, cut at a word boundary to slot i's own length. */
    private static String rotatedText(List<String> texts, int i) {
        int max = texts.get(i).length();
        StringBuilder src = new StringBuilder();
        for (int k = 1; k <= texts.size(); k++) src.append(texts.get((i + k) % texts.size()).strip()).append(' ');
        String out = "";
        for (String w : src.toString().split(" ")) {
            if (w.isEmpty()) continue;
            String next = out.isEmpty() ? w : out + " " + w;
            if (next.length() > max) break;
            out = next;
        }
        return out;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** Counts renders, so T8 can prove a rejection cost zero renders. */
    private static final class CountingRenderer implements Renderer {
        private final Renderer delegate;
        private final AtomicInteger calls = new AtomicInteger();

        CountingRenderer(Renderer delegate) {
            this.delegate = delegate;
        }

        @Override
        public Path render(Path docxPath, Path outDir) throws RenderException {
            calls.incrementAndGet();
            return delegate.render(docxPath, outDir);
        }

        @Override
        public String version() {
            return delegate.version();
        }

        int count() {
            return calls.get();
        }
    }
}