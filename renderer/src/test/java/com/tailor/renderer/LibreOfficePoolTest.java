package com.tailor.renderer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailor.engine.render.RemoteRenderer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pool's behaviour with real LibreOffice (PHASE6_SPEC.md 2.1 and the process part of P6-T9):
 * processes are recycled, a killed process is replaced and the render still succeeds, bad input is
 * RENDER_FAILED, and no scratch file outlives its request.
 */
@Tag("corpus")
class LibreOfficePoolTest {

    @TempDir
    Path scratch;

    private LibreOfficePool pool;
    private RendererServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close(); // closes the pool too
        } else if (pool != null) {
            pool.close();
        }
    }

    private RendererConfig config(int poolSize, int maxTasks, long timeoutMs) {
        return new RendererConfig(0, poolSize, maxTasks, timeoutMs, 5_000, RendererConfig.MAX_DOCX_BYTES, scratch, null);
    }

    private static Set<Long> sofficePids() {
        return ProcessHandle.current().descendants()
                .filter(p -> p.info().command().map(c -> c.contains("soffice.bin")).orElse(false))
                .map(ProcessHandle::pid)
                .collect(Collectors.toSet());
    }

    private static void killOffice() {
        ProcessHandle.current().descendants()
                .filter(p -> p.info().command().map(c -> c.contains("soffice.bin") || c.contains("oosplash")).orElse(false))
                .forEach(ProcessHandle::destroyForcibly);
    }

    private static byte[] aResume() throws Exception {
        return Files.readAllBytes(Corpus.docx().get(0));
    }

    @Test
    void rendersADocxToAPdfAndReportsTheVersion() throws Exception {
        pool = new LibreOfficePool(config(1, 200, 30_000));

        byte[] pdf = pool.toPdf(aResume());

        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(pool.version()).matches("[0-9]+(\\.[0-9]+)+");
    }

    @Test
    void somethingThatIsNotADocxIsRefusedAndTheServiceKeepsWorking() throws Exception {
        pool = new LibreOfficePool(config(1, 200, 30_000));

        // LibreOffice would turn plain text, RTF or HTML into a PDF; the service must not.
        for (String notDocx : new String[] {"this is not a docx", "{\rtf1 hello}", "<html><body>x</body></html>"}) {
            assertThatThrownBy(() -> pool.toPdf(notDocx.getBytes()))
                    .isInstanceOfSatisfying(RenderFailure.class, f -> {
                        assertThat(f.code()).isEqualTo(RenderFailure.Code.RENDER_FAILED);
                        assertThat(f.callerFault()).isTrue();
                    });
        }

        assertThat(pool.toPdf(aResume())).isNotEmpty();
    }

    @Test
    void noScratchFileOutlivesItsRequest() throws Exception {
        pool = new LibreOfficePool(config(1, 200, 30_000));
        pool.toPdf(aResume());
        try {
            pool.toPdf("PK a zip header and then garbage".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        } catch (RenderFailure expected) {
            // whether LibreOffice rejects or renders it, the failure path must clean up too
        }

        try (var children = Files.list(scratch.resolve("requests"))) {
            assertThat(children.toList()).isEmpty();
        }
    }

    @Test
    void aProcessIsRestartedAfterItsQuotaOfRenders() throws Exception {
        pool = new LibreOfficePool(config(1, 2, 30_000));
        byte[] resume = aResume();

        pool.toPdf(resume);
        Set<Long> first = sofficePids();
        assertThat(first).isNotEmpty();

        pool.toPdf(resume); // the quota (2) is reached here
        long deadline = System.currentTimeMillis() + 60_000;
        Set<Long> now = sofficePids();
        while ((now.isEmpty() || now.equals(first)) && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            now = sofficePids();
        }
        assertThat(now).as("a fresh soffice process replaced the recycled one").isNotEmpty().isNotEqualTo(first);

        assertThat(pool.toPdf(resume)).isNotEmpty();
    }

    @Test
    void aKilledProcessIsReplacedAndTheRenderSucceeds() throws Exception {
        RendererConfig config = config(1, 200, 30_000);
        pool = new LibreOfficePool(config);
        server = new RendererServer(config, pool).start();
        RemoteRenderer remote = new RemoteRenderer(URI.create("http://127.0.0.1:" + server.port()));
        Path in = Files.createDirectory(scratch.resolve("in")).resolve("resume.docx");
        Files.write(in, aResume());
        Path out = Files.createDirectory(scratch.resolve("out"));

        assertThat(remote.render(in, out)).exists();
        Set<Long> before = sofficePids();
        assertThat(before).isNotEmpty();

        killOffice();
        Path again = remote.render(in, Files.createDirectory(scratch.resolve("out2")));

        assertThat(Files.size(again)).isPositive();
        Set<Long> after = sofficePids();
        assertThat(after).isNotEmpty();
        assertThat(after).as("the killed process was replaced by a new one").doesNotContainAnyElementsOf(before);
    }

    @Test
    void aTimeoutIsReportedAsRenderTimeout() throws Exception {
        pool = new LibreOfficePool(config(1, 200, 1), false); // 1 ms: nothing can finish in time (and no startup self-test)

        assertThatThrownBy(() -> pool.toPdf(aResume()))
                .isInstanceOfSatisfying(RenderFailure.class,
                        f -> assertThat(f.code()).isEqualTo(RenderFailure.Code.RENDER_TIMEOUT));
    }

    @Test
    void aPoolStartsCleanlyEveryTimeAndEveryProcessAnswers() throws Exception {
        // Starting several office processes at once used to crash one now and then, leaving a slot that never
        // answered. Start a pool of four, five times over; each time all four must render at the same moment.
        byte[] resume = aResume();
        for (int round = 0; round < 5; round++) {
            LibreOfficePool p = new LibreOfficePool(config(4, 200, 30_000));
            try {
                var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
                var futures = new java.util.ArrayList<java.util.concurrent.Future<byte[]>>();
                for (int i = 0; i < 4; i++) {
                    futures.add(executor.submit(() -> p.toPdf(resume)));
                }
                for (var f : futures) {
                    assertThat(f.get(25, java.util.concurrent.TimeUnit.SECONDS)).isNotEmpty();
                }
                executor.shutdown();
            } finally {
                p.close();
            }
        }
    }

    @Test
    void healthEndpointRendersTheFixtureThroughTheRealPool() throws Exception {
        RendererConfig config = config(1, 200, 30_000);
        pool = new LibreOfficePool(config);
        server = new RendererServer(config, pool).start();

        var response = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(List.of(response.body())).isNotEmpty();
    }
}
