package com.tailor.engine.generate;

/**
 * PHASE4_SPEC.md section 3: one {@code submit_bullets} call — the system prompt (identical every
 * time, so it cache-hits) and one user message. A retry round is just another call with the same
 * system prompt and a new (retry) user message; no conversation history is threaded between calls.
 */
public interface ModelClient {
    ModelResponse call(String systemPrompt, String userMessage) throws Exception;
}
