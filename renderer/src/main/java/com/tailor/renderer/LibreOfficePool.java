package com.tailor.renderer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.jodconverter.core.office.OfficeException;
import org.jodconverter.core.office.OfficeManager;
import org.jodconverter.local.LocalConverter;
import org.jodconverter.local.office.LocalOfficeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PHASE6_SPEC.md section 2.1: a pool of persistent LibreOffice processes (the Phase 5 backlog
 * item). A process is restarted after {@code maxTasksPerProcess} renders, on a crash and on a
 * timeout; each render has a time limit and its own scratch directory, deleted afterwards.
 *
 * Nothing about the document is logged: sizes, codes and timings only.
 */
public final class LibreOfficePool implements DocxConverter {

    private static final Logger log = LoggerFactory.getLogger(LibreOfficePool.class);
    private static final Pattern VERSION_PATTERN = Pattern.compile("LibreOffice\\s+([0-9][0-9.]*)");
    private static final int FIRST_PORT = 2002;

    private final OfficeManager manager;
    private final Path requestRoot;
    private final String version;

    public LibreOfficePool(RendererConfig config) {
        this(config, true);
    }

    /** {@code verifyOnStart == false} skips the startup self-test; only a test of the timeout itself needs that. */
    LibreOfficePool(RendererConfig config, boolean verifyOnStart) {
        this.requestRoot = config.scratchRoot().resolve("requests");
        Path officeRoot = config.scratchRoot().resolve("office");
        try {
            Files.createDirectories(requestRoot);
            Files.createDirectories(officeRoot);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create scratch directories under " + config.scratchRoot(), e);
        }
        // A restart of the renderer must not inherit the previous run's leftovers.
        deleteContents(requestRoot);

        warmUp(config.officeHome(), officeRoot);

        // Starting several LibreOffice processes at the same moment can crash one of them (measured: "Signal 6"
        // at startup), which would leave a slot that never answers and makes every other request wait out the
        // render timeout. So: fail fast if a process won't start, prove every process renders before the pool is
        // used, and if either fails, stop everything and start again.
        OfficeManager started = null;
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= START_ATTEMPTS && started == null; attempt++) {
            OfficeManager candidate = build(config, officeRoot);
            try {
                candidate.start();
                if (verifyOnStart) {
                    selfTest(candidate, config);
                }
                started = candidate;
            } catch (OfficeException | RuntimeException e) {
                lastFailure = new IllegalStateException("LibreOffice did not start cleanly: " + e.getClass().getSimpleName(), e);
                log.warn("renderer pool start attempt {} of {} failed: {}", attempt, START_ATTEMPTS, e.getClass().getSimpleName());
                stopQuietly(candidate);
                sleepQuietly(1000L * attempt);
            }
        }
        if (started == null) {
            throw lastFailure != null ? lastFailure : new IllegalStateException("LibreOffice could not be started");
        }
        this.manager = started;
        this.version = probeVersion(config.officeHome());
        log.info("renderer pool started: size={} maxTasksPerProcess={} timeoutMs={} libreoffice={}",
                config.poolSize(), config.maxTasksPerProcess(), config.renderTimeoutMs(), version);
    }

    private static final int START_ATTEMPTS = 3;

    /** One office process per port; the pool is as big as the port list. */
    private OfficeManager build(RendererConfig config, Path officeRoot) {
        LocalOfficeManager.Builder builder = LocalOfficeManager.builder()
                .portNumbers(IntStream.range(FIRST_PORT, FIRST_PORT + config.poolSize()).toArray())
                .maxTasksPerProcess(config.maxTasksPerProcess())
                .taskExecutionTimeout(config.renderTimeoutMs())
                .taskQueueTimeout(config.renderTimeoutMs())
                .startFailFast(true)
                .workingDir(officeRoot.toFile());
        if (config.officeHome() != null && !config.officeHome().isBlank()) {
            builder.officeHome(config.officeHome());
        }
        return builder.build();
    }

    /** Every process must produce a PDF now (all at once), or the start is not clean. */
    private void selfTest(OfficeManager candidate, RendererConfig config) throws OfficeException {
        byte[] fixture = HealthFixture.docx();
        java.util.List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(config.poolSize());
        try {
            for (int i = 0; i < config.poolSize(); i++) {
                results.add(pool.submit(() -> {
                    Path dir = requestRoot.resolve("selftest-" + UUID.randomUUID());
                    try {
                        Files.createDirectory(dir);
                        File in = dir.resolve("input.docx").toFile();
                        File out = dir.resolve("output.pdf").toFile();
                        Files.write(in.toPath(), fixture);
                        LocalConverter.make(candidate).convert(in).to(out).execute();
                        return out.length() > 0;
                    } finally {
                        deleteTree(dir);
                    }
                }));
            }
            for (var r : results) {
                if (!Boolean.TRUE.equals(r.get(config.renderTimeoutMs() + 10_000, TimeUnit.MILLISECONDS))) {
                    throw new IllegalStateException("a LibreOffice process produced no PDF");
                }
            }
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("a LibreOffice process did not answer: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Starts and stops one throwaway LibreOffice, so first-run work is not done by several processes at once. */
    private static void warmUp(String officeHome, Path officeRoot) {
        String soffice = officeHome == null || officeHome.isBlank() ? "soffice" : officeHome + "/program/soffice";
        Path profile = officeRoot.resolve("warm-up-" + UUID.randomUUID());
        try {
            Process p = new ProcessBuilder(soffice, "--headless", "--norestore", "--terminate_after_init",
                    "-env:UserInstallation=file://" + profile.toAbsolutePath()).redirectErrorStream(true).start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (IOException e) {
            log.warn("LibreOffice warm-up could not run: {}", e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            deleteTree(profile);
        }
    }

    private static void stopQuietly(OfficeManager m) {
        try {
            m.stop();
        } catch (OfficeException | RuntimeException ignored) {
            // best effort: the next attempt starts its own processes
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public byte[] toPdf(byte[] docx) throws RenderFailure {
        if (!isZip(docx)) {
            // LibreOffice will happily import plain text, RTF or HTML named .docx. Only OOXML
            // (a ZIP container) is a resume, so nothing else reaches its import filters.
            throw RenderFailure.badInput("not a .docx file");
        }
        Path dir = requestRoot.resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectory(dir);
            File in = dir.resolve("input.docx").toFile();
            File out = dir.resolve("output.pdf").toFile();
            Files.write(in.toPath(), docx);
            LocalConverter.make(manager).convert(in).to(out).execute();
            if (!out.isFile() || out.length() == 0) {
                throw new RenderFailure(RenderFailure.Code.RENDER_FAILED, "LibreOffice produced no PDF");
            }
            return Files.readAllBytes(out.toPath());
        } catch (OfficeException e) {
            throw failure(e);
        } catch (IOException e) {
            throw new RenderFailure(RenderFailure.Code.RENDER_FAILED, "scratch I/O failed", e);
        } finally {
            // "No file outlives its request."
            deleteTree(dir);
        }
    }

    private static boolean isZip(byte[] data) {
        return data.length >= 4 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public void close() {
        try {
            manager.stop();
        } catch (OfficeException e) {
            log.warn("error stopping LibreOffice: {}", e.getClass().getSimpleName());
        }
    }

    /** A timeout is reported as such; every other office failure (crash, bad document) is RENDER_FAILED. */
    private static RenderFailure failure(OfficeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getClass().getSimpleName().toLowerCase().contains("timeout")) {
                return new RenderFailure(RenderFailure.Code.RENDER_TIMEOUT, "render timed out", e);
            }
        }
        return new RenderFailure(RenderFailure.Code.RENDER_FAILED, "render failed: " + e.getClass().getSimpleName(), e);
    }

    private static String probeVersion(String officeHome) {
        String soffice = officeHome == null || officeHome.isBlank() ? "soffice" : officeHome + "/program/soffice";
        try {
            Process p = new ProcessBuilder(soffice, "--version").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(10, TimeUnit.SECONDS);
            Matcher m = VERSION_PATTERN.matcher(out);
            return m.find() ? m.group(1) : "unknown";
        } catch (IOException e) {
            return "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }

    private static void deleteContents(Path dir) {
        try (var children = Files.list(dir)) {
            children.forEach(LibreOfficePool::deleteTree);
        } catch (IOException ignored) {
            // best effort; per-request directories are unique anyway
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
