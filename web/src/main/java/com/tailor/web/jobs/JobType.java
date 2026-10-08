package com.tailor.web.jobs;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * PHASE6_SPEC.md section 5: priority (1 is served first), time limit, and whether an
 * infrastructure failure is retried (once). {@code generate} is never retried automatically: it
 * costs money, so the user retries.
 */
public enum JobType {
    MATCH("match", 1, Duration.ofSeconds(60), true, Group.MATCH),
    EDIT_REVISION("edit_revision", 1, Duration.ofSeconds(60), true, null),
    RENDER_ALTERNATIVE("render_alternative", 2, Duration.ofSeconds(60), true, null),
    ONBOARD("onboard", 3, Duration.ofSeconds(180), true, Group.HEAVY),
    GENERATE("generate", 4, Duration.ofMinutes(10), false, Group.HEAVY);

    /** Per-user concurrency: at most one onboard or generate job, and two match jobs, running at once. */
    public enum Group {
        HEAVY(1),
        MATCH(2);

        private final int limit;

        Group(int limit) {
            this.limit = limit;
        }

        public int limit() {
            return limit;
        }

        public List<JobType> members() {
            return Arrays.stream(JobType.values()).filter(t -> t.group == this).toList();
        }
    }

    private final String dbName;
    private final int priority;
    private final Duration timeLimit;
    private final boolean retriedOnInfrastructureFailure;
    private final Group group;

    JobType(String dbName, int priority, Duration timeLimit, boolean retried, Group group) {
        this.dbName = dbName;
        this.priority = priority;
        this.timeLimit = timeLimit;
        this.retriedOnInfrastructureFailure = retried;
        this.group = group;
    }

    public String dbName() {
        return dbName;
    }

    public int priority() {
        return priority;
    }

    public Duration timeLimit() {
        return timeLimit;
    }

    public Group group() {
        return group;
    }

    /** One run, plus one retry for the types that are retried. */
    public int maxAttempts() {
        return retriedOnInfrastructureFailure ? 2 : 1;
    }

    public static JobType fromDb(String name) {
        return Arrays.stream(values()).filter(t -> t.dbName.equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown job type: " + name));
    }
}
