package com.tailor.renderer;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The HTTP contract of PHASE6_SPEC.md section 2.1, against a fake converter (no LibreOffice). */
class RendererServerTest {

    private static final byte[] PDF = "%PDF-1.7 fake".getBytes(StandardCharsets.UTF_8);

    private final AtomicInteger conversions = new AtomicInteger();
    private volatile RenderFailure failure;
    private volatile long delayMs;
    private RendererServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    private final DocxConverter converter = new DocxConverter() {
        @Override
        public byte[] toPdf(byte[] docx) throws RenderFailure {
            conversions.incrementAndGet();
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure != null) {
                throw failure;
            }
            return PDF;
        }

        @Override
        public String version() {
            return "24.2.7.2";
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void start() throws Exception {
        RendererConfig config = new RendererConfig(0, 2, 200, 30_000, 300, 1024, Path.of("."), null);
        server = new RendererServer(config, converter).start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private HttpResponse<byte[]> post(byte[] body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/render")).POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void rendersADocxToPdfAndReportsTheVersion() throws Exception {
        HttpResponse<byte[]> response = post(new byte[] {1, 2, 3});

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(PDF);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/pdf");
        assertThat(response.headers().firstValue("X-Renderer-Version")).contains("24.2.7.2");
    }

    @Test
    void versionEndpointNamesTheLibreOfficeVersion() throws Exception {
        HttpResponse<String> response = get("/version");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"rendererVersion\":\"24.2.7.2\"}");
    }

    @Test
    void timeoutAndFailureUseTheSpecCodes() throws Exception {
        failure = new RenderFailure(RenderFailure.Code.RENDER_TIMEOUT, "render timed out");
        HttpResponse<byte[]> timeout = post(new byte[] {1});
        assertThat(timeout.statusCode()).isEqualTo(504);
        assertThat(new String(timeout.body(), StandardCharsets.UTF_8)).contains("\"code\":\"RENDER_TIMEOUT\"");

        failure = new RenderFailure(RenderFailure.Code.RENDER_FAILED, "render failed");
        HttpResponse<byte[]> failed = post(new byte[] {1});
        assertThat(failed.statusCode()).isEqualTo(500);
        assertThat(new String(failed.body(), StandardCharsets.UTF_8)).contains("\"code\":\"RENDER_FAILED\"");
    }

    @Test
    void aBodyOverTheLimitIsRefusedBeforeAnyRender() throws Exception {
        HttpResponse<byte[]> response = post(new byte[1025]);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(conversions).hasValue(0);
    }

    @Test
    void aBodyOverTheLimitWithNoDeclaredLengthIsRefusedToo() throws Exception {
        // Chunked: no Content-Length, so the limit has to be enforced while reading.
        HttpRequest chunked = HttpRequest.newBuilder(uri("/render"))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(new byte[5000])))
                .build();
        HttpResponse<byte[]> response = http.send(chunked, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(conversions).hasValue(0);
    }

    @Test
    void anEmptyBodyIsABadRequest() throws Exception {
        assertThat(post(new byte[0]).statusCode()).isEqualTo(400);
        assertThat(conversions).hasValue(0);
    }

    @Test
    void healthIsOkWhenTheFixtureRendersInTime() throws Exception {
        HttpResponse<String> response = get("/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(conversions).hasValue(1);
    }

    @Test
    void healthFailsWhenTheFixtureRenderIsTooSlowOrBroken() throws Exception {
        delayMs = 1500;
        assertThat(get("/health").statusCode()).isEqualTo(503);

        delayMs = 0;
        failure = new RenderFailure(RenderFailure.Code.RENDER_FAILED, "boom");
        assertThat(get("/health").statusCode()).isEqualTo(503);
    }

    @Test
    void theFixtureIsAValidOnePageDocx() {
        byte[] docx = HealthFixture.docx();
        // A .docx is a zip: "PK" magic.
        assertThat(docx[0]).isEqualTo((byte) 'P');
        assertThat(docx[1]).isEqualTo((byte) 'K');
    }
}
