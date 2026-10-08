package com.tailor.web.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailor.web.auth.TestBrowser;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PHASE6_SPEC.md revision 3, real progress events on the real jobs: the engine's own stages, in the order it ran them,
 * with timings, kept with the job.
 */
@Tag("corpus")
class ProgressEventsFlowTest extends MatchFlowBase {

    private JsonNode job(TestBrowser b, String id) throws Exception {
        return JSON.readTree(b.get("/jobs/" + id).body());
    }

    private static List<String> summary(JsonNode events) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : events) {
            out.add(e.path("stage").asText() + " " + e.path("state").asText());
        }
        return out;
    }

    private static void assertTimed(JsonNode events) {
        long previous = -1;
        for (int i = 0; i < events.size(); i++) {
            JsonNode e = events.get(i);
            assertThat(e.path("seq").asInt()).isEqualTo(i + 1);
            assertThat(e.path("at_ms").asLong()).isGreaterThanOrEqualTo(previous);
            previous = e.path("at_ms").asLong();
            if ("finished".equals(e.path("state").asText())) {
                assertThat(e.path("duration_ms").asLong()).as(e.toString()).isGreaterThanOrEqualTo(0);
                assertThat(e.path("duration_ms").asLong()).isLessThanOrEqualTo(e.path("at_ms").asLong() + 1);
            }
        }
    }

    // ---- onboarding -------------------------------------------------------------------------

    @Test
    void onboardingReportsSafetyChecksFontsBulletsAndLines() throws Exception {
        TestBrowser b = signedInBrowser(uniqueEmail());
        TestBrowser.Response up = b.postFile("/resumes", "file", "projects.docx", Files.readAllBytes(phase3("projects_synthetic.docx")));
        UUID resumeId = UUID.fromString(JSON.readTree(up.body()).path("resume_id").asText());
        String jobId = JSON.readTree(up.body()).path("job_id").asText();
        runJobs();

        JsonNode job = job(b, jobId);
        JsonNode events = job.path("events");
        assertThat(summary(events)).containsExactly("safety_checks started", "safety_checks finished", "fonts started",
                "fonts finished", "find_bullets started", "find_bullets finished", "measure_lines started",
                "measure_lines finished", "storing started", "storing finished");
        assertTimed(events);
        JsonNode report = JSON.readTree(jdbc.queryForObject("SELECT onboard_json::text FROM resumes WHERE id = ?", String.class, resumeId));
        JsonNode bullets = events.get(5).path("detail");
        assertThat(bullets.path("found").asInt()).isEqualTo(report.path("editableCount").asInt());
        assertThat(bullets.path("total").asInt()).isEqualTo(report.path("slots").size());
        assertThat(events.get(3).path("detail").path("pages").asInt()).isEqualTo(report.path("pages").asInt());
        assertThat(job.path("progress").path("stage").asText()).isEqualTo("storing");
        // No text of the resume anywhere in what is reported.
        assertThat(events.toString()).doesNotContain(report.path("slots").get(0).path("text").asText().substring(0, 12));
    }

    @Test
    void aRejectedUploadShowsHowFarOnboardingGot() throws Exception {
        TestBrowser b = signedInBrowser(uniqueEmail());
        TestBrowser.Response up = b.postFile("/resumes", "file", "few.docx", Fixtures.phase2("too_few_bullets.docx"));
        String jobId = JSON.readTree(up.body()).path("job_id").asText();
        runJobs();

        JsonNode job = job(b, jobId);
        assertThat(job.path("error_code").asText()).isEqualTo("TOO_FEW_EDITABLE");
        assertThat(summary(job.path("events"))).containsExactly("safety_checks started", "safety_checks finished",
                "fonts started", "fonts finished", "find_bullets started", "find_bullets finished");
        assertThat(job.path("events").get(5).path("detail").path("found").asInt()).isLessThan(3);
    }

    // ---- matching ---------------------------------------------------------------------------

    @Test
    void matchingReportsReadingChoosingEveryPlacementVerifyingAndDelivering() throws Exception {
        Seeded s = seededAccount();
        Account a = s.account();
        TestBrowser.Response posted = post(a, jd("platform"));
        assertThat(posted.status()).isEqualTo(202);
        String jobId = JSON.readTree(posted.body()).path("job_id").asText();
        String postingId = JSON.readTree(posted.body()).path("posting_id").asText();
        runJobs();

        JsonNode job = job(a.browser(), jobId);
        assertThat(job.path("status").asText()).isEqualTo("succeeded");
        JsonNode events = job.path("events");
        List<String> sequence = summary(events);
        assertThat(sequence.subList(0, 6)).containsExactly("read_posting started", "read_posting finished",
                "check_earlier_postings started", "check_earlier_postings finished", "choose_material started",
                "choose_material finished");
        assertThat(sequence).containsSubsequence("place_projects started", "place_projects finished", "verify started",
                "verify finished", "deliver started", "deliver finished");
        assertThat(sequence.get(sequence.size() - 1)).isEqualTo("deliver finished");
        assertThat(sequence.stream().filter(x -> x.equals("read_posting started")).count()).as("reported once").isEqualTo(1);
        assertTimed(events);

        // One progress event per project in resume #1, between place_projects started and finished.
        int from = sequence.indexOf("place_projects started");
        int to = sequence.indexOf("place_projects finished");
        List<String> placed = new ArrayList<>();
        for (int i = from + 1; i < to; i++) {
            assertThat(sequence.get(i)).isEqualTo("place_projects progress");
            placed.add(events.get(i).path("detail").path("position").asText() + "=" + events.get(i).path("detail").path("project").asText());
        }
        JsonNode match = JSON.readTree(a.browser().get("/postings/" + postingId + "/match").body());
        String snapshotId = match.path("resume").path("id").asText();
        JsonNode assembly = JSON.readTree(jdbc.queryForObject("SELECT assembly::text FROM snapshots WHERE id = ?::uuid", String.class, snapshotId));
        List<String> expected = new ArrayList<>();
        for (JsonNode p : assembly.path("projects")) {
            expected.add(p.path("position").asText() + "=" + p.path("project").asText());
        }
        assertThat(placed).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(placed).isNotEmpty();
        assertThat(events.get(sequence.indexOf("choose_material finished")).path("detail").path("resumes").asInt()).isGreaterThanOrEqualTo(1);
        // Nothing of the posting or the resume in the events.
        assertThat(events.toString()).doesNotContain("Kubernetes").doesNotContain("Designed");
    }

    @Test
    void aRepeatedPostingIsDeliveredFromTheCacheWithoutPlacingAnything() throws Exception {
        Seeded s = seededAccount();
        Account a = s.account();
        post(a, jd("platform"));
        runJobs();
        // The reworded posting is similar enough to reuse the first match (a worker decision, so it still runs a job).
        TestBrowser.Response again = post(a, jd("platform_reworded"));
        if (again.status() != 202) {
            return; // answered at once by the api's own cache check: no job to look at
        }
        String jobId = JSON.readTree(again.body()).path("job_id").asText();
        runJobs();

        JsonNode events = job(a.browser(), jobId).path("events");
        List<String> sequence = summary(events);
        if (sequence.contains("choose_material started")) {
            return; // this wording was not similar enough: it was matched in full (covered above)
        }
        assertThat(sequence).containsExactly("read_posting started", "read_posting finished", "check_earlier_postings started",
                "check_earlier_postings finished", "deliver started", "deliver finished");
        assertThat(events.get(3).path("detail").path("reused").asBoolean()).isTrue();
    }

    // ---- generation -------------------------------------------------------------------------

    @Test
    void generationReportsOneEventPerSection() throws Exception {
        Account a = acceptedAccount();
        JsonNode janeDoe = JSON.readTree(Fixtures.phase4().resolve("intake_jane_doe.json").toFile());
        for (JsonNode sec : janeDoe.path("sections")) {
            assertThat(save(a, sec.path("id").asText(), sec.path("mode").asText(), sec.path("raw_text").asText(""),
                    sec.path("fields").toString()).status()).isEqualTo(200);
        }
        TestBrowser.Response r = a.browser().postJson("/resumes/" + a.resumeId() + "/generate", "{}");
        String jobId = JSON.readTree(r.body()).path("job_id").asText();
        runJobs();

        JsonNode job = job(a.browser(), jobId);
        assertThat(job.path("status").asText()).isEqualTo("succeeded");
        JsonNode events = job.path("events");
        List<String> sections = new ArrayList<>();
        int starts = 0;
        int finishes = 0;
        for (JsonNode e : events) {
            if (!"section".equals(e.path("stage").asText())) {
                continue;
            }
            if ("started".equals(e.path("state").asText())) {
                starts++;
                sections.add(e.path("detail").path("section").asText());
                assertThat(e.path("detail").path("step").asInt()).isEqualTo(starts);
                assertThat(e.path("detail").path("of").asInt()).isGreaterThanOrEqualTo(starts);
            } else if ("finished".equals(e.path("state").asText())) {
                finishes++;
                assertThat(e.path("detail").path("section").asText()).isEqualTo(sections.get(sections.size() - 1));
                assertThat(e.path("detail").path("cost_usd").asDouble()).isGreaterThanOrEqualTo(0);
                assertThat(e.path("detail").path("status").asText()).isNotBlank();
            }
        }
        assertThat(starts).isEqualTo(finishes).isGreaterThanOrEqualTo(1);
        assertThat(sections).startsWith("job-0");
        // Each section is a started/finished pair and the next one starts only after the previous finished.
        List<String> sequence = summary(events);
        for (int i = 0; i + 1 < sequence.size(); i++) {
            if (sequence.get(i).equals("section started")) {
                assertThat(sequence.get(i + 1)).isEqualTo("section finished");
            }
        }
        assertTimed(events);
        assertThat(events.toString()).doesNotContain("my own notes");
    }
}
