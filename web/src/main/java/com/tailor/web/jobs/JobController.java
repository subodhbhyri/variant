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
     * says why. {@code result} is the job's output on success, or the rejection details.
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
            Instant finishedAt) {

        static JobView of(Job job) {
            return new JobView(job.id(), job.type().dbName(), job.status().db(), job.progress(), job.result(),
                    job.errorCode(), job.rejected(), job.createdAt(), job.startedAt(), job.finishedAt());
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

    @Operation(summary = "Server-sent events with the same JobView, sent as the job changes; the stream ends when the "
            + "job is succeeded or failed.", operationId = "getJobEvents")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (also for another user's job)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "429", description = "TOO_MANY_STREAMS: at most 5 open streams per user",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping(value = "/jobs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID id, @AuthenticationPrincipal AuthUser user) {
        jobs.getForUser(id, user.id()); // 404 before any stream is opened
        return streams.open(id, user.id());
    }
}
