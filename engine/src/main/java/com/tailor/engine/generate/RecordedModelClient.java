package com.tailor.engine.generate;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Offline {@link ModelClient} for tests (PHASE4_SPEC.md section 9: "all tests except P4-T6 are
 * offline"): replays one section's recorded responses in order (round 1, then each retry),
 * ignoring the actual prompt text — one instance is scoped to one section, matching {@link
 * RecordedResponses#load}'s per-section lists. <b>Never logs prompt or response text</b>, only
 * ids and token counts, same as {@link AnthropicClient}.
 */
public final class RecordedModelClient implements ModelClient {

    private final Deque<ModelResponse> queue;

    public RecordedModelClient(List<ModelResponse> responses) {
        this.queue = new ArrayDeque<>(responses);
    }

    @Override
    public ModelResponse call(String systemPrompt, String userMessage) {
        ModelResponse response = queue.poll();
        if (response == null) {
            throw new IllegalStateException("no more recorded responses for this section");
        }
        System.out.printf(
                "recorded call: candidates=%d input_tokens=%d output_tokens=%d "
                        + "cache_creation_tokens=%d cache_read_tokens=%d%n",
                response.bullets().size(), response.usage().inputTokens(), response.usage().outputTokens(),
                response.usage().cacheCreationInputTokens(), response.usage().cacheReadInputTokens());
        return response;
    }
}
