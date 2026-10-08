package com.tailor.web.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Claims and runs jobs (PHASE6_SPEC.md section 5). One call to {@link #runNext()} runs at most one
 * job to completion; worker threads call it in a loop.
 *
 * <p>Outcomes: a result is stored as {@code succeeded}; a {@link JobRejection} is stored as
 * {@code failed} with {@code rejected: true} and never retried; anything else is an infrastructure
 * failure, retried once (never for {@code generate}) and then failed. A job that outlives its time
 * limit is an infrastructure failure ({@code JOB_TIMEOUT}). Nothing here logs job content.
 */
@Service
public class JobProcessor {

    private static final Logger log = LoggerFactory.getLogger(JobProcessor.class);

    private final JobRepository jobs;
    private final JobProperties props;
    private final ObjectMapper json;
    private final String workerId;
    private final Map<JobType, JobHandler> handlers = new EnumMap<>(JobType.class);
    private final ExecutorService runners = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "job-run");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "job-heartbeat");
        t.setDaemon(true);
        return t;
    });

    /** Spring wires whichever handlers exist; with none (the api role) the processor simply has no work. */
    @Autowired
    public JobProcessor(JobRepository jobs, ObjectProvider<JobHandler> handlers, JobProperties props, ObjectMapper json) {
        this(jobs, handlers.orderedStream().toList(), props, json);
    }

    public JobProcessor(JobRepository jobs, List<JobHandler> handlers, JobProperties props, ObjectMapper json) {
        this.jobs = jobs;
        this.props = props;
        this.json = json;
        this.workerId = hostName() + "/" + UUID.randomUUID().toString().substring(0, 8);
        for (JobHandler handler : handlers) {
            if (this.handlers.put(handler.type(), handler) != null) {
                throw new IllegalStateException("two handlers for job type " + handler.type());
            }
        }
    }

    public String workerId() {
        return workerId;
    }

    /** The job types this process has a handler for; it never claims any other type. */
    public Set<JobType> handledTypes() {
        return handlers.keySet();
    }

    /** Reaps expired leases, claims the next eligible job and runs it. False if there was nothing to run. */
    public boolean runNext() {
        jobs.reapExpired();
        Optional<Job> claimed = jobs.claimNext(workerId, handledTypes(), props.lease());
        if (claimed.isEmpty()) {
            return false;
        }
        execute(claimed.get());
        return true;
    }

    private void execute(Job job) {
        JobHandler handler = handlers.get(job.type());
        Duration limit = Duration.ofMillis((long) (job.type().timeLimit().toMillis() * props.timeLimitScale()));
        RunContext context = new RunContext(job);
        Future<Map<String, Object>> run = runners.submit(() -> handler.run(job, context));
        long beatMs = Math.max(10, props.heartbeat().toMillis());
        ScheduledFuture<?> beat = heartbeats.scheduleAtFixedRate(() -> {
            if (!jobs.extendLease(job.id(), workerId, job.attempts(), props.lease())) {
                // Our lease is gone: the run that replaced us owns the job now. Stop quietly.
                context.cancel();
                run.cancel(true);
            }
        }, beatMs, beatMs, TimeUnit.MILLISECONDS);
        try {
            Map<String, Object> result = run.get(limit.toMillis(), TimeUnit.MILLISECONDS);
            if (!jobs.succeed(job.id(), workerId, job.attempts(), json.valueToTree(result == null ? Map.of() : result))) {
                log.warn("job {} finished after losing its lease; result discarded", job.id());
            }
        } catch (TimeoutException e) {
            context.cancel();
            run.cancel(true);
            infrastructureFailure(job, "JOB_TIMEOUT");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof JobRejection rejection) {
                reject(job, rejection);
            } else {
                // The class only: messages can carry text from a resume or posting.
                log.warn("job {} ({}) failed with {}", job.id(), job.type().dbName(), e.getCause().getClass().getName());
                infrastructureFailure(job, "INTERNAL_ERROR");
            }
        } catch (CancellationException e) {
            log.info("job {} stopped: its lease was lost", job.id());
        } catch (InterruptedException e) {
            // The worker is shutting down. The lease will expire and the job is picked up again.
            context.cancel();
            run.cancel(true);
            Thread.currentThread().interrupt();
        } finally {
            beat.cancel(false);
        }
    }

    private void reject(Job job, JobRejection rejection) {
        ObjectNode result = json.createObjectNode();
        result.put("rejected", true);
        result.set("details", json.valueToTree(rejection.details()));
        jobs.fail(job.id(), workerId, job.attempts(), rejection.code(), result);
    }

    private void infrastructureFailure(Job job, String code) {
        boolean retry = job.attempts() < job.type().maxAttempts();
        log.warn("job {} ({}) infrastructure failure {} on attempt {}; {}", job.id(), job.type().dbName(), code,
                job.attempts(), retry ? "retrying" : "giving up");
        if (retry) {
            jobs.requeue(job.id(), workerId, job.attempts());
        } else {
            jobs.fail(job.id(), workerId, job.attempts(), code, null);
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "worker";
        }
    }

    /** Progress is written fenced by this run, so a stale run cannot overwrite a newer one. */
    private final class RunContext implements JobHandler.Context {
        private final Job job;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        RunContext(Job job) {
            this.job = job;
        }

        void cancel() {
            cancelled.set(true);
        }

        @Override
        public void stage(String stage) {
            write(json.createObjectNode().put("stage", stage));
        }

        @Override
        public void step(String stage, int step, int of) {
            write(json.createObjectNode().put("stage", stage).put("step", step).put("of", of));
        }

        @Override
        public boolean cancelled() {
            return cancelled.get() || Thread.currentThread().isInterrupted();
        }

        private void write(ObjectNode progress) {
            if (!jobs.setProgress(job.id(), workerId, job.attempts(), progress)) {
                cancelled.set(true);
            }
        }
    }
}
