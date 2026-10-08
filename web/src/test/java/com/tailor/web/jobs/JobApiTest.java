package com.tailor.web.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailor.web.auth.ApiTestBase;
import com.tailor.web.auth.TestBrowser;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** GET /jobs/{id} and the SSE stream (PHASE6_SPEC.md section 4.6), including owner isolation. */
class JobApiTest extends ApiTestBase {

    @Autowired
    JobService service;
    @Autowired
    JobProcessor processor;
    @Autowired
    ObjectMapper json;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM jobs");
        JobTestHandlers.reset();
    }

    private UUID idOf(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    @Test
    void theOwnerSeesTheJobAndAnotherUserGets404() throws Exception {
        String ownerEmail = uniqueEmail();
        TestBrowser owner = signedInBrowser(ownerEmail);
        TestBrowser stranger = signedInBrowser(uniqueEmail());
        Job job = service.enqueue(idOf(ownerEmail), JobType.MATCH, Map.of("behavior", "ok", "value", "v"), null);

        TestBrowser.Response mine = owner.get("/jobs/" + job.id());
        assertThat(mine.status()).isEqualTo(200);
        JsonNode body = json.readTree(mine.body());
        assertThat(body.path("id").asText()).isEqualTo(job.id().toString());
        assertThat(body.path("type").asText()).isEqualTo("match");
        assertThat(body.path("status").asText()).isEqualTo("queued");
        assertThat(body.path("rejected").asBoolean()).isFalse();

        TestBrowser.Response theirs = stranger.get("/jobs/" + job.id());
        assertThat(theirs.status()).isEqualTo(404);
        assertThat(theirs.body()).contains("\"code\":\"NOT_FOUND\"");
        // The same answer as for an id that never existed: nothing to probe.
        TestBrowser.Response missing = stranger.get("/jobs/" + UUID.randomUUID());
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body()).isEqualTo(theirs.body());

        assertThat(browser().get("/jobs/" + job.id()).status()).isEqualTo(401);
    }

    @Test
    void aRejectedJobShowsItsCodeAndDetails() throws Exception {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        Job job = service.enqueue(idOf(email), JobType.ONBOARD, Map.of("behavior", "reject"), null);
        processor.runNext();

        JsonNode body = json.readTree(b.get("/jobs/" + job.id()).body());

        assertThat(body.path("status").asText()).isEqualTo("failed");
        assertThat(body.path("rejected").asBoolean()).isTrue();
        assertThat(body.path("error_code").asText()).isEqualTo("NEEDS_USER");
        assertThat(body.path("result").path("details").path("options").size()).isEqualTo(2);
    }

    @Test
    void theEventStreamFollowsAJobToItsEnd() throws Exception {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        Job job = service.enqueue(idOf(email), JobType.GENERATE, Map.of("behavior", "hold", "key", "sse-1"), null);

        CompletableFuture<List<String>> events = CompletableFuture.supplyAsync(
                () -> b.events("/jobs/" + job.id() + "/events", Duration.ofSeconds(30)));
        Thread.sleep(800); // the stream sees "queued" first
        CompletableFuture<Boolean> ran = CompletableFuture.supplyAsync(processor::runNext);
        Thread.sleep(800); // ... then "running"
        JobTestHandlers.release("sse-1");
        assertThat(ran.get(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        List<String> received = events.get(20, java.util.concurrent.TimeUnit.SECONDS);
        List<String> statuses = received.stream().map(e -> {
            try {
                return json.readTree(e).path("status").asText();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }).toList();
        assertThat(statuses).first().isEqualTo("queued");
        assertThat(statuses).contains("running");
        assertThat(statuses).last().isEqualTo("succeeded");
        assertThat(statuses).doesNotHaveDuplicates().as("each change is sent once, in order").isNotEmpty();
    }

    @Test
    void streamingAnotherUsersJobIs404AndOpensNothing() {
        String ownerEmail = uniqueEmail();
        signedInBrowser(ownerEmail);
        Job job = service.enqueue(idOf(ownerEmail), JobType.MATCH, Map.of("behavior", "ok"), null);
        TestBrowser stranger = signedInBrowser(uniqueEmail());

        assertThat(stranger.get("/jobs/" + job.id() + "/events").status()).isEqualTo(404);
    }

    @Test
    void aFinishedJobStreamsOnceAndCloses() {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        Job job = service.enqueue(idOf(email), JobType.MATCH, Map.of("behavior", "ok", "value", "x"), null);
        processor.runNext();

        List<String> received = b.events("/jobs/" + job.id() + "/events", Duration.ofSeconds(10));

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).contains("\"status\":\"succeeded\"");
    }

    @Test
    void aUserCannotHoldMoreThanFiveStreamsOpen() throws Exception {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        Job job = service.enqueue(idOf(email), JobType.GENERATE, Map.of("behavior", "hold", "key", "sse-limit"), null);
        List<CompletableFuture<List<String>>> open = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            open.add(CompletableFuture.supplyAsync(() -> b.events("/jobs/" + job.id() + "/events", Duration.ofSeconds(30))));
        }
        Thread.sleep(1500);

        TestBrowser.Response sixth = b.get("/jobs/" + job.id() + "/events");
        assertThat(sixth.status()).isEqualTo(429);
        assertThat(sixth.body()).contains("TOO_MANY_STREAMS");

        // Finish the job so the five streams close.
        CompletableFuture<Boolean> ran = CompletableFuture.supplyAsync(processor::runNext);
        Thread.sleep(500);
        JobTestHandlers.release("sse-limit");
        ran.get(20, java.util.concurrent.TimeUnit.SECONDS);
        for (CompletableFuture<List<String>> f : open) {
            assertThat(f.get(20, java.util.concurrent.TimeUnit.SECONDS)).isNotEmpty();
        }
    }
}
