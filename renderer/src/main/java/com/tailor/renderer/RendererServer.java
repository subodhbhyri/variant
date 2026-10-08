package com.tailor.renderer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The renderer's HTTP face (PHASE6_SPEC.md section 2.1):
 * <ul>
 *   <li>{@code POST /render}: .docx bytes (at most 10 MB) in, PDF bytes out, or a JSON error
 *       {@code {"code": "RENDER_TIMEOUT" | "RENDER_FAILED", "message": ...}}.</li>
 *   <li>{@code GET /version}: {@code {"rendererVersion": "..."}}.</li>
 *   <li>{@code GET /health}: renders a one-page fixture; 200 if it finishes in time, else 503.</li>
 * </ul>
 * The service holds no state about documents and logs none of their content.
 */
public final class RendererServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RendererServer.class);

    private final RendererConfig config;
    private final DocxConverter converter;
    private final HttpServer http;
    private final ExecutorService requests;
    private final ExecutorService health = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "renderer-health");
        t.setDaemon(true);
        return t;
    });
    private final byte[] healthDocx = HealthFixture.docx();

    public RendererServer(RendererConfig config, DocxConverter converter) throws IOException {
        this.config = config;
        this.converter = converter;
        this.http = HttpServer.create(new InetSocketAddress(config.port()), 0);
        // One thread per pooled process, plus headroom so /health and /version stay answerable
        // while every process is busy.
        this.requests = Executors.newFixedThreadPool(config.poolSize() + 2);
        http.setExecutor(requests);
        http.createContext("/render", this::render);
        http.createContext("/version", this::version);
        http.createContext("/health", this::health);
    }

    public RendererServer start() {
        http.start();
        return this;
    }

    public int port() {
        return http.getAddress().getPort();
    }

    @Override
    public void close() {
        http.stop(1);
        requests.shutdown();
        health.shutdownNow();
        converter.close();
    }

    private void render(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"POST".equals(ex.getRequestMethod())) {
                json(ex, 405, error("RENDER_FAILED", "POST only"));
                return;
            }
            long declared = parseLength(ex.getRequestHeaders().getFirst("Content-Length"));
            if (declared > config.maxDocxBytes()) {
                json(ex, 413, error("RENDER_FAILED", "document is over the size limit"));
                return;
            }
            byte[] docx = readLimited(ex.getRequestBody(), config.maxDocxBytes());
            if (docx == null) {
                json(ex, 413, error("RENDER_FAILED", "document is over the size limit"));
                return;
            }
            if (docx.length == 0) {
                json(ex, 400, error("RENDER_FAILED", "empty body"));
                return;
            }
            long started = System.nanoTime();
            try {
                byte[] pdf = converter.toPdf(docx);
                log.info("render ok in={}B out={}B ms={}", docx.length, pdf.length, millisSince(started));
                ex.getResponseHeaders().add("Content-Type", "application/pdf");
                ex.getResponseHeaders().add("X-Renderer-Version", converter.version());
                ex.sendResponseHeaders(200, pdf.length);
                ex.getResponseBody().write(pdf);
            } catch (RenderFailure f) {
                log.warn("render failed code={} in={}B ms={}", f.code(), docx.length, millisSince(started));
                json(ex, f.callerFault() ? 400 : f.code().httpStatus(), error(f.code().name(), f.getMessage()));
            }
        }
    }

    private void version(HttpExchange ex) throws IOException {
        try (ex) {
            json(ex, 200, "{\"rendererVersion\":" + quote(converter.version()) + "}");
        }
    }

    private void health(HttpExchange ex) throws IOException {
        try (ex) {
            Future<byte[]> check = health.submit(() -> converter.toPdf(healthDocx));
            try {
                byte[] pdf = check.get(config.healthTimeoutMs(), TimeUnit.MILLISECONDS);
                if (pdf.length > 0) {
                    json(ex, 200, "{\"status\":\"ok\"}");
                    return;
                }
                json(ex, 503, "{\"status\":\"empty\"}");
            } catch (TimeoutException e) {
                check.cancel(true);
                json(ex, 503, "{\"status\":\"slow\"}");
            } catch (Exception e) {
                json(ex, 503, "{\"status\":\"failing\"}");
            }
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static long parseLength(String header) {
        try {
            return header == null ? -1 : Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Reads at most {@code limit} bytes; {@code null} if the stream holds more. */
    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        byte[] data = in.readNBytes(limit + 1);
        return data.length > limit ? null : data;
    }

    private static String error(String code, String message) {
        return "{\"code\":" + quote(code) + ",\"message\":" + quote(message) + "}";
    }

    private static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> out.append(c < 0x20 ? " " : String.valueOf(c));
            }
        }
        return out.append('"').toString();
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
