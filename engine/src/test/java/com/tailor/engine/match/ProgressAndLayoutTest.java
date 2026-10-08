package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.generate.SectionPositions;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.layout.PageLayout;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.progress.ProgressListener;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PHASE6_SPEC.md revision 3, on the real fixtures through LibreOffice ({@code corpus} tag): the engine reports the stages
 * it really runs, as it runs them, without changing what it computes; and the page layout is the engine's own
 * measurement of the rendered PDF.
 */
@Tag("corpus")
class ProgressAndLayoutTest {

    private record Event(String kind, String stage, Map<String, Object> detail) {
    }

    private static final class Recorder implements ProgressListener {
        final List<Event> events = new CopyOnWriteArrayList<>();

        @Override
        public void started(String stage, Map<String, Object> detail) {
            events.add(new Event("started", stage, detail));
        }

        @Override
        public void progress(String stage, Map<String, Object> detail) {
            events.add(new Event("progress", stage, detail));
        }

        @Override
        public void finished(String stage, Map<String, Object> detail) {
            events.add(new Event("finished", stage, detail));
        }

        List<String> sequence() {
            List<String> out = new ArrayList<>();
            for (Event e : events) {
                out.add(e.kind() + " " + e.stage());
            }
            return out;
        }
    }

    private static final Renderer RENDERER = new LibreOfficeRenderer();

    private static byte[] fixture() throws Exception {
        return Files.readAllBytes(CorpusPaths.phase3FixturesDir().resolve("projects_synthetic.docx"));
    }

    // ---- onboarding -------------------------------------------------------------------------

    @Test
    void onboardingReportsItsStagesAndIsByteIdenticalWithOrWithoutAListener() throws Exception {
        FontMap fontMap = FontMap.loadDefault();
        Path plainDir = Files.createTempDirectory("progress-plain");
        Path listenedDir = Files.createTempDirectory("progress-listened");
        OnboardReport plain = new OnboardPipeline(RENDERER, fontMap).run(fixture(), plainDir);
        Recorder recorder = new Recorder();
        OnboardReport listened = new OnboardPipeline(RENDERER, fontMap).run(fixture(), listenedDir, recorder);

        assertTrue(listened.accepted(), String.valueOf(listened.reason()));
        assertEquals(List.of("started safety_checks", "finished safety_checks", "started fonts", "finished fonts",
                "started find_bullets", "finished find_bullets", "started measure_lines", "finished measure_lines"),
                recorder.sequence());
        Event bullets = recorder.events.get(5);
        assertEquals(listened.editableCount(), bullets.detail().get("found"));
        assertEquals(listened.slots().size(), bullets.detail().get("total"));
        assertEquals(listened.pages(), recorder.events.get(3).detail().get("pages"));
        assertEquals(listened.slots().stream().filter(s -> s.lines() != null).count(),
                ((Number) recorder.events.get(7).detail().get("measured")).longValue());

        // (A PDF and a zip carry the time they were made, so two runs never match byte for byte: compare what they hold.)
        for (String file : List.of("onboard.json", "baseline.json")) {
            assertEquals(Files.readString(plainDir.resolve(file)), Files.readString(listenedDir.resolve(file)),
                    file + " must not depend on whether anyone is listening");
        }
        assertEquals(entriesOf(plainDir.resolve("normalized.docx")), entriesOf(listenedDir.resolve("normalized.docx")),
                "the normalized document holds the same parts");
        assertEquals(PdfLines.extractLayout(plainDir.resolve("preview.pdf")),
                PdfLines.extractLayout(listenedDir.resolve("preview.pdf")), "the preview shows the same lines in the same places");
        assertTrue(plain.accepted());
    }

    @Test
    void aRejectedUploadStopsAfterTheSafetyChecksAndSaysWhy() throws Exception {
        Recorder recorder = new Recorder();
        OnboardReport report = new OnboardPipeline(RENDERER, FontMap.loadDefault())
                .run("not a docx at all".getBytes(), Files.createTempDirectory("progress-reject"), recorder);

        assertFalse(report.accepted());
        assertEquals(List.of("started safety_checks", "finished safety_checks"), recorder.sequence());
        assertEquals(report.reason(), recorder.events.get(1).detail().get("reason"));
    }

    private static Map<String, List<Byte>> entriesOf(Path docx) throws Exception {
        Map<String, List<Byte>> out = new java.util.TreeMap<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(docx.toFile())) {
            for (var entries = zip.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement();
                byte[] bytes = zip.getInputStream(entry).readAllBytes();
                List<Byte> boxed = new ArrayList<>(bytes.length);
                for (byte b : bytes) {
                    boxed.add(b);
                }
                out.put(entry.getName(), boxed);
            }
        }
        return out;
    }

    // ---- matching ---------------------------------------------------------------------------

    private record Setup(MatchRunner.Context ctx, Path onboardDir, OnboardReport report, String jd) {
    }

    private Setup setup() throws Exception {
        FontMap fontMap = FontMap.loadDefault();
        Path onboardDir = Files.createTempDirectory("progress-onboard");
        OnboardReport report = new OnboardPipeline(RENDERER, fontMap).run(fixture(), onboardDir);
        assumeTrue(report.accepted(), "fixture failed to onboard: " + report.reason());
        MatchRunner.Context ctx = MatchRunner.buildContext(onboardDir.resolve("normalized.docx"),
                CorpusPaths.phase5FixturesDir().resolve("match_run").resolve("variants.json"),
                CorpusPaths.phase3FixturesDir().resolve("library.json"), SkillsDictionary.loadDefault(),
                new FakeEmbedder(), RENDERER, fontMap);
        String jd = Files.readString(CorpusPaths.phase5FixturesDir().resolve("jds").resolve("platform.txt"));
        return new Setup(ctx, onboardDir, report, jd);
    }

    @Test
    void matchingReportsReadChooseAndEveryPlacementInOrderThenVerifyAndChangesNothing() throws Exception {
        Setup s = setup();
        Path plainOut = Files.createTempDirectory("progress-plain-out").resolve("resume-1.docx");
        MatchRunner.Result plain = MatchRunner.runOne(s.ctx(), s.jd(), Files.createTempDirectory("progress-w1"), plainOut);
        Recorder recorder = new Recorder();
        Path listenedOut = Files.createTempDirectory("progress-listened-out").resolve("resume-1.docx");
        MatchRunner.Result listened =
                MatchRunner.runOne(s.ctx(), s.jd(), Files.createTempDirectory("progress-w2"), listenedOut, recorder);

        assertNull(plain.renderFailureReason(), String.valueOf(plain.renderFailureReason()));
        assertNull(listened.renderFailureReason());
        assertEquals(plain.resumes(), listened.resumes(), "the engine's answer does not depend on being listened to");
        assertEquals(plain.missing(), listened.missing());

        List<String> sequence = recorder.sequence();
        assertEquals(List.of("started read_posting", "finished read_posting", "started choose_material",
                "finished choose_material", "started place_projects"), sequence.subList(0, 5));
        int placedFinished = sequence.indexOf("finished place_projects");
        int verifyStarted = sequence.indexOf("started verify");
        int verifyFinished = sequence.indexOf("finished verify");
        assertTrue(placedFinished > 4 && verifyStarted > placedFinished && verifyFinished > verifyStarted, sequence.toString());
        assertEquals("finished verify", sequence.get(sequence.size() - 1));

        // One progress event per project put in a position, between place_projects started and finished: exactly the
        // pairs resume #1 ends with.
        List<String> placed = new ArrayList<>();
        for (int i = 5; i < placedFinished; i++) {
            Event e = recorder.events.get(i);
            assertEquals("progress place_projects", e.kind() + " " + e.stage());
            placed.add(e.detail().get("position") + "=" + e.detail().get("project"));
        }
        List<String> expected = new ArrayList<>();
        for (AssembledResume.ProjectAssignment a : listened.resumes().get(0).projects()) {
            expected.add(a.position() + "=" + a.project());
        }
        assertEquals(expected.stream().sorted().toList(), placed.stream().sorted().toList());
        assertFalse(placed.isEmpty());

        // Only ids, names of positions and counts: no text of the resume or the posting.
        for (Event e : recorder.events) {
            for (Object value : e.detail().values()) {
                assertTrue(value instanceof Number || String.valueOf(value).length() < 40, e.toString());
            }
        }
        assertEquals(1, recorder.events.stream().filter(e -> e.stage().equals("verify") && e.kind().equals("started")).count());
    }

    // ---- layout -----------------------------------------------------------------------------

    @Test
    void theLayoutIsTheEnginesOwnMeasurementOfTheRenderedPdf() throws Exception {
        Setup s = setup();
        Path docx = s.onboardDir().resolve("normalized.docx");
        Path pdf = s.onboardDir().resolve("preview.pdf");

        PdfLines.Layout measured = PdfLines.extractLayout(pdf);
        assertEquals(PdfLines.extract(pdf), measured.lines(), "line boxes come from the same clustering as the lines");
        assertEquals(measured.lines().size(), measured.boxes().size());

        PageLayout.Layout layout = PageLayout.build(docx, pdf, s.report());
        assertEquals(s.report().pages(), layout.pages().size());
        for (PdfLines.PageSize page : layout.pages()) {
            assertTrue(page.width() > 500 && page.width() < 650, "page width " + page.width());
            assertTrue(page.height() > 700 && page.height() < 850, "page height " + page.height());
        }

        SectionPositions positions = SectionPositions.detect(docx, s.report());
        assertEquals(positions.slots().size(), layout.slots().size());
        int withLines = 0;
        for (PageLayout.SlotLayout slot : layout.slots()) {
            OnboardReport.SlotReport sr = s.report().slots().get(slot.slot());
            assertEquals(sr.editable(), slot.editable());
            assertEquals(sr.lockReason(), slot.lockReason());
            assertEquals(slot.lineCount() == null ? 0 : slot.lineCount(), slot.lines().size());
            if (sr.editable() && sr.lines() != null) {
                assertEquals(sr.lines(), slot.lineCount(), "the layout's lines are the calibrated line count");
                withLines++;
            }
            double previousBottom = -1;
            int previousPage = -1;
            for (PageLayout.Box b : slot.lines()) {
                PdfLines.PageSize page = layout.pages().get(b.page());
                assertTrue(b.w() > 0 && b.h() > 0, slot.slot() + " has an empty box");
                assertTrue(b.x() >= 0 && b.x() + b.w() <= page.width() + 1, "box inside the page width: " + b);
                assertTrue(b.y() >= -1 && b.y() + b.h() <= page.height() + 1, "box inside the page height: " + b);
                if (b.page() == previousPage) {
                    assertTrue(b.y() >= previousBottom - b.h(), "lines of a bullet go down the page");
                }
                previousPage = b.page();
                previousBottom = b.y();
            }
        }
        assertTrue(withLines >= 3, "the fixture has editable bullets with measured lines");

        assertEquals(positions.jobPositions().size() + positions.projectPositions().size(), layout.positions().size());
        for (PageLayout.PositionLayout position : layout.positions()) {
            assertFalse(position.lines().isEmpty(), position.id() + " has lines on the page");
            // A position's lines include all of its bullets' lines.
            int bulletLines = position.slots().stream()
                    .mapToInt(i -> layout.slots().get(i).lines().size()).sum();
            assertTrue(position.lines().size() >= bulletLines, position.id());
            if (position.id().startsWith("P")) {
                assertEquals("project", position.kind());
            }
        }
    }

    // ---- section roles through the whole engine -------------------------------------------

    @Test
    void aRoleTheUserChoseIsUsedByPositionAnalysisThroughTheReport() throws Exception {
        Setup s = setup();
        Path docx = s.onboardDir().resolve("normalized.docx");
        int expected = SectionPositions.detect(docx, s.report()).projectPositions().size();
        assertTrue(expected > 0);

        OnboardReport demoted = s.report().withSectionRoles(Map.of("Projects", "other"));
        assertEquals(0, SectionPositions.detect(docx, demoted).projectPositions().size());
        assertEquals(expected, SectionPositions.detect(docx, s.report().withSectionRoles(Map.of())).projectPositions().size());
        // The choice travels in onboard.json, where match and generate read it.
        Path file = Files.createTempFile("onboard-roles", ".json");
        demoted.writeTo(file);
        assertEquals(Map.of("Projects", "other"), OnboardReport.readFrom(file).sectionRoles());
    }
}
