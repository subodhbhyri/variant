package com.tailor.engine.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.CorpusPaths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * P4-T5 (PHASE4_SPEC.md section 9): recorded usage -> $0.01544; a fake run crossing $0.50 stops
 * with COST_LIMIT (here: {@link BudgetTracker#canGenerate()} flips to false). Pure, no network,
 * no render.
 */
class CostAccountingTest {

    @Test
    void recordedJobZeroUsageCosts001544Dollars() throws Exception {
        PriceTable prices = PriceTable.loadDefault();
        Map<String, List<ModelResponse>> recorded =
                RecordedResponses.load(CorpusPaths.phase4FixturesDir().resolve("recorded_responses.json"));

        BudgetTracker tracker = new BudgetTracker(prices);
        for (ModelResponse response : recorded.get("job-0")) {
            tracker.record(response.usage());
        }

        assertEquals(3, tracker.callCount());
        assertEquals(0.01544, tracker.totalUsd(), 1e-9);
    }

    @Test
    void budgetCapStopsFurtherGenerationOnceReached() {
        PriceTable prices = PriceTable.loadDefault();
        BudgetTracker tracker = new BudgetTracker(prices);
        assertTrue(tracker.canGenerate(), "nothing spent yet");

        // Each call costs roughly $0.1 (100k input tokens * $2/mtok) -- five of them cross $0.50.
        ModelResponse.Usage bigCall = new ModelResponse.Usage(50_000, 0, 0, 0);
        for (int i = 0; i < 5 && tracker.canGenerate(); i++) {
            tracker.record(bigCall);
        }

        assertFalse(tracker.canGenerate(), "budget cap must stop further generation once reached");
        assertTrue(tracker.totalUsd() >= prices.budgetCapUsd(), "total must have reached the cap");
    }

    @Test
    void priceTableMatchesTheSpecsPerMillionTokenPrices() {
        PriceTable prices = PriceTable.loadDefault();
        assertEquals("claude-sonnet-5", prices.model());
        assertEquals(2.0, prices.inputPerMtokUsd());
        assertEquals(10.0, prices.outputPerMtokUsd());
        assertEquals(2.5, prices.cacheWrite5mPerMtokUsd());
        assertEquals(0.2, prices.cacheReadPerMtokUsd());
        assertEquals(0.5, prices.budgetCapUsd());
    }
}
