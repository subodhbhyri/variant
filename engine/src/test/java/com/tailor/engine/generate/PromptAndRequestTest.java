package com.tailor.engine.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * P4-T4 (PHASE4_SPEC.md section 9): the request has the system prompt verbatim with
 * cache_control, forced submit_bullets, raw text only inside candidate_material, no raw text for
 * EXISTING_ONLY; logs contain no prompt or response text. Pure, no network.
 */
class PromptAndRequestTest {

    /** Guards against a transcription slip: re-extracts the fenced system-prompt block straight
     * out of PHASE4_SPEC.md section 3 and compares it to {@link PromptBuilder#SYSTEM_PROMPT}. */
    @Test
    void systemPromptMatchesTheSpecFileVerbatim() throws Exception {
        Path specFile = findSpecFile();
        String spec = Files.readString(specFile, StandardCharsets.UTF_8);
        int afterHeading = spec.indexOf("**System prompt**");
        assertTrue(afterHeading >= 0, "PHASE4_SPEC.md section 3 heading not found");
        int fenceStart = spec.indexOf("```text", afterHeading);
        assertTrue(fenceStart >= 0, "system prompt fence not found");
        int contentStart = spec.indexOf('\n', fenceStart) + 1;
        int fenceEnd = spec.indexOf("\n```", contentStart);
        assertTrue(fenceEnd >= 0, "system prompt closing fence not found");
        String extracted = spec.substring(contentStart, fenceEnd);
        assertEquals(extracted, PromptBuilder.SYSTEM_PROMPT);
    }

    private static Path findSpecFile() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("PHASE4_SPEC.md");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("PHASE4_SPEC.md not found walking up from " + Path.of("").toAbsolutePath());
    }

    @Test
    void systemPromptIsVerbatimAndCached() {
        JsonNode req = AnthropicRequest.build(PromptBuilder.SYSTEM_PROMPT, "<section/>");
        assertEquals("claude-sonnet-5", req.path("model").asText());
        assertEquals(2000, req.path("max_tokens").asInt());
        JsonNode system = req.path("system").get(0);
        assertEquals(PromptBuilder.SYSTEM_PROMPT, system.path("text").asText());
        assertEquals("ephemeral", system.path("cache_control").path("type").asText());
    }

    @Test
    void toolUseIsForcedToSubmitBullets() {
        JsonNode req = AnthropicRequest.build(PromptBuilder.SYSTEM_PROMPT, "x");
        assertEquals("tool", req.path("tool_choice").path("type").asText());
        assertEquals("submit_bullets", req.path("tool_choice").path("name").asText());
        assertEquals("submit_bullets", req.path("tools").get(0).path("name").asText());
        JsonNode variantsSchema = req.path("tools").get(0).path("input_schema")
                .path("properties").path("bullets").path("items").path("properties").path("variants");
        assertEquals("array", req.path("tools").get(0).path("input_schema")
                .path("properties").path("bullets").path("type").asText());
        assertEquals(1, variantsSchema.path("minProperties").asInt());
        assertFalse(variantsSchema.path("additionalProperties").asBoolean(true),
                "revision 2: variant keys must be named, no others");
        assertTrue(variantsSchema.path("properties").has("1"));
        assertTrue(variantsSchema.path("properties").has("2"));
        assertTrue(variantsSchema.path("properties").has("3"));
    }

    @Test
    void detailedAsksForUpToNCandidatesExistingOnlyAsksToRewriteEachBullet() {
        String detailed = PromptBuilder.userMessage("job", "DETAILED",
                List.of(new PromptBuilder.FieldLine("title", "Engineer")),
                List.of("Existing bullet"), "raw",
                List.of(new PromptBuilder.LengthSpec(2, 150, 188)), 6);
        assertTrue(detailed.contains("Write up to 6 candidates."));

        String existingOnly = PromptBuilder.userMessage("job", "EXISTING_ONLY",
                List.of(new PromptBuilder.FieldLine("title", "Engineer")),
                List.of("b0 text", "b1 text", "b2 text"), null,
                List.of(new PromptBuilder.LengthSpec(1, 72, 90)), 3);
        assertTrue(existingOnly.contains("Rewrite each current bullet as one candidate, ids b0, b1"));
        assertTrue(existingOnly.contains("keeping exactly its facts."));
        assertFalse(existingOnly.contains("Write up to"));
    }

    @Test
    void rawTextAppearsOnlyInsideCandidateMaterialForDetailedMode() {
        String rawText = "SECRET_RAW_TEXT_MARKER";
        String msg = PromptBuilder.userMessage("job", "DETAILED",
                List.of(new PromptBuilder.FieldLine("title", "Engineer")),
                List.of("Existing bullet"), rawText,
                List.of(new PromptBuilder.LengthSpec(2, 150, 188)), 4);
        int start = msg.indexOf("<candidate_material>");
        int end = msg.indexOf("</candidate_material>");
        assertTrue(start >= 0 && end > start, "candidate_material block must be present");
        int rawTextIndex = msg.indexOf(rawText);
        assertTrue(rawTextIndex > start && rawTextIndex < end, "raw text must sit inside candidate_material");
    }

    @Test
    void existingOnlyModeOmitsRawTextEntirely() {
        String msg = PromptBuilder.userMessage("job", "EXISTING_ONLY",
                List.of(new PromptBuilder.FieldLine("title", "Engineer")),
                List.of("Existing bullet"), null,
                List.of(new PromptBuilder.LengthSpec(1, 72, 90)), 4);
        assertFalse(msg.contains("<candidate_material>"), "EXISTING_ONLY must omit candidate_material entirely");
    }

    @Test
    void logsContainNoPromptOrResponseText() throws Exception {
        String secretPrompt = "SECRET_PROMPT_TEXT_" + System.nanoTime();
        ModelResponse recorded = new ModelResponse(
                List.of(new ModelResponse.BulletCandidate("c1", Map.of("1", "SECRET_RESPONSE_TEXT_MARKER"))),
                new ModelResponse.Usage(100, 50, 0, 0));
        RecordedModelClient client = new RecordedModelClient(List.of(recorded));

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            client.call(PromptBuilder.SYSTEM_PROMPT, secretPrompt);
        } finally {
            System.setOut(originalOut);
        }
        String logged = captured.toString(StandardCharsets.UTF_8);
        assertFalse(logged.contains(secretPrompt), "log must not contain the user prompt text");
        assertFalse(logged.contains("SECRET_RESPONSE_TEXT_MARKER"), "log must not contain response text");
        assertFalse(logged.contains(PromptBuilder.SYSTEM_PROMPT), "log must not contain the system prompt");
    }
}
