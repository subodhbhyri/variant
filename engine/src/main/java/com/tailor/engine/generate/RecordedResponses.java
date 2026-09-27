package com.tailor.engine.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads a {@code recorded_responses.json}-shaped file (fixtures/phase4/recorded_responses.json):
 * section id -> its ordered list of recorded Messages API responses (one per call: round 1, then
 * each retry round). Entries are the real wire shape, parsed the same way as a live response, so
 * replay is faithful to what {@link AnthropicClient} would have received.
 */
public final class RecordedResponses {

    private RecordedResponses() {
    }

    public static Map<String, List<ModelResponse>> load(Path json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(Files.readAllBytes(json));
        Map<String, List<ModelResponse>> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            List<ModelResponse> calls = new ArrayList<>();
            for (JsonNode call : e.getValue()) {
                calls.add(AnthropicResponse.parse(call));
            }
            out.put(e.getKey(), calls);
        }
        return out;
    }
}
