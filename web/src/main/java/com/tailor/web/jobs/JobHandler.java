package com.tailor.web.jobs;

import com.tailor.engine.progress.ProgressListener;
import java.util.Map;

/**
 * Runs one type of job on a worker. A handler returns a result map (stored as the job's
 * {@code result}), throws {@link JobRejection} when the engine says no, and lets anything else
 * escape as an infrastructure failure. Handlers log ids, codes and counts, never document text.
 */
public interface JobHandler {

    JobType type();

    Map<String, Object> run(Job job, Context context) throws Exception;

    /** What a running job can tell the outside world. */
    interface Context {

        /** A named stage ({@code gate}, {@code normalize}, {@code assembling}, ...), with no percentage. */
        void stage(String stage);

        /** "generating section 2 of 6": a real step count, never an invented percentage. */
        void step(String stage, int step, int of);

        /**
         * Where the engine's real stages go (PHASE6_SPEC.md revision 3): each call becomes an event stored with the job,
         * with its time since the run began (and, for a finished stage, how long it took). A stage that is still open
         * when the handler starts the next {@link #stage} or returns is finished at that moment.
         */
        default ProgressListener events() {
            return ProgressListener.NONE;
        }

        /** True once the worker has given up on this run (timeout, or the lease was lost). */
        boolean cancelled();
    }
}
