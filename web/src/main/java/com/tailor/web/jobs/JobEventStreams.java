package com.tailor.web.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailor.web.api.ApiException;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-sent events for a job (PHASE6_SPEC.md section 4.6). The stream polls the job row, so it
 * works the same whichever api task holds the connection and whichever worker runs the job.
 */
@Component
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class JobEventStreams {

    static final int MAX_STREAMS_PER_USER = 5;
    private static final long POLL_MS = 500;
    private static final long KEEPALIVE_MS = 15_000;
    private static final long MAX_STREAM_MS = 15 * 60_000;

    private static final Logger log = LoggerFactory.getLogger(JobEventStreams.class);

    private final JobRepository jobs;
    private final ObjectMapper json;
    private final Map<UUID, AtomicInteger> openByUser = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "job-sse");
        t.setDaemon(true);
        return t;
    });

    public JobEventStreams(JobRepository jobs, ObjectMapper json) {
        this.jobs = jobs;
        this.json = json;
    }

    public SseEmitter open(UUID jobId, UUID userId) {
        AtomicInteger open = openByUser.computeIfAbsent(userId, u -> new AtomicInteger());
        if (open.incrementAndGet() > MAX_STREAMS_PER_USER) {
            open.decrementAndGet();
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_STREAMS",
                    "Too many open progress streams. Close one and try again.");
        }
        SseEmitter emitter = new SseEmitter(MAX_STREAM_MS);
        Stream stream = new Stream(jobId, userId, emitter, open);
        stream.future = scheduler.scheduleWithFixedDelay(stream::tick, 0, POLL_MS, TimeUnit.MILLISECONDS);
        emitter.onCompletion(stream::close);
        emitter.onTimeout(stream::close);
        emitter.onError(e -> stream.close());
        return emitter;
    }

    private final class Stream {
        private final UUID jobId;
        private final UUID userId;
        private final SseEmitter emitter;
        private final AtomicInteger open;
        private volatile ScheduledFuture<?> future;
        private volatile boolean closed;
        private String lastSent;
        private long lastWrite = System.currentTimeMillis();

        Stream(UUID jobId, UUID userId, SseEmitter emitter, AtomicInteger open) {
            this.jobId = jobId;
            this.userId = userId;
            this.emitter = emitter;
            this.open = open;
        }

        void tick() {
            if (closed) {
                return;
            }
            try {
                Optional<Job> found = jobs.findForUser(jobId, userId);
                if (found.isEmpty()) { // the account was deleted while watching
                    emitter.complete();
                    return;
                }
                Job job = found.get();
                JsonNode view = json.valueToTree(JobController.JobView.of(job));
                String text = json.writeValueAsString(view);
                if (!text.equals(lastSent)) {
                    emitter.send(SseEmitter.event().name("job").data(text, MediaType.APPLICATION_JSON));
                    lastSent = text;
                    lastWrite = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - lastWrite > KEEPALIVE_MS) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    lastWrite = System.currentTimeMillis();
                }
                if (job.status().terminal()) {
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException e) {
                // The client went away.
                close();
            } catch (RuntimeException e) {
                log.warn("job stream for {} stopped: {}", jobId, e.getClass().getName());
                emitter.completeWithError(e);
                close();
            }
        }

        void close() {
            if (!closed) {
                closed = true;
                if (future != null) {
                    future.cancel(false);
                }
                open.decrementAndGet();
            }
        }
    }
}
