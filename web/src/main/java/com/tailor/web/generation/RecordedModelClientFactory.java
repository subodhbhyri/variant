package com.tailor.web.generation;

import com.tailor.engine.generate.ModelClient;
import com.tailor.engine.generate.ModelResponse;
import com.tailor.engine.generate.RecordedModelClient;
import com.tailor.engine.generate.RecordedResponses;
import com.tailor.web.config.AppProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Local smoke runs only: replays a {@code recorded_responses.json} exactly as {@code tailor generate --recorded}
 * does (one fresh client per section; a section with no recording is an error), so the whole flow can be run
 * without an Anthropic key or any cost. Refuses to start when {@code app.env} is {@code prod}.
 */
@Component
@Primary
@ConditionalOnExpression("!'${app.generation.recorded-file:}'.isEmpty()")
public class RecordedModelClientFactory implements ModelClientFactory {

    private final Map<String, List<ModelResponse>> recorded;

    public RecordedModelClientFactory(@Value("${app.generation.recorded-file}") String file, AppProperties props) throws IOException {
        if ("prod".equalsIgnoreCase(props.env())) {
            throw new IllegalStateException("app.generation.recorded-file replays canned model answers and is not allowed in prod");
        }
        this.recorded = RecordedResponses.load(Path.of(file));
    }

    @Override
    public ModelClient forSection(String sectionId) {
        List<ModelResponse> responses = recorded.get(sectionId);
        if (responses == null) {
            throw new IllegalStateException("no recorded responses for section " + sectionId);
        }
        return new RecordedModelClient(responses);
    }
}
