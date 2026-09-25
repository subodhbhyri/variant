package com.tailor.engine.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders .docx to PDF by shelling out to headless LibreOffice, one process per
 * call, each with its own {@code -env:UserInstallation} profile directory.
 *
 * Per PHASE1_SPEC.md section 2: parallel calls that share a profile directory
 * hang or fail, so every call gets a fresh one and deletes it afterward. A
 * call that exceeds the timeout is killed with {@code destroyForcibly()} and
 * retried at most once; a second timeout is a hard failure.
 */
public final class LibreOfficeRenderer implements Renderer {

    private static final Pattern VERSION_PATTERN = Pattern.compile("LibreOffice\\s+([0-9][0-9.]*)");

    private final String sofficeCommand;
    private final Path tmpRoot;
    private final long timeoutSeconds;
    private final String version;

    public LibreOfficeRenderer() {
        this("soffice", Path.of(System.getProperty("java.io.tmpdir")), 60);
    }

    public LibreOfficeRenderer(String sofficeCommand, Path tmpRoot, long timeoutSeconds) {
        this.sofficeCommand = sofficeCommand;
        this.tmpRoot = tmpRoot;
        this.timeoutSeconds = timeoutSeconds;
        this.version = probeVersion(sofficeCommand);
    }

    @Override
    public String version() {
        return version;
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
            } catch (RenderException e) {
                lastFailure = e;
                if (attempt == 1) {
                    continue; // one retry, per spec section 2
                }
            }
        }
        throw new RenderException(
                "render failed after 2 attempts for " + docxPath, lastFailure);
    }

    private Path renderOnce(Path docxPath, Path outDir) throws RenderException {
        Path profileDir = tmpRoot.resolve("lo-" + UUID.randomUUID());
        Process process = null;
        try {
            Files.createDirectories(profileDir);

            List<String> command = List.of(
                    sofficeCommand,
                    "--headless",
                    "--norestore",
                    "--nolockcheck",
                    "-env:UserInstallation=file://" + profileDir.toAbsolutePath(),
                    "--convert-to", "pdf",
                    "--outdir", outDir.toAbsolutePath().toString(),
                    docxPath.toAbsolutePath().toString());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            process = pb.start();
            Process started = process; // final for the drain lambda; same object as `process`

            // Drain output so the process never blocks on a full pipe, and keep
            // it so a failure message can show what soffice actually said.
            java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
            Thread drain = new Thread(() -> {
                try (var in = started.getInputStream()) {
                    in.transferTo(captured);
                } catch (IOException ignored) {
                    // process ended; nothing more to read
                }
            });
            drain.setDaemon(true);
            drain.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                throw new RenderException(
                        "soffice timed out after " + timeoutSeconds + "s: " + docxPath);
            }
            drain.join(5000); // let the drain thread catch up now the process has exited

            int exit = process.exitValue();
            if (exit != 0) {
                throw new RenderException(
                        "soffice exited " + exit + " for " + docxPath
                                + " output=" + captured.toString(java.nio.charset.StandardCharsets.UTF_8));
            }

            String pdfName = stripExtension(docxPath.getFileName().toString()) + ".pdf";
            Path pdfPath = outDir.resolve(pdfName);
            if (!Files.isRegularFile(pdfPath)) {
                throw new RenderException(
                        "soffice reported success but no PDF at " + pdfPath);
            }
            return pdfPath;

        } catch (IOException e) {
            throw new RenderException("failed to start soffice for " + docxPath, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RenderException("interrupted rendering " + docxPath, e);
        } finally {
            // Whatever path we're leaving by — the 60s timeout above, an interrupt (e.g. an
            // external deadline cancelling this call), or a plain exception — a still-alive
            // subprocess must not be left running. Previously only the explicit timeout branch
            // called destroyForcibly(); an interrupt during waitFor() left soffice orphaned.
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            deleteRecursively(profileDir);
        }
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a)) // children before parents
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best effort; a leftover temp profile dir is not fatal
                        }
                    });
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static String probeVersion(String sofficeCommand) {
        try {
            Process p = new ProcessBuilder(sofficeCommand, "--version")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(10, TimeUnit.SECONDS);
            Matcher m = VERSION_PATTERN.matcher(out);
            return m.find() ? m.group(1) : "unknown";
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "unknown";
        }
    }
}
