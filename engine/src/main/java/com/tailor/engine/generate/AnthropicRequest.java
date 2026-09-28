package com.tailor.engine.generate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * PHASE4_SPEC.md section 3: builds the Messages API request body — {@code claude-sonnet-5},
 * {@code max_tokens} 2000, the system prompt cached with {@code cache_control}, and forced use
 * of the {@code submit_bullets} tool. Kept separate from {@link AnthropicClient}'s HTTP plumbing
 * so the request shape itself (P4-T4) is directly testable without a network call.
 */
final class AnthropicRequest {

    static final String MODEL = "claude-sonnet-5";
    static final int MAX_TOKENS = 2000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnthropicRequest() {
    }

    static ObjectNode build(String systemPrompt, String userMessage) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", MODEL);
        root.put("max_tokens", MAX_TOKENS);

        ObjectNode systemBlock = MAPPER.createObjectNode();
        systemBlock.put("type", "text");
        systemBlock.put("text", systemPrompt);
        systemBlock.putObject("cache_control").put("type", "ephemeral");
        root.putArray("system").add(systemBlock);

        ObjectNode userBlock = MAPPER.createObjectNode();
        userBlock.put("role", "user");
        userBlock.put("content", userMessage);
        root.putArray("messages").add(userBlock);

        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", PromptBuilder.TOOL_NAME);
        tool.put("description", "Submit the generated bullet candidates.");
        // PHASE4_SPEC.md section 3 (revision 6): strict tool use, so the API enforces the schema
        // instead of it being only a hint — P4-T11 (revision 5) got bullets: [] in 2 of 10 runs
        // despite minItems: 1, with stop_reason: tool_use and no text.
        tool.put("strict", true);
        try {
            tool.set("input_schema", MAPPER.readTree(PromptBuilder.TOOL_SCHEMA_JSON));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("PromptBuilder.TOOL_SCHEMA_JSON does not parse", e);
        }
        root.putArray("tools").add(tool);

        ObjectNode toolChoice = MAPPER.createObjectNode();
        toolChoice.put("type", "tool");
        toolChoice.put("name", PromptBuilder.TOOL_NAME);
        root.set("tool_choice", toolChoice);

        return root;
    }
}
