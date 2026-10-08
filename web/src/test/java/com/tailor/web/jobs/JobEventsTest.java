package com.tailor.web.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailor.web.auth.ApiTestBase;
import com.tailor.web.auth.TestBrowser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PHASE6_SPEC.md revision 3, real progress events: what the engine reports is stored with the job exactly as it happened
 * (nothing invented, nothing held back), with timings, and a finished job can be replayed, over GET and over the stream.
 */
class JobEventsTest extends ApiTestBase {

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

    private List<String> summary(JsonNode events) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : events) {
            out.add(e.path("stage").asText() + " " + e.path("state").asText());
        }
        return out;
    }

    @Test
    void theEventsAreStoredInTheOrderTheyHappenedWithTheirTimings() throws Exception {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        Job job = service.enqueue(idOf(email), JobType.MATCH, Map.of("behavior", "events"), null);

        assertThat(processor.runNext()).isTrue();

        JsonNode view = json.readTree(b.get("/jobs/" + job.id()).body());
        JsonNode events = view.path("events");
        assertThat(summary(events)).containsExactly("alpha started", "alpha progress", "alpha finished", "beta started",
                "beta finished");
        long previous = -1;
        for (int i = 0; i < events.size(); i++) {
            JsonNode e = events.get(i);
            assertThat(e.path("seq").asInt()).as("seq is 1, 2, 3 ...").isEqualTo(i + 1);
            assertThat(e.path("at_ms").asLong()).as("time since the run began never goes back").isGreaterThanOrEqualTo(previous);
            previous = e.path("at_ms").asLong();
        }
        // Details are what the engine said, and only that.
        assertThat(events.get(0).path("detail").path("n").asInt()).isEqualTo(1);
        assertThat(events.get(1).path("detail").path("position").asText()).isEqualTo("P0");
        assertThat(events.get(1).path("detail").path("project").asText()).isEqualTo("quill");
        assertThat(events.get(2).path("detail").path("found").asInt()).isEqualTo(2);
        assertThat(events.get(2).path("detail").path("total").asInt()).isEqualTo(3);
        // A finished stage says how long it took: at least the time the handler spent inside it, and no more than the run.
        assertThat(events.get(2).path("duration_ms").asLong()).isBetween(35L, events.get(2).path("at_ms").asLong() + 1);
        assertThat(events.get(0).has("duration_ms")).as("only a finished stage has a duration").isFalse();
        assertThat(events.get(4).path("duration_ms").asLong()).isGreaterThanOrEqualTo(0);
        // The legacy single-stage call is an event pair too, closed when the job ends; the coarse progress follows the last start.
        assertThat(view.path("progress").path("stage").asText()).isEqualTo("beta");
        assertThat(view.path("status").asText()).isEqualTo("succeeded");
    }

    @Test
    void aJobThatDidNotReportAnythingHasNoEvents() throws Exception {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        Job job = service.enqueue(idOf(email), JobType.MATCH, Map.of("behavior", "ok"), null);
        processor.runNext();

        assertThat(json.readTree(b.get("/jobs/" + job.id()).body()).path("events")).isEmpty();
    }

    @Test
    void aFinishedJobIsReplayedOverTheStreamAndResumesFromTheLastEventId() throws Exception {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        Job job = service.enqueue(idOf(email), JobType.MATCH, Map.of("behavior", "events"), null);
        processor.runNext();

        List<String> lines = b.streamLines("/jobs/" + job.id() + "/events", Duration.ofSeconds(10));
        List<String> ids = lines.stream().filter(l -> l.startsWith("id:")).map(l -> l.substring(3).trim()).toList();
        List<String> names = lines.stream().filter(l -> l.startsWith("event:")).map(l -> l.substring(6).trim()).toList();
        assertThat(ids).containsExactly("1", "2", "3", "4", "5");
        assertThat(names).containsExactly("progress", "progress", "progress", "progress", "progress", "job");
        List<String> data = lines.stream().filter(l -> l.startsWith("data:")).map(l -> l.substring(5).trim()).toList();
        assertThat(json.readTree(data.get(2)).path("stage").asText()).isEqualTo("alpha");
        assertThat(json.readTree(data.get(2)).path("state").asText()).isEqualTo("finished");
        JsonNode last = json.readTree(data.get(5));
        assertThat(last.path("status").asText()).isEqualTo("succeeded");
        assertThat(last.has("events") && !last.path("events").isNull()).as("the status event does not repeat the list").isFalse();

        // Reconnecting after event 3 sends only what came after.
        List<String> rest = b.streamLines("/jobs/" + job.id() + "/events", Duration.ofSeconds(10), "Last-Event-ID", "3");
        assertThat(rest.stream().filter(l -> l.startsWith("id:")).map(l -> l.substring(3).trim()).toList())
                .containsExactly("4", "5");
    }

    @Test
    void aRetriedRunStartsItsEventsAgain() throws Exception {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        Job job = service.enqueue(idOf(email), JobType.ONBOARD, Map.of("behavior", "events-then-boom-once"), null);

        processor.runNext(); // fails after reporting a stage; one retry is allowed for onboarding
        assertThat(json.readTree(b.get("/jobs/" + job.id()).body()).path("events")).as("a queued job shows no events").isEmpty();
        processor.runNext();

        JsonNode events = json.readTree(b.get("/jobs/" + job.id()).body()).path("events");
        assertThat(summary(events)).containsExactly("second-attempt started", "second-attempt finished");
        assertThat(events.get(0).path("seq").asInt()).isEqualTo(1);
        assertThat(events.toString()).doesNotContain(JobTestHandlers.SECRET);
    }

    @Test
    void anotherUsersJobEventsAreA404() throws Exception {
        String ownerEmail = uniqueEmail();
        signedInBrowser(ownerEmail);
        Job job = service.enqueue(idOf(ownerEmail), JobType.MATCH, Map.of("behavior", "events"), null);
        processor.runNext();
        TestBrowser stranger = signedInBrowser(uniqueEmail());

        assertThat(stranger.get("/jobs/" + job.id()).status()).isEqualTo(404);
        assertThat(stranger.get("/jobs/" + job.id() + "/events").status()).isEqualTo(404);
    }
}
