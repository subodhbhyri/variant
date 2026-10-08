package com.tailor.web.match;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** Rows of {@code postings}, {@code matches} and {@code snapshots}. */
public final class MatchRows {

    private MatchRows() {
    }

    /** The posting text is the user's own content: it is stored and shown to them, never logged. */
    public record Posting(UUID id, UUID userId, String text, JsonNode parsed, String fingerprint, UUID libraryId,
            UUID jobId, Instant createdAt) {
    }

    /** {@code cacheOf} points at the match this one reuses; its snapshots are the source match's. */
    public record Match(UUID id, UUID userId, UUID postingId, UUID libraryId, JsonNode result, UUID cacheOf,
            Instant createdAt) {
    }

    public record Snapshot(UUID id, UUID userId, UUID matchId, int rank, String label, JsonNode assembly, UUID parentId,
            JsonNode edits, String docxKey, String pdfKey, String status, Instant createdAt) {

        public static final String STORED = "stored";
        public static final String RENDERING = "rendering";
        public static final String RENDERED = "rendered";
        public static final String FAILED = "failed";
    }
}
