package com.tailor.web.match;

import com.tailor.engine.match.Embedder;
import com.tailor.engine.match.FakeEmbedder;
import com.tailor.engine.match.MiniLmEmbedder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The sentence-similarity model (PHASE6_SPEC.md section 5 and PHASE5_SPEC.md section 7).
 * {@code app.match.embedder} is {@code minilm} (production: the model files named by
 * {@code VARIANT_MODEL_DIR}, loaded once per process on first use, SHA-256-pinned by the engine) or
 * {@code fake} (fixtures and tests, exactly as {@code tailor match --embedder fake}).
 */
@Configuration
public class EmbedderConfig {

    @Bean
    Embedder embedder(@Value("${app.match.embedder:minilm}") String kind) {
        return switch (kind) {
            case "fake" -> new FakeEmbedder();
            case "minilm" -> new LazyMiniLm();
            default -> throw new IllegalArgumentException("app.match.embedder must be 'minilm' or 'fake', got '" + kind + "'");
        };
    }

    /** Loads MiniLM the first time a similarity is asked for, so processes that never match start without the model. */
    static final class LazyMiniLm implements Embedder, AutoCloseable {
        private volatile MiniLmEmbedder model;

        private MiniLmEmbedder model() {
            MiniLmEmbedder m = model;
            if (m == null) {
                synchronized (this) {
                    if (model == null) {
                        try {
                            model = MiniLmEmbedder.loadFromEnv();
                        } catch (Exception e) {
                            throw new IllegalStateException("the similarity model could not be loaded", e);
                        }
                    }
                    m = model;
                }
            }
            return m;
        }

        @Override
        public double similarity(String a, String b) {
            return model().similarity(a, b);
        }

        @Override
        public void close() throws Exception {
            if (model != null) {
                model.close();
            }
        }
    }
}
