package com.tailor.engine.generate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * PHASE4_SPEC.md section 7: Sonnet 5's per-million-token prices and the per-onboarding budget
 * cap — a price table in config (generate/pricing.json on the classpath), not code.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PriceTable(
        @JsonProperty("model") String model,
        @JsonProperty("input_per_mtok_usd") double inputPerMtokUsd,
        @JsonProperty("output_per_mtok_usd") double outputPerMtokUsd,
        @JsonProperty("cache_write_5m_per_mtok_usd") double cacheWrite5mPerMtokUsd,
        @JsonProperty("cache_read_per_mtok_usd") double cacheReadPerMtokUsd,
        @JsonProperty("budget_cap_usd") double budgetCapUsd) {

    public static PriceTable loadDefault() {
        try (InputStream in = PriceTable.class.getResourceAsStream("/generate/pricing.json")) {
            if (in == null) {
                throw new IllegalStateException("generate/pricing.json not on classpath");
            }
            return new ObjectMapper().readValue(in, PriceTable.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One call's cost: each of the four usage fields at its own per-million-token price. */
    public double costUsd(ModelResponse.Usage usage) {
        return (usage.inputTokens() * inputPerMtokUsd
                + usage.outputTokens() * outputPerMtokUsd
                + usage.cacheCreationInputTokens() * cacheWrite5mPerMtokUsd
                + usage.cacheReadInputTokens() * cacheReadPerMtokUsd) / 1_000_000.0;
    }
}
