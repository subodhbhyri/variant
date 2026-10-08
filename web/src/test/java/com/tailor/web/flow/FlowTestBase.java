package com.tailor.web.flow;

import com.tailor.engine.render.RenderException;
import com.tailor.engine.render.RemoteRenderer;
import com.tailor.engine.render.Renderer;
import com.tailor.renderer.LibreOfficePool;
import com.tailor.renderer.RendererConfig;
import com.tailor.renderer.RendererServer;
import com.tailor.web.auth.AbstractApiTest;
import com.tailor.web.jobs.JobProcessor;
import com.tailor.web.storage.FileStorage;
import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The api with the real engine handlers, real file storage (MinIO) and a renderer that starts only
 * when something first renders (so tests that never render cost no LibreOffice). Jobs are run by
 * hand with {@link #runJobs()}: nothing polls in an api context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(FlowTestBase.LazyRendererConfig.class)
public abstract class FlowTestBase extends AbstractApiTest {

    protected static final int FLOW_PORT = freePort();
    protected static final String FLOW_BASE = "http://localhost:" + FLOW_PORT;
    protected static final String BUCKET = "tailor-test";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        commonProperties(registry, FLOW_PORT);
        String endpoint = System.getenv("TEST_S3_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException("TEST_S3_ENDPOINT is not set. Run the web tests with scripts/web-test.ps1.");
        }
        // The SDK's default credential chain reads these.
        System.setProperty("aws.accessKeyId", "tailor");
        System.setProperty("aws.secretAccessKey", "tailor-secret");
        registry.add("app.storage.endpoint", () -> endpoint);
        registry.add("app.storage.path-style", () -> "true");
        registry.add("app.storage.create-bucket", () -> "true");
        registry.add("app.storage.bucket", () -> BUCKET);
        registry.add("app.jobs.run-handlers", () -> "true");
    }

    @Autowired
    protected FileStorage storage;
    @Autowired
    protected JobProcessor processor;

    @Override
    protected String baseUrl() {
        return FLOW_BASE;
    }

    @BeforeEach
    void cleanJobs() {
        jdbc.update("DELETE FROM jobs");
    }

    /** Runs queued jobs on this thread until none is left (what a worker would do). */
    protected int runJobs() {
        int n = 0;
        while (processor.runNext()) {
            n++;
        }
        return n;
    }

    /** A Renderer that starts the real renderer service (LibreOffice) on first use and keeps it for the JVM. */
    @TestConfiguration
    static class LazyRendererConfig {
        private static volatile Renderer started;

        private static synchronized Renderer start() {
            if (started == null) {
                RendererConfig config = new RendererConfig(0, 2, 200, 30_000, 5_000, RendererConfig.MAX_DOCX_BYTES,
                        Path.of(System.getProperty("java.io.tmpdir")), null);
                try {
                    RendererServer server = new RendererServer(config, new LibreOfficePool(config)).start();
                    Runtime.getRuntime().addShutdownHook(new Thread(server::close));
                    started = new RemoteRenderer(URI.create("http://127.0.0.1:" + server.port()));
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return started;
        }

        @Bean
        @Primary
        Renderer lazyRenderer() {
            return new Renderer() {
                @Override
                public Path render(Path docxPath, Path outDir) throws RenderException {
                    return start().render(docxPath, outDir);
                }

                @Override
                public String version() {
                    return start().version();
                }
            };
        }
    }

    protected static UUID uuid(String s) {
        return UUID.fromString(s);
    }
}
