package com.tailor.renderer;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.measure.PdfPageCounter;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.RemoteRenderer;
import com.tailor.engine.render.Renderer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * P6-T9 (the identity part): {@code RemoteRenderer} (persistent pool behind the service) and the
 * local {@code LibreOfficeRenderer} (a fresh process per render) give identical layout results on
 * all 9 corpus resumes. Compared two ways: the rendered PDFs' lines (page, y, text) and page
 * counts, and the full onboarding report (shrink, hints, slots), which exercises hundreds of
 * calibration renders per resume.
 */
@Tag("corpus")
class RendererIdentityTest {

    private static RendererServer server;
    private static Renderer remote;
    private static Renderer local;

    @BeforeAll
    static void start() {
        RendererConfig config = new RendererConfig(0, 2, 200, 30_000, 5_000, RendererConfig.MAX_DOCX_BYTES,
                Path.of(System.getProperty("java.io.tmpdir")), null);
        try {
            server = new RendererServer(config, new LibreOfficePool(config)).start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        remote = new RemoteRenderer(URI.create("http://127.0.0.1:" + server.port()));
        local = new LibreOfficeRenderer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void sameLibreOfficeVersion() {
        assertThat(remote.version()).isEqualTo(local.version());
    }

    @Test
    void renderedLayoutIsIdenticalOnAllNineCorpusResumes() throws Exception {
        List<Path> corpus = Corpus.docx();
        assertThat(corpus).hasSize(9);
        for (Path docx : corpus) {
            Path localOut = Files.createTempDirectory("identity-local");
            Path remoteOut = Files.createTempDirectory("identity-remote");

            Path localPdf = local.render(docx, localOut);
            Path remotePdf = remote.render(docx, remoteOut);

            String name = docx.getFileName().toString();
            assertThat(PdfPageCounter.count(remotePdf)).as(name + " page count")
                    .isEqualTo(PdfPageCounter.count(localPdf));
            assertThat(PdfLines.extract(remotePdf)).as(name + " lines").isEqualTo(PdfLines.extract(localPdf));
        }
    }

    @Test
    void onboardingGivesTheSameReportOnAllNineCorpusResumes() throws Exception {
        FontMap fontMap = FontMap.loadDefault();
        OnboardPipeline viaLocal = new OnboardPipeline(local, fontMap);
        OnboardPipeline viaRemote = new OnboardPipeline(remote, fontMap);
        for (Path docx : Corpus.docx()) {
            byte[] upload = Files.readAllBytes(docx);
            Path localOut = Files.createTempDirectory("onboard-local");
            Path remoteOut = Files.createTempDirectory("onboard-remote");

            OnboardReport expected = viaLocal.run(upload, localOut);
            OnboardReport actual = viaRemote.run(upload, remoteOut);

            String name = docx.getFileName().toString();
            assertThat(actual).as(name + " onboard report").isEqualTo(expected);
            if (expected.accepted()) {
                assertThat(PdfLines.extract(remoteOut.resolve("preview.pdf"))).as(name + " preview")
                        .isEqualTo(PdfLines.extract(localOut.resolve("preview.pdf")));
            }
        }
    }
}
