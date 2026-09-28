package com.tailor.engine.generate;

import java.util.ArrayList;
import java.util.List;

/**
 * PHASE4_SPEC.md section 7: accumulates cost across an onboarding's sections and enforces the
 * budget cap — once the running total reaches it, the remaining sections are never sent to the
 * model at all (they stay locked, as if {@code SKIPPED}, and are reported {@code COST_LIMIT}).
 */
public final class BudgetTracker {

    private final PriceTable prices;
    private final List<ModelResponse.Usage> usages = new ArrayList<>();
    private double totalUsd = 0.0;

    public BudgetTracker(PriceTable prices) {
        this.prices = prices;
    }

    /** Whether a section may still be sent to the model: false once the cap has been reached. */
    public boolean canGenerate() {
        return totalUsd < prices.budgetCapUsd();
    }

    public void record(ModelResponse.Usage usage) {
        usages.add(usage);
        totalUsd += prices.costUsd(usage);
    }

    public void recordAll(List<ModelResponse.Usage> callUsages) {
        for (ModelResponse.Usage u : callUsages) {
            record(u);
        }
    }

    public double totalUsd() {
        return totalUsd;
    }

    public int callCount() {
        return usages.size();
    }

    public PriceTable prices() {
        return prices;
    }
}
