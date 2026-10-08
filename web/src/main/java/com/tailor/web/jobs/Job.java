package com.tailor.web.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** A row of {@code jobs}. {@code payload}, {@code progress} and {@code result} are JSON documents. */
public record Job(
        UUID id,
        UUID userId,
        JobType type,
        JsonNode payload,
        Status status,
        int attempts,
        JsonNode progress,
        JsonNode result,
        String errorCode,
        String workerId,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant leaseUntil) {

    public enum Status {
        QUEUED, RUNNING, SUCCEEDED, FAILED;

        public String db() {
            return name().toLowerCase();
        }

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED;
        }

        public static Status fromDb(String s) {
            return valueOf(s.toUpperCase());
        }
    }

    /** An engine rejection ({@code NEEDS_USER}, a gate code, ...): a result, never a failure, never retried. */
    public boolean rejected() {
        return result != null && result.path("rejected").asBoolean(false);
    }
}
