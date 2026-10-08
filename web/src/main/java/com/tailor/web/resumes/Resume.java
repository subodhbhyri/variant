package com.tailor.web.resumes;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** A row of {@code resumes}. */
public record Resume(
        UUID id,
        UUID userId,
        String status,
        boolean active,
        String originalKey,
        String normalizedKey,
        String previewKey,
        String baselineKey,
        JsonNode onboardJson,
        JsonNode blocksJson,
        String rendererVersion,
        UUID onboardJobId,
        Instant createdAt) {

    public static final String UPLOADED = "uploaded";
    public static final String ONBOARDING = "onboarding";
    public static final String NEEDS_USER = "needs_user";
    public static final String READY = "ready";
    public static final String ACCEPTED = "accepted";
    public static final String REJECTED = "rejected";
    public static final String FAILED = "failed";
    public static final String ARCHIVED = "archived";
}
