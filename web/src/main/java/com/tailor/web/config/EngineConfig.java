package com.tailor.web.config;

import com.tailor.engine.render.RemoteRenderer;
import com.tailor.engine.render.Renderer;
import com.tailor.web.storage.StorageProperties;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The engine's seams: the renderer is the renderer service (PHASE6_SPEC.md section 2), reached over HTTP. */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class EngineConfig {

    /** Present when {@code app.renderer.url} is set (the worker needs it; the api never renders). */
    @Bean
    @ConditionalOnExpression("!'${app.renderer.url:}'.isEmpty()")
    Renderer renderer(@Value("${app.renderer.url}") String url) {
        return new RemoteRenderer(URI.create(url));
    }
}
