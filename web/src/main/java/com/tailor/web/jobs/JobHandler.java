package com.tailor.web.jobs;

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

        /** True once the worker has given up on this run (timeout, or the lease was lost). */
        boolean cancelled();
    }
}
