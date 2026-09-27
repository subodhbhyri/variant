package com.tailor.engine.generate;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a Messages API response body (real or, in {@code fixtures/phase4/recorded_responses.json},
 * recorded) into a {@link ModelResponse}. Shared by {@link AnthropicClient} and the offline
 * recorded-response client so replay is faithful to the real wire shape.
 */
final class AnthropicResponse {

    private AnthropicResponse() {
    }

    static ModelResponse parse(JsonNode root) {
        List<ModelResponse.BulletCandidate> bullets = List.of();
        for (JsonNode block : root.path("content")) {
            if ("tool_use".equals(block.path("type").asText())
                    && PromptBuilder.TOOL_NAME.equals(block.path("name").asText())) {
                bullets = parseBullets(block.path("input"));
                break;
            }
        }
        JsonNode u = root.path("usage");
        ModelResponse.Usage usage = new ModelResponse.Usage(
                u.path("input_tokens").asInt(0),
                u.path("output_tokens").asInt(0),
                u.path("cache_creation_input_tokens").asInt(0),
                u.path("cache_read_input_tokens").asInt(0));
        return new ModelResponse(bullets, usage);
    }

    private static List<ModelResponse.BulletCandidate> parseBullets(JsonNode input) {
        List<ModelResponse.BulletCandidate> out = new ArrayList<>();
        for (JsonNode b : input.path("bullets")) {
            String id = b.path("id").asText();
            Map<String, String> variants = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = b.path("variants").fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                variants.put(e.getKey(), e.getValue().asText());
            }
            out.add(new ModelResponse.BulletCandidate(id, variants));
        }
        return out;
    }
}
