package com.tailor.web.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailor.web.api.ApiError;
import com.tailor.web.auth.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** PHASE6_SPEC.md section 4.6. Every lookup is by id and owner; someone else's job is a 404. */
@RestController
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class JobController {

    /**
     * What the outside world sees of a job. {@code progress} is coarse and honest: a named
     * {@code stage}, and {@code step}/{@code of} only when there is a real count. {@code rejected}
     * means the engine said no (a result needing the user, never retried); {@code errorCode} then
     * says why. {@code result} is the job's output on success, or the rejection details. {@code events} are the
     * engine's real stages with their timings, in the order they happened, kept with the job so that a finished job can
     * be replayed: {@code seq}, {@code stage}, {@code state} (started, progress or finished), {@code at_ms} since the run
     * began, {@code duration_ms} on a finished stage, and a {@code detail} of ids and counts.
     */
    public record JobView(
            UUID id,
            String type,
            String status,
            JsonNode progress,
            JsonNode result,
            String errorCode,
            boolean rejected,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            JsonNode events) {

        static JobView of(Job job) {
            return new JobView(job.id(), job.type().dbName(), job.status().db(), job.progress(), job.result(),
                    job.errorCode(), job.rejected(), job.createdAt(), job.startedAt(), job.finishedAt(), job.events());
        }

        /** The status without the event list, which a stream sends one event at a time. */
        JobView withoutEvents() {
            return new JobView(id, type, status, progress, result, errorCode, rejected, createdAt, startedAt, finishedAt, null);
        }
    }

    private final JobService jobs;
    private final JobEventStreams streams;

    public JobController(JobService jobs, JobEventStreams streams) {
        this.jobs = jobs;
        this.streams = streams;
    }

    @Operation(summary = "A job's status (queued, running, succeeded, failed), progress, result and error code.",
            operationId = "getJob")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (also for another user's job)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/jobs/{id}")
    public JobView get(@PathVariable UUID id, @AuthenticationPrincipal AuthUser user) {
        return JobView.of(jobs.getForUser(id, user.id()));
    }

    @Operation(summary = "Server-sent events. Every stored event of the job is sent first as a 'progress' event (data = the "
            + "event, id = its seq), then new ones the moment the worker records them; a 'job' event carries the status "
            + "(without the event list) whenever it changes. A finished job replays all of its events and ends. Send "
            + "Last-Event-ID to resume after the last seq you got.", operationId = "getJobEvents")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (also for another user's job)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "429", description = "TOO_MANY_STREAMS: at most 5 open streams per user",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping(value = "/jobs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID id, @AuthenticationPrincipal AuthUser user,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        jobs.getForUser(id, user.id()); // 404 before any stream is opened
        return streams.open(id, user.id(), parseSeq(lastEventId));
    }

    private static int parseSeq(String lastEventId) {
        try {
            return lastEventId == null ? 0 : Math.max(0, Integer.parseInt(lastEventId.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
