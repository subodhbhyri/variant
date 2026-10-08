package com.tailor.renderer;

import java.nio.file.Path;
import java.util.Map;

/** Settings from the environment. Every limit has the section 2.1 default. */
public record RendererConfig(
        int port,
        int poolSize,
        int maxTasksPerProcess,
        long renderTimeoutMs,
        long healthTimeoutMs,
        int maxDocxBytes,
        Path scratchRoot,
        String officeHome) {

    public static final int MAX_DOCX_BYTES = 10 * 1024 * 1024;

    public static RendererConfig fromEnv(Map<String, String> env) {
        int cpus = Runtime.getRuntime().availableProcessors();
        return new RendererConfig(
                intOf(env, "PORT", 8090),
                // "start with one per vCPU"
                intOf(env, "POOL_SIZE", Math.max(1, cpus)),
                intOf(env, "MAX_TASKS_PER_PROCESS", 200),
                longOf(env, "RENDER_TIMEOUT_MS", 30_000),
                longOf(env, "HEALTH_TIMEOUT_MS", 5_000),
                intOf(env, "MAX_DOCX_BYTES", MAX_DOCX_BYTES),
                Path.of(env.getOrDefault("SCRATCH_DIR", System.getProperty("java.io.tmpdir"))),
                env.get("OFFICE_HOME"));
    }

    private static int intOf(Map<String, String> env, String key, int fallback) {
        String v = env.get(key);
        return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
    }

    private static long longOf(Map<String, String> env, String key, long fallback) {
        String v = env.get(key);
        return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim());
    }
}
