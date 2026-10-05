package com.tailor.engine.match;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Per-posting wall time and render count for each pipeline stage (instrumentation only — nothing
 * here changes what is computed). Stages are recorded in the order they finish. A {@code null}
 * counter (tests that don't render) reports zero renders everywhere.
 */
public final class PipelineTiming {

    public record Stage(String name, long wallMs, long renders) {
    }

    public record Report(String jd, long totalMs, long totalRenders, List<Stage> stages) {
    }

    private final CountingRenderer counter;
    private final long rendersAtStart;
    private final long startNanos = System.nanoTime();
    private final List<Stage> stages = new ArrayList<>();

    public PipelineTiming(CountingRenderer counter) {
        this.counter = counter;
        this.rendersAtStart = counter == null ? 0 : counter.count();
    }

    public PipelineTiming() {
        this(null);
    }

    public <T> T time(String name, Callable<T> work) throws Exception {
        long rendersBefore = renders();
        long startedAt = System.nanoTime();
        try {
            return work.call();
        } finally {
            stages.add(new Stage(name, millisSince(startedAt), renders() - rendersBefore));
        }
    }

    /** The render count now, for pairing with {@link #record} around a block that can't throw. */
    public long renderMark() {
        return renders();
    }

    public void record(String name, long startNanos, long rendersBefore) {
        stages.add(new Stage(name, millisSince(startNanos), renders() - rendersBefore));
    }

    /** A zero-duration marker in the stage list (e.g. a swap that failed, with its reason). */
    public void note(String name) {
        stages.add(new Stage(name, 0, 0));
    }

    public List<Stage> stages() {
        return List.copyOf(stages);
    }

    public long totalMs() {
        return millisSince(startNanos);
    }

    public long totalRenders() {
        return renders() - rendersAtStart;
    }

    public Report report(String jd) {
        return new Report(jd, totalMs(), totalRenders(), stages());
    }

    private long renders() {
        return counter == null ? 0 : counter.count();
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
