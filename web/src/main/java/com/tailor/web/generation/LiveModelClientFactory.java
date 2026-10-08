package com.tailor.web.generation;

import com.tailor.engine.generate.AnthropicClient;
import com.tailor.engine.generate.ModelClient;
import org.springframework.stereotype.Component;

/** The real Anthropic API, keyed by the {@code ANTHROPIC_API_KEY} environment variable (tests register a primary replacement). */
@Component
public class LiveModelClientFactory implements ModelClientFactory {

    @Override
    public ModelClient forSection(String sectionId) {
        return new AnthropicClient();
    }
}
