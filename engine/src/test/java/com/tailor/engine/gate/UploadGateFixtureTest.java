package com.tailor.engine.gate;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.golden.Phase2Fixtures;
import com.tailor.engine.golden.Phase2Fixtures.Expected;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * P2-T1 (PHASE2_SPEC.md section 6): every fixture in fixtures/phase2/ must
 * get exactly the gate outcome fixtures/phase2/expected.json says.
 *
 * TOO_MANY_PAGES and TOO_FEW_EDITABLE are decided later, by the onboarding
 * pipeline (section 4) after normalizing and rendering — not by the static
 * gate. too_many_pages.docx and too_few_bullets.docx are safe, well-formed
 * files, so the gate itself must accept them even though expected.json's
 * "accept" field for them describes the final (post-pipeline) outcome.
 *
 * UploadGate has no Renderer dependency at all — it works from bytes and a
 * ZipFile only — so "a rejected upload costs zero renders" holds
 * structurally at this stage. A counting-renderer proof belongs to the
 * `tailor onboard` tests (P2-T4/P2-T7, section 7 step 4), once a Renderer is
 * actually reachable from the entry point.
 */
class UploadGateFixtureTest {

    private static final Set<String> DECIDED_LATER_IN_PIPELINE = Set.of(
            GateReason.TOO_MANY_PAGES, GateReason.TOO_FEW_EDITABLE);

    @Test
    void everyFixtureMatchesItsExpectedGateOutcome() throws Exception {
        Path fixturesDir = CorpusPaths.phase2FixturesDir();
        Map<String, Expected> expected =
                Phase2Fixtures.load(fixturesDir.resolve("expected.json"));
        assertTrue(expected.size() >= 20, "expected.json unexpectedly small: " + expected.size());

        StringBuilder failures = new StringBuilder();

        for (Map.Entry<String, Expected> e : expected.entrySet()) {
            String fileName = e.getKey();
            Expected exp = e.getValue();
            boolean gateShouldAccept = exp.accept() || DECIDED_LATER_IN_PIPELINE.contains(exp.reason());

            byte[] bytes = Files.readAllBytes(fixturesDir.resolve(fileName));
            GateResult result = UploadGate.check(bytes);

            if (gateShouldAccept && !result.accepted()) {
                failures.append(fileName).append(": expected gate to accept, got reject reason=")
                        .append(result.reason()).append('\n');
            } else if (!gateShouldAccept && result.accepted()) {
                failures.append(fileName).append(": expected gate to reject with reason=")
                        .append(exp.reason()).append(", gate accepted\n");
            } else if (!gateShouldAccept && !exp.reason().equals(result.reason())) {
                failures.append(fileName).append(": expected reason=").append(exp.reason())
                        .append(", got=").append(result.reason()).append('\n');
            }
        }

        assertTrue(failures.isEmpty(), "P2-T1 failures:\n" + failures);
    }
}
