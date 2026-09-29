package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.golden.Phase5Fixtures;
import com.tailor.engine.golden.Phase5Fixtures.AliasRuleCase;
import com.tailor.engine.golden.Phase5Fixtures.AliasRulesExpected;
import org.junit.jupiter.api.Test;

/**
 * P5-T11 (PHASE5_SPEC.md section 10, section 7.1 revision 2): {@code alias_rules} gives exactly
 * {@code fixtures/phase5/alias_rules_expected.json} — 7 of 10 aliases matched, 0 of 12 hard
 * negatives.
 */
class AliasRulesTest {

    @Test
    void aliasRulesMatchExpectedForEveryFixturePair() throws Exception {
        AliasRulesExpected expected = Phase5Fixtures.loadAliasRulesExpected(
                CorpusPaths.phase5FixturesDir().resolve("alias_rules_expected.json"));

        for (AliasRuleCase c : expected.aliases()) {
            assertEquals(c.rules(), AliasRules.rules(c.a(), c.b()), c.a() + " ~ " + c.b());
        }
        for (AliasRuleCase c : expected.hardNegatives()) {
            assertEquals(c.rules(), AliasRules.rules(c.a(), c.b()), c.a() + " ~ " + c.b());
        }
    }

    @Test
    void sectionTwelveGuard_noHardNegativeEverMatchesAnyRule() throws Exception {
        AliasRulesExpected expected = Phase5Fixtures.loadAliasRulesExpected(
                CorpusPaths.phase5FixturesDir().resolve("alias_rules_expected.json"));
        for (AliasRuleCase c : expected.hardNegatives()) {
            var rules = AliasRules.rules(c.a(), c.b());
            org.junit.jupiter.api.Assertions.assertTrue(rules.isEmpty(),
                    "PHASE5_SPEC.md section 12 escalation: " + c.a() + " ~ " + c.b() + " matched " + rules);
        }
    }
}
