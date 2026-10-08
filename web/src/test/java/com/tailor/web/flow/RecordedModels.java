package com.tailor.web.flow;

import com.tailor.engine.generate.ModelClient;
import com.tailor.engine.generate.ModelResponse;
import com.tailor.engine.generate.RecordedModelClient;
import com.tailor.engine.generate.RecordedResponses;
import com.tailor.web.generation.ModelClientFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replays Phase 4's recorded model responses (Jane Doe: {@code job-0}, {@code job-1}), exactly as
 * {@code tailor generate --recorded} does: one fresh client per section, and a section with no
 * recording is an error. Tests can make the model fail.
 */
@TestConfiguration
public class RecordedModels {

    public static volatile boolean failing;
    public static final java.util.concurrent.atomic.AtomicInteger CALLS = new java.util.concurrent.atomic.AtomicInteger();

    public static Path recordedFile() {
        String env = System.getenv("TAILOR_PHASE4_FIXTURES_DIR");
        return Path.of(env == null || env.isBlank() ? "/app/fixtures/phase4" : env).resolve("recorded_responses.json");
    }

    @Bean
    @Primary
    ModelClientFactory recordedModelClients() throws IOException {
        Map<String, List<ModelResponse>> recorded = RecordedResponses.load(recordedFile());
        return sectionId -> {
            CALLS.incrementAndGet();
            if (failing) {
                throw new IllegalStateException("the model is unavailable");
            }
            List<ModelResponse> responses = recorded.get(sectionId);
            if (responses == null) {
                throw new IllegalStateException("no recorded responses for section " + sectionId);
            }
            ModelClient client = new RecordedModelClient(responses);
            return client;
        };
    }
}
