package com.tailor.web.storage;

import java.time.Duration;
import java.time.Instant;

/**
 * Private file storage (PHASE6_SPEC.md sections 3 and 9.2): S3 in AWS, MinIO in development.
 * Files are never handed out directly; the api issues a short-lived link only after the ownership
 * check. Keys are built by {@link StorageKeys}.
 */
public interface FileStorage {

    /** A time-limited download link. */
    record PresignedLink(String url, Instant expiresAt) {
    }

    /**
     * Stores {@code data} under {@code key}. With {@code overwrite == false} an existing object is
     * left untouched and {@link StorageConflictException} is thrown (originals and rendered
     * snapshots are never replaced).
     */
    void put(String key, byte[] data, String contentType, boolean overwrite);

    /** @throws StorageNotFoundException if there is no such object */
    byte[] get(String key);

    boolean exists(String key);

    /** A GET link valid for {@code ttl}; {@code downloadName} (optional) makes browsers save it under that name. */
    PresignedLink presignGet(String key, Duration ttl, String downloadName);

    /** Deletes every object whose key starts with {@code prefix}; returns how many were removed. */
    int deletePrefix(String prefix);

    /** Number of objects whose key starts with {@code prefix}. */
    int countPrefix(String prefix);
}
