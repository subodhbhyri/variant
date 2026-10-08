package com.tailor.renderer;

import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RendererMain {

    private static final Logger log = LoggerFactory.getLogger(RendererMain.class);

    private RendererMain() {
    }

    public static void main(String[] args) throws Exception {
        RendererConfig config = RendererConfig.fromEnv(System.getenv());
        // On a read-only root filesystem everything lives under the scratch tmpfs; these are the
        // directories the image points HOME and java.io.tmpdir at.
        Files.createDirectories(config.scratchRoot().resolve("home"));
        Files.createDirectories(config.scratchRoot().resolve("tmp"));
        LibreOfficePool pool = new LibreOfficePool(config);
        RendererServer server = new RendererServer(config, pool).start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "renderer-shutdown"));
        log.info("renderer listening on port {}", server.port());
        Thread.currentThread().join();
    }
}
