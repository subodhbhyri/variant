package com.tailor.web.storage;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PHASE6_SPEC.md section 9.4: removes the stored files of deleted accounts. {@code DELETE /me}
 * writes the user's prefix to {@code storage_deletions} in the same transaction as the row
 * deletion; this removes the objects (well inside the 24 hours) and marks the entry done. Safe to
 * run on several workers at once: deleting a prefix twice is harmless.
 */
@Component
public class StorageCleaner {

    private static final Logger log = LoggerFactory.getLogger(StorageCleaner.class);

    private final JdbcTemplate jdbc;
    private final FileStorage storage;
    private final Clock clock;

    public StorageCleaner(JdbcTemplate jdbc, FileStorage storage, Clock clock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.clock = clock;
    }

    /** Purges every pending prefix; returns how many were completed. */
    public int runOnce() {
        List<String> pending = jdbc.queryForList(
                "SELECT prefix FROM storage_deletions WHERE completed_at IS NULL ORDER BY created_at", String.class);
        int done = 0;
        for (String prefix : pending) {
            try {
                int removed = storage.deletePrefix(prefix);
                jdbc.update("UPDATE storage_deletions SET completed_at = ? WHERE prefix = ?",
                        Timestamp.from(clock.instant()), prefix);
                log.info("removed {} stored files of a deleted account", removed);
                done++;
            } catch (RuntimeException e) {
                // Retried on the next run; the prefix stays pending.
                log.error("storage cleanup failed: {}", e.getClass().getName());
            }
        }
        return done;
    }
}
