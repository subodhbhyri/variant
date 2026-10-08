package com.tailor.engine.progress;

import java.util.Map;

/**
 * Where the engine tells a caller what it is doing, as it does it (PHASE6_SPEC.md revision 3). A stage {@code started}
 * when the engine begins that work and {@code finished} when it is done; a {@code progress} call is a real step inside
 * a stage (a project placed in a position). The engine calls these at the moment things happen: it never sleeps, never
 * batches, and never reports a stage that did not run. {@code detail} carries ids, counts and codes only, never the
 * resume's own text. Calls can come from the thread that runs the engine's work, so an implementation must be thread-safe
 * and quick (it must not block the engine).
 *
 * <p>With {@link #NONE} (the default everywhere) nothing is reported and nothing else changes.
 */
public interface ProgressListener {

    ProgressListener NONE = new ProgressListener() {
        @Override
        public void started(String stage, Map<String, Object> detail) {
        }

        @Override
        public void progress(String stage, Map<String, Object> detail) {
        }

        @Override
        public void finished(String stage, Map<String, Object> detail) {
        }
    };

    /** The engine began {@code stage}. */
    void started(String stage, Map<String, Object> detail);

    /** One real step inside {@code stage} (for example a project placed in a position). */
    void progress(String stage, Map<String, Object> detail);

    /** The engine finished {@code stage}; {@code detail} carries what it found. */
    void finished(String stage, Map<String, Object> detail);

    default void started(String stage) {
        started(stage, Map.of());
    }

    default void finished(String stage) {
        finished(stage, Map.of());
    }

    /** Runs {@code work} as one stage: started before it, finished after it (also when it throws). */
    default <T> T stage(String stage, java.util.concurrent.Callable<T> work) throws Exception {
        started(stage, Map.of());
        try {
            return work.call();
        } finally {
            finished(stage, Map.of());
        }
    }
}
