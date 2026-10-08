package com.tailor.engine.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** RemoteRenderer against a stub service: protocol, error codes and the retry-once contract. */
class RemoteRendererTest {

    private static final byte[] PDF = "%PDF-1.7 stub".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private final AtomicInteger renders = new AtomicInteger();
    private final List<Integer> scriptedStatuses = new ArrayList<>();
    private final List<String> scriptedCodes = new ArrayList<>();
    private volatile int receivedBytes;

    @TempDir
    Path dir;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/render", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            receivedBytes = body.length;
            int n = renders.getAndIncrement();
            int status = n < scriptedStatuses.size() ? scriptedStatuses.get(n) : 200;
            if (status == 200) {
                ex.getResponseHeaders().add("Content-Type", "application/pdf");
                ex.sendResponseHeaders(200, PDF.length);
                ex.getResponseBody().write(PDF);
            } else {
                byte[] err = ("{\"code\":\"" + scriptedCodes.get(n) + "\",\"message\":\"m\"}")
                        .getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, err.length);
                ex.getResponseBody().write(err);
            }
            ex.close();
        });
        server.createContext("/version", ex -> {
            byte[] v = "{\"rendererVersion\":\"24.2.7.2\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, v.length);
            ex.getResponseBody().write(v);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private RemoteRenderer renderer() {
        return new RemoteRenderer(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    private Path docx() throws Exception {
        Path p = dir.resolve("resume.docx");
        Files.write(p, new byte[] {1, 2, 3, 4});
        return p;
    }

    private void script(int status, String code) {
        scriptedStatuses.add(status);
        scriptedCodes.add(code);
    }

    private static void assertMessageContains(Exception e, String part) {
        assertTrue(e.getMessage().contains(part), () -> "expected '" + part + "' in: " + e.getMessage());
    }

    @Test
    void sendsTheDocxAndWritesThePdfBesideItsName() throws Exception {
        Path out = Files.createDirectory(dir.resolve("out"));
        Path pdf = renderer().render(docx(), out);

        assertEquals(out.resolve("resume.pdf"), pdf);
        assertArrayEquals(PDF, Files.readAllBytes(pdf));
        assertEquals(4, receivedBytes);
        assertEquals(1, renders.get());
    }

    @Test
    void reportsTheServicesLibreOfficeVersion() {
        assertEquals("24.2.7.2", renderer().version());
    }

    @Test
    void aFailedRenderIsRetriedOnce() throws Exception {
        script(500, "RENDER_FAILED");
        Path out = Files.createDirectory(dir.resolve("out"));

        assertTrue(Files.exists(renderer().render(docx(), out)));
        assertEquals(2, renders.get());
    }

    @Test
    void aSecondFailureIsHardAndNamesTheServiceCode() throws Exception {
        script(504, "RENDER_TIMEOUT");
        script(504, "RENDER_TIMEOUT");
        Path out = Files.createDirectory(dir.resolve("out"));

        RenderException e = assertThrows(RenderException.class, () -> renderer().render(docx(), out));
        assertMessageContains(e, "after 2 attempts");
        assertMessageContains((Exception) e.getCause(), "504 RENDER_TIMEOUT");
        assertEquals(2, renders.get());
    }

    @Test
    void anOversizedRequestIsNotRetried() throws Exception {
        script(413, "RENDER_FAILED");
        Path out = Files.createDirectory(dir.resolve("out"));

        RenderException e = assertThrows(RenderException.class, () -> renderer().render(docx(), out));
        assertMessageContains(e, "413");
        assertEquals(1, renders.get());
    }

    @Test
    void anUnreachableServiceFailsAfterTwoAttempts() throws Exception {
        int port = server.getAddress().getPort();
        server.stop(0);
        Path out = Files.createDirectory(dir.resolve("out"));

        RenderException e = assertThrows(RenderException.class,
                () -> new RemoteRenderer(URI.create("http://127.0.0.1:" + port)).render(docx(), out));
        assertMessageContains(e, "after 2 attempts");
    }

    @Test
    void rejectsMissingInputsLikeTheLocalRenderer() {
        assertMessageContains(assertThrows(RenderException.class,
                () -> renderer().render(dir.resolve("nope.docx"), dir)), "not a file");
        assertMessageContains(assertThrows(RenderException.class,
                () -> renderer().render(docx(), dir.resolve("nodir"))), "outDir does not exist");
    }
}
