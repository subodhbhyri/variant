package com.tailor.engine.calibrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.fonts.FontNormalizer;
import com.tailor.engine.render.RenderException;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.slots.BulletText;
import com.tailor.engine.slots.DocxBulletDetection;
import com.tailor.engine.slots.EmphasisSpan;
import com.tailor.engine.slots.Slot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Step 1.5 validation tests (spec section 7.4). */
class BatchValidatorCorpusTest {

    private static final int PROSE_LENGTH = 900;

    /** T8: a 40-char token is rejected with zero renders, on every resume's first eligible slot. */
    @Test
    void unbreakableTokenNeverRenders() throws Exception {
        CountingRenderer renderer = new CountingRenderer(new LibreOfficeRenderer());
        FontNormalizer normalizer = new FontNormalizer(FontMap.loadDefault(), renderer);
        Path workDir = Files.createTempDirectory("validator-token-test");

        for (Path docx : CorpusPaths.corpusDocx()) {
            Path normalized = workDir.resolve(stripExt(docx) + "-normalized.docx");
            normalizer.normalize(docx, normalized, 1, workDir);

            List<Slot> slots = DocxBulletDetection.detect(normalized);
            int firstSupported = firstSupportedIndex(slots);
            if (firstSupported < 0) {
                continue;
            }

            String fortyCharToken = "a".repeat(40);
            BulletText candidate = new BulletText(fortyCharToken, List.<EmphasisSpan>of());

            Map<Integer, Integer> lineCounts = Map.of(firstSupported, 1);
            Map<Integer, Integer> hints = Map.of(firstSupported, 100);
            Map<Integer, List<BulletText>> candidates = Map.of(firstSupported, List.of(candidate));

            int rendersBefore = renderer.count();
            List<BatchValidator.CandidateResult> results =
                    BatchValidator.validate(normalized, renderer, lineCounts, hints, candidates, workDir);

            assertEquals(1, results.size());
            assertEquals(BatchValidator.Outcome.UNBREAKABLE_TOKEN, results.get(0).outcome());
            assertEquals(rendersBefore, renderer.count(), "the 40-char token candidate should cost zero renders");
        }
    }

    /** T7 (relaxed to the real invariant): a far-too-long candidate is never FITS/FITS_WITH_PADDING. */
    @Test
    void tooLongCandidatesAreRejected() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        FontNormalizer normalizer = new FontNormalizer(FontMap.loadDefault(), renderer);
        Path workDir = Files.createTempDirectory("validator-toolong-test");

        Path source = resumeEhr();
        Path normalized = workDir.resolve("resume_EHR-normalized.docx");
        normalizer.normalize(source, normalized, 1, workDir);

        List<Slot> slots = DocxBulletDetection.detect(normalized);
        String prose = ProseSource.fromSlots(slots, PROSE_LENGTH);
        BatchCalibrator.Result cal = BatchCalibrator.calibrate(normalized, renderer, prose, workDir);

        Map<Integer, List<BulletText>> candidates = new LinkedHashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            Integer hint = cal.hints().get(i);
            if (hint == null) {
                continue;
            }
            String tooLong = BatchCalibrator.wordPrefix(prose, hint + 60, i);
            candidates.put(i, List.of(new BulletText(tooLong, List.of())));
        }

        List<BatchValidator.CandidateResult> results =
                BatchValidator.validate(normalized, renderer, cal.lineCounts(), cal.hints(), candidates, workDir);

        StringBuilder failures = new StringBuilder();
        for (var r : results) {
            if (r.outcome() == BatchValidator.Outcome.FITS
                    || r.outcome() == BatchValidator.Outcome.FITS_WITH_PADDING) {
                failures.append("slot ").append(r.slotIndex()).append(" unexpectedly ").append(r.outcome())
                        .append(" for a hint+60 candidate\n");
            }
        }
        System.out.println("resume_EHR too-long outcomes: " + results);
        assertTrue(failures.isEmpty(), failures.toString());
    }

    /** A candidate at exactly the calibrated hint should FITS or FITS_WITH_PADDING, never TOO_LONG. */
    @Test
    void hintLengthCandidatesFitOrPad() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        FontNormalizer normalizer = new FontNormalizer(FontMap.loadDefault(), renderer);
        Path workDir = Files.createTempDirectory("validator-hintfit-test");

        Path source = resumeEhr();
        Path normalized = workDir.resolve("resume_EHR-normalized.docx");
        normalizer.normalize(source, normalized, 1, workDir);

        List<Slot> slots = DocxBulletDetection.detect(normalized);
        String prose = ProseSource.fromSlots(slots, PROSE_LENGTH);
        BatchCalibrator.Result cal = BatchCalibrator.calibrate(normalized, renderer, prose, workDir);

        Map<Integer, List<BulletText>> candidates = new LinkedHashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            Integer hint = cal.hints().get(i);
            if (hint == null) {
                continue;
            }
            String atHint = BatchCalibrator.wordPrefix(prose, hint, i);
            candidates.put(i, List.of(new BulletText(atHint, List.of())));
        }

        List<BatchValidator.CandidateResult> results =
                BatchValidator.validate(normalized, renderer, cal.lineCounts(), cal.hints(), candidates, workDir);

        StringBuilder failures = new StringBuilder();
        for (var r : results) {
            if (r.outcome() != BatchValidator.Outcome.FITS && r.outcome() != BatchValidator.Outcome.FITS_WITH_PADDING) {
                failures.append("slot ").append(r.slotIndex()).append(": ").append(r.outcome())
                        .append(" for its own calibrated hint length\n");
            }
        }
        System.out.println("resume_EHR at-hint outcomes: " + results);
        assertTrue(failures.isEmpty(), failures.toString());
    }

    private static int firstSupportedIndex(List<Slot> slots) {
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).supported()) {
                return i;
            }
        }
        return -1;
    }

    private static Path resumeEhr() throws Exception {
        return CorpusPaths.corpusDocx().stream()
                .filter(p -> p.getFileName().toString().equals("resume_EHR.docx"))
                .findFirst().orElseThrow();
    }

    private static String stripExt(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** Wraps a Renderer to count calls, so a test can assert zero renders happened. */
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