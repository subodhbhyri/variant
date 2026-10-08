package com.tailor.web.jobs;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The {@code app.jobs.*} settings. {@code lease}: how long a claim lasts without a heartbeat (an
 * expired lease means the worker is gone). {@code pollInterval}: how long an idle worker thread
 * waits before looking for work again. {@code concurrency}: jobs one worker process runs at once.
 * {@code timeLimitScale}: multiplies every job type's time limit (tests shrink it).
 */
@ConfigurationProperties(prefix = "app.jobs")
public record JobProperties(
        @DefaultValue("60s") Duration lease,
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("2") int concurrency,
        @DefaultValue("1.0") double timeLimitScale) {

    public Duration heartbeat() {
        return lease.dividedBy(3);
    }
}
