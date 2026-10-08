package com.tailor.web.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailor.web.api.ApiException;
import com.tailor.web.auth.ApiTestBase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** P6-T11, the queue itself: priorities, leases, per-user limits, idempotency keys. */
class JobQueueTest extends ApiTestBase {

    private static final Duration LEASE = Duration.ofSeconds(60);

    @Autowired
    JobRepository repo;
    @Autowired
    JobService service;
    @Autowired
    ObjectMapper json;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM jobs");
        JobTestHandlers.reset();
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, uniqueEmail());
        return id;
    }

    private Job enqueue(UUID user, JobType type) {
        return service.enqueue(user, type, Map.of("behavior", "ok"), null);
    }

    private Optional<Job> claim(String worker) {
        return repo.claimNext(worker, List.of(JobType.values()), LEASE);
    }

    private Job reload(UUID id) {
        return repo.find(id).orElseThrow();
    }

    @Test
    void jobsAreClaimedByPriorityThenAge() {
        // Different users, so only priority and age decide. Created oldest-first: generate first.
        Job generate = enqueue(user(), JobType.GENERATE);
        Job onboard = enqueue(user(), JobType.ONBOARD);
        Job alternative = enqueue(user(), JobType.RENDER_ALTERNATIVE);
        Job match = enqueue(user(), JobType.MATCH);
        Job edit = enqueue(user(), JobType.EDIT_REVISION);

        List<UUID> order = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            order.add(claim("w").orElseThrow().id());
        }

        assertThat(order).containsExactly(match.id(), edit.id(), alternative.id(), onboard.id(), generate.id());
        assertThat(claim("w")).isEmpty();
    }

    @Test
    void aClaimIsExclusiveAndCountsTheAttempt() {
        Job job = enqueue(user(), JobType.MATCH);

        Job claimed = claim("worker-a").orElseThrow();

        assertThat(claimed.id()).isEqualTo(job.id());
        assertThat(claimed.status()).isEqualTo(Job.Status.RUNNING);
        assertThat(claimed.attempts()).isEqualTo(1);
        assertThat(claimed.workerId()).isEqualTo("worker-a");
        assertThat(claimed.leaseUntil()).isAfter(claimed.startedAt());
        assertThat(claim("worker-b")).isEmpty();
    }

    @Test
    void onlyTheTypesAWorkerHandlesAreClaimed() {
        Job onboard = enqueue(user(), JobType.ONBOARD);
        assertThat(repo.claimNext("w", List.of(JobType.MATCH), LEASE)).isEmpty();
        assertThat(repo.claimNext("w", List.of(JobType.ONBOARD), LEASE).orElseThrow().id()).isEqualTo(onboard.id());
    }

    @Test
    void anExpiredLeaseRerunsTheJobOnAnotherWorker() {
        Job job = enqueue(user(), JobType.ONBOARD);
        Job first = claim("worker-a").orElseThrow();
        assertThat(first.attempts()).isEqualTo(1);

        // Nothing happens while the lease is valid.
        assertThat(repo.reapExpired()).isZero();
        assertThat(claim("worker-b")).isEmpty();

        clock.advance(LEASE.plusSeconds(1)); // worker-a has crashed: no heartbeat
        assertThat(repo.reapExpired()).isEqualTo(1);
        assertThat(reload(job.id()).status()).isEqualTo(Job.Status.QUEUED);

        Job second = claim("worker-b").orElseThrow();
        assertThat(second.id()).isEqualTo(job.id());
        assertThat(second.attempts()).isEqualTo(2);

        // The crashed worker wakes up and tries to report: it no longer owns the job.
        JsonNode stale = json.valueToTree(Map.of("echo", "stale"));
        assertThat(repo.succeed(job.id(), "worker-a", 1, stale)).isFalse();
        assertThat(repo.setProgress(job.id(), "worker-a", 1, stale)).isFalse();
        assertThat(repo.extendLease(job.id(), "worker-a", 1, LEASE)).isFalse();

        assertThat(repo.succeed(job.id(), "worker-b", 2, json.valueToTree(Map.of("echo", "fresh")))).isTrue();
        Job done = reload(job.id());
        assertThat(done.status()).isEqualTo(Job.Status.SUCCEEDED);
        assertThat(done.result().path("echo").asText()).isEqualTo("fresh");
    }

    @Test
    void aSecondLostLeaseFailsTheJobInsteadOfLoopingForever() {
        Job job = enqueue(user(), JobType.ONBOARD);
        claim("w1");
        clock.advance(LEASE.plusSeconds(1));
        repo.reapExpired();
        claim("w2");
        clock.advance(LEASE.plusSeconds(1));
        assertThat(repo.reapExpired()).isEqualTo(1);

        Job done = reload(job.id());
        assertThat(done.status()).isEqualTo(Job.Status.FAILED);
        assertThat(done.errorCode()).isEqualTo("WORKER_LOST");
        assertThat(done.attempts()).isEqualTo(2);
    }

    @Test
    void aGenerateJobIsNeverRerunAutomaticallyAfterALostWorker() {
        Job job = enqueue(user(), JobType.GENERATE);
        claim("w1");
        clock.advance(LEASE.plusSeconds(1));

        assertThat(repo.reapExpired()).isEqualTo(1);

        Job done = reload(job.id());
        assertThat(done.status()).isEqualTo(Job.Status.FAILED);
        assertThat(done.errorCode()).isEqualTo("WORKER_LOST");
        assertThat(done.attempts()).isEqualTo(1);
        assertThat(claim("w2")).isEmpty();
    }

    @Test
    void aHeartbeatKeepsTheLeaseAlive() {
        Job job = enqueue(user(), JobType.ONBOARD);
        Job claimed = claim("w1").orElseThrow();

        clock.advance(Duration.ofSeconds(50));
        assertThat(repo.extendLease(job.id(), "w1", claimed.attempts(), LEASE)).isTrue();
        clock.advance(Duration.ofSeconds(50)); // 100 s after the claim, 50 s after the heartbeat

        assertThat(repo.reapExpired()).isZero();
        assertThat(reload(job.id()).status()).isEqualTo(Job.Status.RUNNING);
    }

    @Test
    void perUserLimitsHold() {
        UUID u = user();
        UUID other = user();
        Job onboard = enqueue(u, JobType.ONBOARD);
        Job generate = enqueue(u, JobType.GENERATE);
        Job m1 = enqueue(u, JobType.MATCH);
        Job m2 = enqueue(u, JobType.MATCH);
        Job m3 = enqueue(u, JobType.MATCH);
        Job otherGenerate = enqueue(other, JobType.GENERATE);

        // Priority order: matches first, but only two at once for this user; then ONE heavy job
        // (onboard before generate); the other user is unaffected.
        List<UUID> claimed = new ArrayList<>();
        claim("w").ifPresent(j -> claimed.add(j.id()));
        claim("w").ifPresent(j -> claimed.add(j.id()));
        claim("w").ifPresent(j -> claimed.add(j.id()));
        claim("w").ifPresent(j -> claimed.add(j.id()));
        assertThat(claimed).containsExactly(m1.id(), m2.id(), onboard.id(), otherGenerate.id());
        assertThat(claim("w")).as("m3 waits for a match slot, generate for the heavy slot").isEmpty();

        repo.succeed(m1.id(), "w", 1, json.createObjectNode());
        assertThat(claim("w").orElseThrow().id()).isEqualTo(m3.id());
        assertThat(claim("w")).isEmpty();

        repo.succeed(onboard.id(), "w", 1, json.createObjectNode());
        assertThat(claim("w").orElseThrow().id()).isEqualTo(generate.id());
    }

    @Test
    void oneHeavyJobAtATimeCoversOnboardAndGenerateTogether() {
        UUID u = user();
        enqueue(u, JobType.GENERATE);
        enqueue(u, JobType.ONBOARD);

        Job first = claim("w").orElseThrow();

        assertThat(first.type()).isEqualTo(JobType.ONBOARD);
        assertThat(claim("w")).isEmpty();
    }

    @Test
    void theLimitHoldsWhenManyWorkersClaimAtOnce() throws Exception {
        UUID u = user();
        for (int i = 0; i < 10; i++) {
            enqueue(u, JobType.MATCH);
        }
        Set<UUID> claimed = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < 8; i++) {
            String worker = "w" + i;
            pool.submit(() -> {
                go.await();
                for (int attempt = 0; attempt < 5; attempt++) {
                    claim(worker).ifPresent(j -> claimed.add(j.id()));
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(claimed).as("two matches at most, however many workers race").hasSize(2);
        assertThat(count("SELECT count(*) FROM jobs WHERE user_id = ? AND status = 'running'", u)).isEqualTo(2);
    }

    @Test
    void everyJobIsClaimedExactlyOnceUnderContention() throws Exception {
        Set<UUID> expected = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            expected.add(enqueue(user(), JobType.values()[i % 5]).id());
        }
        List<UUID> claimed = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 8; i++) {
            String worker = "w" + i;
            pool.submit(() -> {
                Optional<Job> job;
                while ((job = claim(worker)).isPresent()) {
                    claimed.add(job.get().id());
                }
                return null;
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(claimed).hasSize(40).doesNotHaveDuplicates();
        assertThat(new HashSet<>(claimed)).isEqualTo(expected);
    }

    @Test
    void theSameIdempotencyKeyReturnsTheSameJob() {
        UUID u = user();
        Job first = service.enqueue(u, JobType.GENERATE, Map.of("behavior", "ok"), "key-1");
        Job again = service.enqueue(u, JobType.GENERATE, Map.of("behavior", "ok"), "key-1");

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(count("SELECT count(*) FROM jobs WHERE user_id = ?", u)).isEqualTo(1);

        // Another key, or another user with the same key, is a different job.
        assertThat(service.enqueue(u, JobType.GENERATE, Map.of("behavior", "ok"), "key-2").id()).isNotEqualTo(first.id());
        assertThat(service.enqueue(user(), JobType.GENERATE, Map.of("behavior", "ok"), "key-1").id()).isNotEqualTo(first.id());
        // No key: always new.
        assertThat(enqueue(u, JobType.GENERATE).id()).isNotEqualTo(enqueue(u, JobType.GENERATE).id());
    }

    @Test
    void anIdempotencyKeyIsOnlyRememberedFor24Hours() {
        UUID u = user();
        Job first = service.enqueue(u, JobType.MATCH, Map.of("behavior", "ok"), "daily");

        clock.advance(Duration.ofHours(23));
        assertThat(service.enqueue(u, JobType.MATCH, Map.of("behavior", "ok"), "daily").id()).isEqualTo(first.id());

        clock.advance(Duration.ofHours(2)); // now 25 h after the first
        Job later = service.enqueue(u, JobType.MATCH, Map.of("behavior", "ok"), "daily");
        assertThat(later.id()).isNotEqualTo(first.id());
        assertThat(service.enqueue(u, JobType.MATCH, Map.of("behavior", "ok"), "daily").id()).isEqualTo(later.id());
    }

    @Test
    void anOverlongIdempotencyKeyIsRefused() {
        assertThatThrownBy(() -> service.enqueue(user(), JobType.MATCH, Map.of(), "k".repeat(201)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void jobsOfOneUserAreInvisibleToAnother() {
        UUID owner = user();
        Job job = enqueue(owner, JobType.MATCH);

        assertThat(repo.findForUser(job.id(), owner)).isPresent();
        assertThat(repo.findForUser(job.id(), user())).isEmpty();
        assertThatThrownBy(() -> service.getForUser(job.id(), user())).isInstanceOf(ApiException.class);
    }
}
