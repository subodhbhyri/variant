package com.tailor.engine.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Renders by calling the renderer service (PHASE6_SPEC.md section 2.1): {@code POST /render} with
 * the .docx bytes, PDF bytes back. The service keeps persistent LibreOffice processes and has no
 * network egress; this class is the worker's only route to it.
 *
 * Same contract as {@link LibreOfficeRenderer}: the PDF lands in {@code outDir} as
 * {@code <docx name>.pdf}, a failed render is retried once, and a second failure is a hard
 * {@link RenderException} (with the service's error code in the message).
 */
public final class RemoteRenderer implements Renderer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI baseUrl;
    private final HttpClient http;
    private final Duration requestTimeout;
    private volatile String version;

    public RemoteRenderer(URI baseUrl) {
        this(baseUrl, Duration.ofSeconds(75));
    }

    public RemoteRenderer(URI baseUrl, Duration requestTimeout) {
        String s = baseUrl.toString();
        this.baseUrl = URI.create(s.endsWith("/") ? s : s + "/");
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public String version() {
        String v = version;
        if (v == null) {
            v = fetchVersion();
            version = v;
        }
        return v;
    }

    @Override
    public Path render(Path docxPath, Path outDir) throws RenderException {
        if (!Files.isRegularFile(docxPath)) {
            throw new RenderException("not a file: " + docxPath);
        }
        if (!Files.isDirectory(outDir)) {
            throw new RenderException("outDir does not exist: " + outDir);
        }

        RenderException lastFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return renderOnce(docxPath, outDir);
            } catch (NotRetryable e) {
                throw new RenderException(e.getMessage());
            } catch (RenderException e) {
                lastFailure = e;
            }
        }
        throw new RenderException("render failed after 2 attempts for " + docxPath, lastFailure);
    }

    private Path renderOnce(Path docxPath, Path outDir) throws RenderException {
        byte[] docx;
        try {
            docx = Files.readAllBytes(docxPath);
        } catch (IOException e) {
            throw new RenderException("could not read " + docxPath, e);
        }
        HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve("render"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .POST(HttpRequest.BodyPublishers.ofByteArray(docx))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new RenderException("renderer unreachable: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RenderException("interrupted rendering " + docxPath, e);
        }

        if (response.statusCode() != 200) {
            String code = errorCode(response.body());
            String message = "renderer answered " + response.statusCode() + " " + code + " for " + docxPath;
            if (response.statusCode() == 413 || response.statusCode() == 400) {
                throw new NotRetryable(message);
            }
            throw new RenderException(message);
        }
        byte[] pdf = response.body();
        if (pdf.length == 0) {
            throw new RenderException("renderer returned an empty PDF for " + docxPath);
        }
        String name = docxPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path pdfPath = outDir.resolve((dot < 0 ? name : name.substring(0, dot)) + ".pdf");
        try {
            Files.write(pdfPath, pdf);
        } catch (IOException e) {
            throw new RenderException("could not write " + pdfPath, e);
        }
        return pdfPath;
    }

    private String fetchVersion() {
        HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve("version"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("renderer /version answered " + response.statusCode());
            }
            JsonNode node = JSON.readTree(response.body());
            String v = node.path("rendererVersion").asText("");
            if (v.isEmpty()) {
                throw new IllegalStateException("renderer /version has no rendererVersion");
            }
            return v;
        } catch (IOException e) {
            throw new IllegalStateException("renderer unreachable: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted asking the renderer for its version", e);
        }
    }

    private static String errorCode(byte[] body) {
        try {
            return JSON.readTree(body).path("code").asText("UNKNOWN");
        } catch (IOException e) {
            return "UNKNOWN";
        }
    }

    /** A failure the caller caused (oversized or malformed request); a second attempt would fail identically. */
    private static final class NotRetryable extends RenderException {
        NotRetryable(String message) {
            super(message);
        }
    }
}
