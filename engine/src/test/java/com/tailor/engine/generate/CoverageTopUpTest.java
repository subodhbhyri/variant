package com.tailor.engine.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.blocks.Position;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PHASE4_SPEC.md sections 6.1 and 6.2, replayed through the real render check on the synthetic projects
 * resume. The recorded responses reuse the resume's own bullets, so each one is known to fit the line count
 * it claims. Corpus tag: renders through LibreOffice.
 */
@Tag("corpus")
class CoverageTopUpTest {

    /** A project section ready for FitLoop, with the resume's swappable project slots as its render-check slots. */
    private record Fixture(SectionContext ctx, OnboardReport report, SectionPositions positions) {
    }

    private static Fixture projectSection(String bulletEnding) throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        Path outDir = Files.createTempDirectory("coverage-projects");
        byte[] upload = Files.readAllBytes(CorpusPaths.phase3FixturesDir().resolve("projects_synthetic.docx"));
        OnboardReport report = new OnboardPipeline(renderer, FontMap.loadDefault()).run(upload, outDir);
        assertTrue(report.accepted(), "fixture failed to onboard: " + report.reason());
        Path normalized = outDir.resolve("normalized.docx");
        SectionPositions positions = SectionPositions.detect(normalized, report);

        Map<Integer, List<Integer>> pooled = new HashMap<>();
        for (Position p : positions.projectPositions()) {
            if (!p.swappable()) {
                continue;
            }
            for (Map.Entry<Integer, List<Integer>> e : positions.slotIndicesByLineCount(p, report).entrySet()) {
                pooled.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
            }
        }
        List<String> sources = new ArrayList<>();
        for (OnboardReport.SlotReport sr : report.slots()) {
            sources.add(sr.text());
        }
        String rawText = "Built the projects shown above.";
        sources.add(rawText);
        TargetBuilder.SectionTarget target = TargetBuilder.forProjects(report, positions);
        SectionContext ctx = new SectionContext(
                "project-new-1", "project", "DETAILED", List.of(), List.of(), rawText, sources,
                target.lineCounts(), List.of(), target.candidateCount(), target.budgetCharsByLineCount(), pooled,
                normalized, renderer, bulletEnding);
        return new Fixture(ctx, report, positions);
    }

    /** An editable slot's text with the given line count, preferring one whose ending matches {@code withPeriod}. */
    private static String slotText(OnboardReport report, int lines, boolean withPeriod) {
        String fallback = null;
        for (OnboardReport.SlotReport sr : report.slots()) {
            if (!sr.editable() || sr.lines() == null || sr.lines() != lines || sr.text() == null) {
                continue;
            }
            String t = sr.text().strip();
            if (t.endsWith(".") == withPeriod) {
                return t;
            }
            fallback = fallback == null ? t : fallback;
        }
        assertTrue(fallback != null, "the fixture has no editable " + lines + "-line bullet");
        return fallback;
    }

    /** The line counts of each swappable project position in the home section, one list per position. */
    private static List<List<Integer>> homeShapes(Fixture f, String home) {
        List<List<Integer>> out = new ArrayList<>();
        for (int i = 0; i < f.positions().projectPositions().size(); i++) {
            Position p = f.positions().projectPositions().get(i);
            if (p.swappable() && home.equals(f.positions().projectPositionSections().get(i))) {
                out.add(f.positions().bulletSlotIndices(p).stream()
                        .map(idx -> f.report().slots().get(idx).lines()).toList());
            }
        }
        return out;
    }

    /** The first home section with a two-slot shape of one 1-line and one 2-line slot, in any order. */
    private static String homeWithAPairShape(Fixture f) {
        for (int i = 0; i < f.positions().projectPositions().size(); i++) {
            if (!f.positions().projectPositions().get(i).swappable()) {
                continue;
            }
            String section = f.positions().projectPositionSections().get(i);
            boolean pair = homeShapes(f, section).stream().anyMatch(s -> s.size() == 2 && s.contains(1) && s.contains(2));
            if (pair) {
                return section;
            }
        }
        throw new IllegalStateException("the fixture has no home section with a 1-line and 2-line pair shape");
    }

    /** A client that replays fixed responses in order and records each user message it was sent. */
    private static final class Replay implements ModelClient {
        private final Deque<ModelResponse> queue;
        final List<String> users = new ArrayList<>();

        Replay(List<ModelResponse> responses) {
            this.queue = new ArrayDeque<>(responses);
        }

        @Override
        public ModelResponse call(String systemPrompt, String userMessage) {
            users.add(userMessage);
            return queue.poll();
        }
    }

    private static ModelResponse bullets(String... idLengthText) {
        List<ModelResponse.BulletCandidate> out = new ArrayList<>();
        for (int i = 0; i < idLengthText.length; i += 3) {
            out.add(new ModelResponse.BulletCandidate(idLengthText[i], Map.of(idLengthText[i + 1], idLengthText[i + 2])));
        }
        return new ModelResponse(out, new ModelResponse.Usage(0, 0, 0, 0));
    }

    @Test
    void aProjectThatFillsNoHomePositionGetsOneTopUpCallForTheMissingLengths() throws Exception {
        Fixture f = projectSection("");
        SkillsDictionary skills = SkillsDictionary.load(CorpusPaths.phase4FixturesDir().resolve("skills_seed.json"));
        String home = homeWithAPairShape(f);
        List<List<Integer>> shapes = homeShapes(f, home);

        // Base call: one bullet that can't fit its 1-line slot (dropped; a failed text is never sent back).
        String tooLong = "Built " + "a very long project description ".repeat(12).strip() + ".";
        // Top-up call: a 1-line and a 2-line bullet, the 2-line one ending in a period the resume doesn't use.
        String oneLine = slotText(f.report(), 1, false);
        String twoLine = slotText(f.report(), 2, true);
        Replay client = new Replay(List.of(bullets("b1", "1", tooLong),
                bullets("t1", "1", oneLine, "t2", "2", twoLine)));

        FitLoop.FitLoopResult base = FitLoop.run(f.ctx(), client, skills);
        FitLoop.TopUp topUp = FitLoop.coverTopUp(f.ctx(), client, skills, base, shapes);

        assertFalse(topUp.before().covered(), "with nothing kept, no home position can be filled");
        assertTrue(topUp.before().missingLengths().containsAll(List.of(1, 2)), "both lengths are missing");
        assertTrue(topUp.after().covered(), "the top-up's bullets fill a home position");
        assertEquals(2, client.users.size(), "exactly one fit-loop call and one top-up call");
        String topUpPrompt = client.users.get(1);
        assertFalse(topUpPrompt.contains("a very long project description"), "a failed text is never sent back");
        assertEquals(oneLine.replaceAll("\\.$", ""), topUp.result().finalResults().get("top-t1").variants().get("1"));
        String added = topUp.result().finalResults().get("top-t2").variants().get("2");
        assertEquals(twoLine.replaceAll("\\.$", ""), added, "the new bullet follows the resume's convention");
    }

    @Test
    void aVariantWithTheWrongEndingIsNormalizedBeforeItsRenderCheck() throws Exception {
        Fixture f = projectSection("");
        SkillsDictionary skills = SkillsDictionary.load(CorpusPaths.phase4FixturesDir().resolve("skills_seed.json"));
        String withPeriod = slotText(f.report(), 1, true);
        Replay client = new Replay(List.of(bullets("b1", "1", withPeriod)));

        FitLoop.FitLoopResult result = FitLoop.run(f.ctx(), client, skills);

        FitLoop.CandidateOutcome outcome = result.finalResults().get("b1");
        assertEquals("OK", outcome.status(), "the normalized text passes the render check");
        String variant = outcome.variants().get("1");
        assertEquals(withPeriod.replaceAll("\\.$", ""), variant, "the final text follows the resume's convention");
        assertFalse(result.attempts().isEmpty());
        for (FitLoop.Attempt attempt : result.attempts()) {
            assertFalse(attempt.text().endsWith("."), "the render check saw the normalized text: " + attempt.text());
        }
    }
}
