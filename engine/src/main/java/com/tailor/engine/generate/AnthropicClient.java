package com.tailor.engine.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * PHASE4_SPEC.md section 3: the live Messages API client. Key from the {@code ANTHROPIC_API_KEY}
 * environment variable only; 60s timeout; retries 429/5xx/timeouts up to 3 times with exponential
 * backoff. <b>Never logs prompt or response text</b> (the user's resume) — only ids, token counts
 * and outcomes.
 */
public final class AnthropicClient implements ModelClient {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_RETRIES = 3;

    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnthropicClient() {
        this.apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("ANTHROPIC_API_KEY is not set");
        }
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public ModelResponse call(String systemPrompt, String userMessage) throws Exception {
        String body = mapper.writeValueAsString(AnthropicRequest.build(systemPrompt, userMessage));
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL))
                .timeout(TIMEOUT)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        Exception lastError = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 429 || status >= 500) {
                    lastError = new IOException("Anthropic API returned HTTP " + status);
                    logOutcome("retry", status, attempt);
                    backoff(attempt);
                    continue;
                }
                if (status >= 400) {
                    logOutcome("error", status, attempt);
                    throw new IOException("Anthropic API returned HTTP " + status);
                }
                JsonNode root = mapper.readTree(response.body());
                ModelResponse parsed = AnthropicResponse.parse(root);
                logCall(status, attempt, parsed);
                return parsed;
            } catch (HttpTimeoutException e) {
                lastError = e;
                logOutcome("timeout", null, attempt);
                backoff(attempt);
            }
        }
        throw new IOException("Anthropic API call failed after " + (MAX_RETRIES + 1) + " attempts", lastError);
    }

    private static void backoff(int attempt) throws InterruptedException {
        long millis = 1000L << attempt; // 1s, 2s, 4s, 8s
        TimeUnit.MILLISECONDS.sleep(millis);
    }

    private static void logCall(int status, int attempt, ModelResponse response) {
        System.out.printf(
                "anthropic call: status=%d attempt=%d candidates=%d input_tokens=%d output_tokens=%d "
                        + "cache_creation_tokens=%d cache_read_tokens=%d%n",
                status, attempt, response.bullets().size(), response.usage().inputTokens(),
                response.usage().outputTokens(), response.usage().cacheCreationInputTokens(),
                response.usage().cacheReadInputTokens());
    }

    private static void logOutcome(String outcome, Integer status, int attempt) {
        System.out.printf("anthropic call: outcome=%s status=%s attempt=%d%n", outcome, status, attempt);
    }
}
