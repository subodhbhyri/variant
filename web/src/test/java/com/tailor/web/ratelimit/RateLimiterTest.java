package com.tailor.web.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailor.web.auth.ApiTestBase;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The limiter behind every limit in PHASE6_SPEC.md (email links, slot checks, postings): sliding window, shared by all api tasks. */
class RateLimiterTest extends ApiTestBase {

    @Autowired
    RateLimiter limiter;

    private String subject() {
        return UUID.randomUUID().toString();
    }

    @Test
    void allowsExactlyTheLimitWithinTheWindow() {
        String who = subject();
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("t", who, 5, Duration.ofHours(1))).as("event " + i).isTrue();
        }
        assertThat(limiter.tryAcquire("t", who, 5, Duration.ofHours(1))).isFalse();
        assertThat(limiter.tryAcquire("t", who, 5, Duration.ofHours(1))).as("a refusal is not counted").isFalse();
    }

    @Test
    void theWindowSlides() {
        String who = subject();
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("t", who, 3, Duration.ofMinutes(1));
        }
        assertThat(limiter.tryAcquire("t", who, 3, Duration.ofMinutes(1))).isFalse();

        clock.advance(Duration.ofSeconds(61));

        assertThat(limiter.tryAcquire("t", who, 3, Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void bucketsAndSubjectsAreIndependent() {
        String a = subject();
        String b = subject();
        for (int i = 0; i < 2; i++) {
            limiter.tryAcquire("one", a, 2, Duration.ofHours(1));
        }
        assertThat(limiter.tryAcquire("one", a, 2, Duration.ofHours(1))).isFalse();
        assertThat(limiter.tryAcquire("one", b, 2, Duration.ofHours(1))).isTrue();
        assertThat(limiter.tryAcquire("two", a, 2, Duration.ofHours(1))).isTrue();
    }

    @Test
    void theLimitHoldsWhenManyCallersRaceForTheSameKey() throws Exception {
        String who = subject();
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        for (int i = 0; i < 64; i++) {
            pool.submit(() -> {
                if (limiter.tryAcquire("race", who, 10, Duration.ofHours(1))) {
                    allowed.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(allowed).as("however many tasks race, exactly the limit gets through").hasValue(10);
    }

    @Test
    void theLimiterStoresOnlyAHashedOrIdSubject() {
        // Callers pass ids or hashes, never an address or text (PHASE6_SPEC.md section 9.3).
        assertThat(count("SELECT count(*) FROM rate_events WHERE subject LIKE '%@%'")).isZero();
    }
}
