package com.tailor.web.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The job queue in PostgreSQL (PHASE6_SPEC.md section 5). Workers claim with
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} and take a lease; an expired lease means a crashed
 * worker. Every write by a running job is fenced by {@code (worker_id, attempts)}.
 */
@Repository
public class JobRepository {

    private static final Logger log = LoggerFactory.getLogger(JobRepository.class);
    private static final int CANDIDATES_PER_CLAIM = 25;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public JobRepository(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    // ---- api side ------------------------------------------------------------------------------

    /**
     * Queues a job, or returns the user's existing job for the same {@code Idempotency-Key} within
     * the last 24 hours.
     */
    public Job enqueue(UUID userId, JobType type, JsonNode payload, String idempotencyKey) {
        Instant now = clock.instant();
        return tx.execute(status -> {
            if (idempotencyKey != null) {
                Optional<Job> existing = findByKey(userId, idempotencyKey, now.minus(Duration.ofHours(24)));
                if (existing.isPresent()) {
                    return existing.get();
                }
                // An older job with this key is outside the window: free the key for reuse.
                jdbc.update("UPDATE jobs SET idempotency_key = NULL WHERE user_id = ? AND idempotency_key = ?",
                        userId, idempotencyKey);
            }
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO jobs (id, user_id, type, payload, status, priority, idempotency_key, created_at)"
                            + " VALUES (?, ?, ?, ?::jsonb, 'queued', ?, ?, ?)",
                    id, userId, type.dbName(), write(payload), type.priority(), idempotencyKey, ts(now));
            return find(id).orElseThrow();
        });
    }

    private Optional<Job> findByKey(UUID userId, String key, Instant since) {
        return jdbc.query("SELECT * FROM jobs WHERE user_id = ? AND idempotency_key = ? AND created_at > ?",
                mapper(), userId, key, ts(since)).stream().findFirst();
    }

    /** Looked up by id and owner in one query: someone else's job is simply not found (section 9.2). */
    public Optional<Job> findForUser(UUID id, UUID userId) {
        return jdbc.query("SELECT * FROM jobs WHERE id = ? AND user_id = ?", mapper(), id, userId)
                .stream().findFirst();
    }

    public Optional<Job> find(UUID id) {
        return jdbc.query("SELECT * FROM jobs WHERE id = ?", mapper(), id).stream().findFirst();
    }

    // ---- worker side ---------------------------------------------------------------------------

    /**
     * Claims the best job a worker can run: highest priority first, oldest first, skipping users who
     * are at their concurrency limit. Returns the job as claimed (attempt number already counted).
     */
    public Optional<Job> claimNext(String workerId, Collection<JobType> handled, Duration lease) {
        if (handled.isEmpty()) {
            return Optional.empty();
        }
        String types = handled.stream().map(JobType::dbName).collect(Collectors.joining(","));
        Instant now = clock.instant();
        UUID claimed = tx.execute(status -> {
            List<Candidate> candidates = jdbc.query(
                    "SELECT id, user_id, type FROM jobs WHERE status = 'queued' AND type = ANY (string_to_array(?, ','))"
                            + " ORDER BY priority, created_at, id FOR UPDATE SKIP LOCKED LIMIT " + CANDIDATES_PER_CLAIM,
                    (rs, n) -> new Candidate(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                            JobType.fromDb(rs.getString("type"))), types);
            for (Candidate c : candidates) {
                // One claimer per user at a time, so two workers can't both pass the same user's limit.
                // Non-blocking: a busy user is skipped now and reconsidered on the next poll.
                Boolean locked = jdbc.queryForObject(
                        "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 1))", Boolean.class, c.userId().toString());
                if (!Boolean.TRUE.equals(locked)) {
                    continue;
                }
                JobType.Group group = c.type().group();
                if (group != null) {
                    String members = group.members().stream().map(JobType::dbName).collect(Collectors.joining(","));
                    Integer running = jdbc.queryForObject(
                            "SELECT count(*) FROM jobs WHERE user_id = ? AND status = 'running'"
                                    + " AND type = ANY (string_to_array(?, ','))", Integer.class, c.userId(), members);
                    if (running != null && running >= group.limit()) {
                        continue;
                    }
                }
                jdbc.update("UPDATE jobs SET status = 'running', attempts = attempts + 1, started_at = ?,"
                                + " lease_until = ?, worker_id = ?, progress = NULL WHERE id = ?",
                        ts(now), ts(now.plus(lease)), workerId, c.id());
                return c.id();
            }
            return null;
        });
        return claimed == null ? Optional.empty() : find(claimed);
    }

    /** Extends the lease of the run identified by {@code (workerId, attempt)}; false if that run no longer owns the job. */
    public boolean extendLease(UUID id, String workerId, int attempt, Duration lease) {
        return jdbc.update("UPDATE jobs SET lease_until = ? WHERE id = ? AND status = 'running'"
                        + " AND worker_id = ? AND attempts = ?",
                ts(clock.instant().plus(lease)), id, workerId, attempt) == 1;
    }

    public boolean setProgress(UUID id, String workerId, int attempt, JsonNode progress) {
        return jdbc.update("UPDATE jobs SET progress = ?::jsonb WHERE id = ? AND status = 'running'"
                + " AND worker_id = ? AND attempts = ?", write(progress), id, workerId, attempt) == 1;
    }

    public boolean succeed(UUID id, String workerId, int attempt, JsonNode result) {
        return finish(id, workerId, attempt, "succeeded", null, result);
    }

    /** {@code result} may carry the details of a rejection; {@code errorCode} is the code the user sees. */
    public boolean fail(UUID id, String workerId, int attempt, String errorCode, JsonNode result) {
        return finish(id, workerId, attempt, "failed", errorCode, result);
    }

    private boolean finish(UUID id, String workerId, int attempt, String status, String errorCode, JsonNode result) {
        return jdbc.update("UPDATE jobs SET status = ?, error_code = ?, result = ?::jsonb, finished_at = ?,"
                        + " lease_until = NULL WHERE id = ? AND status = 'running' AND worker_id = ? AND attempts = ?",
                status, errorCode, result == null ? null : write(result), ts(clock.instant()), id, workerId, attempt) == 1;
    }

    /** Puts a failed run back in the queue for its one retry. */
    public boolean requeue(UUID id, String workerId, int attempt) {
        return jdbc.update("UPDATE jobs SET status = 'queued', lease_until = NULL, worker_id = NULL, progress = NULL"
                + " WHERE id = ? AND status = 'running' AND worker_id = ? AND attempts = ?", id, workerId, attempt) == 1;
    }

    /**
     * Handles jobs whose lease expired (their worker crashed or hung): a retryable job with an
     * attempt left goes back to the queue; anything else fails with {@code WORKER_LOST}. A
     * {@code generate} job is never re-run automatically, because it costs money.
     */
    public int reapExpired() {
        Instant now = clock.instant();
        Integer handled = tx.execute(status -> {
            List<Expired> expired = jdbc.query(
                    "SELECT id, type, attempts FROM jobs WHERE status = 'running' AND lease_until < ?"
                            + " FOR UPDATE SKIP LOCKED",
                    (rs, n) -> new Expired(rs.getObject("id", UUID.class), JobType.fromDb(rs.getString("type")),
                            rs.getInt("attempts")), ts(now));
            for (Expired e : expired) {
                if (e.attempts() < e.type().maxAttempts()) {
                    jdbc.update("UPDATE jobs SET status = 'queued', lease_until = NULL, worker_id = NULL, progress = NULL"
                            + " WHERE id = ?", e.id());
                    log.warn("job {} ({}) lost its worker on attempt {}; queued again", e.id(), e.type().dbName(), e.attempts());
                } else {
                    jdbc.update("UPDATE jobs SET status = 'failed', error_code = 'WORKER_LOST', finished_at = ?,"
                            + " lease_until = NULL WHERE id = ?", ts(now), e.id());
                    log.warn("job {} ({}) lost its worker on attempt {}; failed", e.id(), e.type().dbName(), e.attempts());
                }
            }
            return expired.size();
        });
        return handled == null ? 0 : handled;
    }

    // ---- mapping -------------------------------------------------------------------------------

    private record Candidate(UUID id, UUID userId, JobType type) {
    }

    private record Expired(UUID id, JobType type, int attempts) {
    }

    private RowMapper<Job> mapper() {
        return (rs, n) -> new Job(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                JobType.fromDb(rs.getString("type")),
                read(rs, "payload"),
                Job.Status.fromDb(rs.getString("status")),
                rs.getInt("attempts"),
                read(rs, "progress"),
                read(rs, "result"),
                rs.getString("error_code"),
                rs.getString("worker_id"),
                instant(rs, "created_at"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                instant(rs, "lease_until"));
    }

    private JsonNode read(ResultSet rs, String column) throws SQLException {
        String text = rs.getString(column);
        if (text == null) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("bad JSON in jobs." + column, e);
        }
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
