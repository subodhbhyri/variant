package com.tailor.web.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailor.web.auth.ApiTestBase;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** P6-T11, running jobs: outcomes, retries, rejections, time limits, heartbeats. */
@ExtendWith(OutputCaptureExtension.class)
class JobProcessorTest extends ApiTestBase {

    @Autowired
    JobRepository repo;
    @Autowired
    JobService service;
    @Autowired
    JobProcessor processor;
    @Autowired
    ObjectMapper json;
    @Autowired
    List<JobHandler> handlers;

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

    private Job enqueue(UUID user, JobType type, Map<String, Object> payload) {
        return service.enqueue(user, type, payload, null);
    }

    private Job reload(UUID id) {
        return repo.find(id).orElseThrow();
    }

    @Test
    void aSuccessfulJobStoresItsResult() {
        Job job = enqueue(user(), JobType.MATCH, Map.of("behavior", "ok", "value", "hello"));

        assertThat(processor.runNext()).isTrue();

        Job done = reload(job.id());
        assertThat(done.status()).isEqualTo(Job.Status.SUCCEEDED);
        assertThat(done.result().path("echo").asText()).isEqualTo("hello");
        assertThat(done.errorCode()).isNull();
        assertThat(done.finishedAt()).isNotNull();
        assertThat(done.rejected()).isFalse();
        assertThat(processor.runNext()).as("nothing left to run").isFalse();
    }

    @Test
    void progressIsNamedStagesAndRealCountsOnly() throws Exception {
        Job job = enqueue(user(), JobType.GENERATE, Map.of("behavior", "progress"));

        processor.runNext();

        assertThat(reload(job.id()).progress())
                .isEqualTo(json.readTree("{\"stage\":\"generating\",\"step\":2,\"of\":6}"));
    }

    @Test
    void anEngineRejectionIsAResultAndNeverRetried() {
        Job job = enqueue(user(), JobType.ONBOARD, Map.of("behavior", "reject"));

        processor.runNext();

        Job done = reload(job.id());
        assertThat(done.status()).isEqualTo(Job.Status.FAILED);
        assertThat(done.rejected()).isTrue();
        assertThat(done.errorCode()).isEqualTo("NEEDS_USER");
        assertThat(done.result().path("details").path("options").get(0).asText()).isEqualTo("SUBSTITUTE_FONTS");
        assertThat(done.attempts()).isEqualTo(1);
        assertThat(processor.runNext()).isFalse();
        assertThat(JobTestHandlers.runs(job.id())).isEqualTo(1);
    }

    @Test
    void anInfrastructureFailureIsRetriedOnceForOnboard() {
        Job job = enqueue(user(), JobType.ONBOARD, Map.of("behavior", "boom"));

        assertThat(processor.runNext()).isTrue();
        Job afterFirst = reload(job.id());
        assertThat(afterFirst.status()).isEqualTo(Job.Status.QUEUED);
        assertThat(afterFirst.attempts()).isEqualTo(1);

        assertThat(processor.runNext()).isTrue();
        Job done = reload(job.id());
        assertThat(done.status()).isEqualTo(Job.Status.FAILED);
        assertThat(done.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(done.rejected()).isFalse();
        assertThat(done.attempts()).isEqualTo(2);
        assertThat(processor.runNext()).isFalse();
        assertThat(JobTestHandlers.runs(job.id())).isEqualTo(2);
    }

    @Test
    void generateIsNeverRetriedAutomatically() {
        Job job = enqueue(user(), JobType.GENERATE, Map.of("behavior", "boom"));

        processor.runNext();

        Job done = reload(job.id());
        assertThat(done.status()).isEqualTo(Job.Status.FAILED);
        assertThat(done.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(done.attempts()).isEqualTo(1);
        assertThat(processor.runNext()).isFalse();
        assertThat(JobTestHandlers.runs(job.id())).isEqualTo(1);
    }

    @Test
    void failureNeverPutsHandlerMessagesInLogsOrTheJobRow(CapturedOutput output) {
        Job job = enqueue(user(), JobType.GENERATE, Map.of("behavior", "boom"));

        processor.runNext();

        assertThat(output.getAll()).doesNotContain(JobTestHandlers.SECRET);
        assertThat(reload(job.id()).toString()).doesNotContain(JobTestHandlers.SECRET);
    }

    @Test
    void aJobOverItsTimeLimitIsStoppedAndRetriedOnce() {
        // Limits shrunk to 0.5% (onboard: 180 s -> 0.9 s) so the test is quick.
        JobProcessor quick = new JobProcessor(repo, handlers,
                new JobProperties(Duration.ofSeconds(60), Duration.ofMillis(50), 1, 0.005), json);
        Job job = enqueue(user(), JobType.ONBOARD, Map.of("behavior", "slow", "ms", 20_000));

        long started = System.nanoTime();
        quick.runNext();
        assertThat(reload(job.id()).status()).isEqualTo(Job.Status.QUEUED);
        quick.runNext();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        Job done = reload(job.id());
        assertThat(done.status()).isEqualTo(Job.Status.FAILED);
        assertThat(done.errorCode()).isEqualTo("JOB_TIMEOUT");
        assertThat(done.attempts()).isEqualTo(2);
        assertThat(elapsedMs).as("the slow handler was interrupted, not waited for").isLessThan(10_000);
    }

    @Test
    void aTimedOutGenerateIsNotRetried() {
        JobProcessor quick = new JobProcessor(repo, handlers,
                new JobProperties(Duration.ofSeconds(60), Duration.ofMillis(50), 1, 0.001), json);
        Job job = enqueue(user(), JobType.GENERATE, Map.of("behavior", "slow", "ms", 20_000));

        quick.runNext(); // 10 min x 0.001 = 0.6 s

        Job done = reload(job.id());
        assertThat(done.status()).isEqualTo(Job.Status.FAILED);
        assertThat(done.errorCode()).isEqualTo("JOB_TIMEOUT");
        assertThat(done.attempts()).isEqualTo(1);
    }

    @Test
    void theHeartbeatKeepsALongJobAliveWhileOthersReapExpiredLeases() throws Exception {
        // Lease 900 ms, heartbeat every 300 ms; the job takes 2.5 s of real time.
        JobProcessor beating = new JobProcessor(repo, handlers,
                new JobProperties(Duration.ofMillis(900), Duration.ofMillis(50), 1, 1.0), json);
        Job job = enqueue(user(), JobType.ONBOARD, Map.of("behavior", "slow", "ms", 2500));
        AtomicBoolean reaped = new AtomicBoolean();
        Thread reaper = new Thread(() -> {
            long end = System.currentTimeMillis() + 2300;
            while (System.currentTimeMillis() < end) {
                if (repo.reapExpired() > 0) {
                    reaped.set(true);
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        reaper.start();

        beating.runNext();
        reaper.join();

        assertThat(reaped).as("another worker never saw an expired lease").isFalse();
        Job done = reload(job.id());
        assertThat(done.status()).isEqualTo(Job.Status.SUCCEEDED);
        assertThat(done.attempts()).isEqualTo(1);
    }

    @Test
    void aProcessorWithoutAHandlerNeverClaimsThatType() {
        JobHandler onlyMatch = handlers.stream().filter(h -> h.type() == JobType.MATCH).findFirst().orElseThrow();
        JobProcessor matchOnly = new JobProcessor(repo, List.of(onlyMatch),
                new JobProperties(Duration.ofSeconds(60), Duration.ofMillis(50), 1, 1.0), json);
        Job onboard = enqueue(user(), JobType.ONBOARD, Map.of("behavior", "ok"));

        assertThat(matchOnly.runNext()).isFalse();
        assertThat(reload(onboard.id()).status()).isEqualTo(Job.Status.QUEUED);
    }
}
