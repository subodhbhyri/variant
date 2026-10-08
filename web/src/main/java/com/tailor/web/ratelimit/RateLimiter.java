package com.tailor.web.ratelimit;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A sliding-window limiter that lives in PostgreSQL so every api task sees the same counts.
 * The subject is an id or a hash, never an address or text (PHASE6_SPEC.md section 9.3).
 */
@Service
public class RateLimiter {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RateLimiter(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Records one event and returns true if fewer than {@code limit} events for
     * {@code (bucket, subject)} happened in the last {@code window}; otherwise records nothing and
     * returns false. Concurrent callers for the same key are serialized with an advisory lock.
     */
    public boolean tryAcquire(String bucket, String subject, int limit, Duration window) {
        Instant now = clock.instant();
        Timestamp since = Timestamp.from(now.minus(window));
        return Boolean.TRUE.equals(tx.execute(status -> {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, bucket + ':' + subject);
            jdbc.update("DELETE FROM rate_events WHERE bucket = ? AND subject = ? AND created_at <= ?",
                    bucket, subject, since);
            Integer used = jdbc.queryForObject(
                    "SELECT count(*) FROM rate_events WHERE bucket = ? AND subject = ?", Integer.class, bucket, subject);
            if (used != null && used >= limit) {
                return false;
            }
            jdbc.update("INSERT INTO rate_events (bucket, subject, created_at) VALUES (?, ?, ?)",
                    bucket, subject, Timestamp.from(now));
            return true;
        }));
    }
}
