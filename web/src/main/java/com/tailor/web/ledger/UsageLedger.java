package com.tailor.web.ledger;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * PHASE6_SPEC.md section 8: one row per billable or costly event, each with a unique idempotency
 * key, so a delivery that happens twice can never write twice. Phase 6 charges nothing; rows hold
 * ids, a kind and an amount, never text.
 */
@Service
public class UsageLedger {

    public static final String GENERATION = "generation";
    public static final String TAILORING = "tailoring";
    public static final String ALTERNATIVE = "alternative";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public UsageLedger(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Writes the row unless one with this key exists; returns whether it wrote. */
    public boolean record(UUID userId, String kind, UUID refId, String idempotencyKey, double costUsd) {
        return jdbc.update("INSERT INTO usage_ledger (id, user_id, kind, ref_id, idempotency_key, cost_usd, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING",
                UUID.randomUUID(), userId, kind, refId, idempotencyKey, BigDecimal.valueOf(costUsd),
                Timestamp.from(clock.instant())) == 1;
    }
}
