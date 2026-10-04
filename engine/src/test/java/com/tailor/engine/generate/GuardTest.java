package com.tailor.engine.generate;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.golden.Phase4Fixtures;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P4-T1 (PHASE4_SPEC.md section 9): every case in fixtures/phase4/guard_cases.json must give
 * exactly the expected reasons, in order, matching reference/guard_ref.py.
 */
class GuardTest {

    @Test
    void everyGuardCaseMatchesExpectedReasons() throws Exception {
        Path fixturesDir = CorpusPaths.phase4FixturesDir();
        Phase4Fixtures.GuardCases fixture =
                Phase4Fixtures.loadGuardCases(fixturesDir.resolve("guard_cases.json"));
        SkillsDictionary skills = SkillsDictionary.load(fixturesDir.resolve("skills_seed.json"));

        StringBuilder failures = new StringBuilder();
        for (Phase4Fixtures.GuardCase c : fixture.cases()) {
            List<String> got = TruthfulnessGuard.guard(c.variant(), fixture.sourceTexts(), c.budgetChars(), skills);
            if (!got.equals(c.expected())) {
                failures.append(c.name()).append(": expected ").append(c.expected())
                        .append(", got ").append(got).append('\n');
            }
        }

        System.out.println("guard cases checked: " + fixture.cases().size());
        System.out.println("FAILURES:\n" + (failures.isEmpty() ? "(none)" : failures));
        assertTrue(failures.isEmpty(), "P4-T1 failures:\n" + failures);
    }

    /** P4-T1 (PHASE4_SPEC.md section 5, revision 5): the new number-parsing rules — units glued
     * or after one space, only K/k/M/B/bn as multipliers, versions compared as text — each of
     * these 8 cases carries its own sources (unlike guard_cases.json's single shared list). */
    @Test
    void everyGuardNumberCaseMatchesExpectedReasons() throws Exception {
        Path fixturesDir = CorpusPaths.phase4FixturesDir();
        Phase4Fixtures.GuardNumberCases fixture =
                Phase4Fixtures.loadGuardNumberCases(fixturesDir.resolve("guard_number_cases.json"));
        SkillsDictionary skills = SkillsDictionary.load(fixturesDir.resolve("skills_seed.json"));

        StringBuilder failures = new StringBuilder();
        for (Phase4Fixtures.GuardNumberCase c : fixture.cases()) {
            List<String> got = TruthfulnessGuard.guard(c.variant(), c.sourceTexts(), c.budgetChars(), skills);
            if (!got.equals(c.expected())) {
                failures.append(c.name()).append(": expected ").append(c.expected())
                        .append(", got ").append(got).append('\n');
            }
        }

        System.out.println("guard number cases checked: " + fixture.cases().size());
        System.out.println("FAILURES:\n" + (failures.isEmpty() ? "(none)" : failures));
        assertTrue(failures.isEmpty(), "P4-T1 (number cases) failures:\n" + failures);
    }
}
