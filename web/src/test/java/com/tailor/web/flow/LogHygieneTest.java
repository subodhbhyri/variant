package com.tailor.web.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailor.web.auth.TestBrowser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * P6-T10 (PHASE6_SPEC.md sections 9.3 and 12): a full fixture run, successes and failures, leaves none of the
 * fixture's resume, notes, posting or edit text in anything written to the console (the application's logs, the
 * engine's own output, the renderer's and the job worker's). The check is on every run of four consecutive
 * words of each text, so a truncated preview or a half-quoted message is caught as well as a whole line.
 */
@Tag("corpus")
@ExtendWith(OutputCaptureExtension.class)
class LogHygieneTest extends MatchFlowBase {

    private static final String SECRET_EDIT = "Zyxwvu quartzite marmalade sentinel phrase about my private result";
    private static final String SECRET_NOTES = "Qwertyuiop confidential note about acquisition codename Basilisk and Tamarind";
    private static final String SECRET_POSTING_TAIL = "Confidential recruiter remark xylophone tangerine wombat budget";

    /** Every run of {@code n} consecutive words in {@code text}. */
    private static Set<String> shingles(String text, int n) {
        String[] words = text.replaceAll("\\s+", " ").strip().split(" ");
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i + n <= words.length; i++) {
            out.add(String.join(" ", java.util.Arrays.copyOfRange(words, i, i + n)));
        }
        return out;
    }

    @Test
    void aFullFixtureRunLogsNoResumeNotesPostingOrEditText(CapturedOutput output) throws Exception {
        List<String> secrets = new ArrayList<>();

        // --- Jane Doe: upload, onboarding, intake with notes, generation (recorded), one failing generation ---------
        Account jane = acceptedAccount();
        secrets.add(SECRET_NOTES);
        secrets.add(janeNotes());
        assertThat(save(jane, "job-0", "DETAILED", SECRET_NOTES + ". " + janeNotes(), null).status()).isEqualTo(200);
        assertThat(save(jane, "job-1", "EXISTING_ONLY", "", null).status()).isEqualTo(200);
        jane.browser().postJson("/resumes/" + jane.resumeId() + "/generate", "{}");
        runJobs();
        RecordedModels.failing = true; // a model failure must not put the notes in a message either
        jane.browser().postJson("/resumes/" + jane.resumeId() + "/generate", "{}");
        runJobs();
        RecordedModels.failing = false;

        // --- the projects resume: matching, an alternative, edits, and edits that fail ------------------------------
        Seeded s = seededAccount();
        Account a = s.account();
        String platform = jd("platform");
        secrets.add(platform);
        TestBrowser.Response posted = post(a, platform + "\n" + SECRET_POSTING_TAIL);
        runJobs();
        String postingId = JSON.readTree(posted.body()).path("posting_id").asText();
        JsonNode match = JSON.readTree(a.browser().get("/postings/" + postingId + "/match").body());
        UUID snapshot = UUID.fromString(match.path("resume").path("id").asText());
        String alt = match.path("alternatives").get(0).path("id").asText();
        a.browser().postJson("/snapshots/" + alt + "/render", "{}");
        runJobs();
        JsonNode view = JSON.readTree(a.browser().get("/snapshots/" + snapshot).body());
        int slot = -1;
        for (JsonNode sl : view.path("slots")) {
            if (sl.path("editable").asBoolean()) {
                slot = sl.path("slot").asInt();
                break;
            }
        }
        secrets.add(SECRET_EDIT);
        // a check, a good revision, a revision that is too long, and malformed JSON carrying the secret
        a.browser().postJson("/snapshots/" + snapshot + "/slots/" + slot + "/check", "{\"text\":" + quote(SECRET_EDIT) + "}");
        a.browser().postJson("/snapshots/" + snapshot + "/revisions", "{\"edits\":[{\"slot\":" + slot + ",\"text\":" + quote(SECRET_EDIT) + "}]}");
        runJobs();
        a.browser().postJson("/snapshots/" + snapshot + "/revisions",
                "{\"edits\":[{\"slot\":" + slot + ",\"text\":" + quote((SECRET_EDIT + " ").repeat(40)) + "}]}");
        runJobs();
        a.browser().postJson("/snapshots/" + snapshot + "/revisions", "{\"edits\":[{\"slot\":" + slot + ",\"text\":\"" + SECRET_EDIT + "\"");
        a.browser().postJson("/postings", "{\"text\":\"" + SECRET_POSTING_TAIL + " " + "word ".repeat(5000) + "\"}");
        a.browser().postFile("/resumes", "file", "broken.docx", ("not a docx " + SECRET_NOTES).getBytes());
        runJobs();

        // --- collect every text the users gave or the system made, and look for it in the output --------------------
        for (var account : List.of(jane, a)) {
            secrets.add(jdbc.queryForObject("SELECT string_agg(s->>'text', ' ') FROM resumes r, jsonb_array_elements(r.onboard_json->'slots') s WHERE r.id = ?",
                    String.class, account.resumeId()));
        }
        secrets.add(jdbc.queryForObject("SELECT coalesce(string_agg(text, ' '), '') FROM library_items", String.class));
        secrets.add(jdbc.queryForObject("SELECT coalesce(string_agg(text, ' '), '') FROM postings", String.class));
        secrets.add(jdbc.queryForObject("SELECT coalesce(string_agg(raw_text, ' '), '') FROM intake_sections", String.class));

        int checked = assertNoLeaks(output.getAll(), secrets);
        assertThat(checked).as("a real amount of text was checked").isGreaterThan(300);
        // The run really produced logs (the check is not passing on silence).
        String logs = output.getAll();
        assertThat(logs).contains("onboarded").contains("matched");
    }

    /** Fails if any four consecutive words of any secret appear in {@code logs}; returns how many were checked. */
    private static int assertNoLeaks(String logs, List<String> secrets) {
        String flat = logs.replaceAll("\s+", " ");
        int checked = 0;
        for (String secret : secrets) {
            for (String shingle : shingles(secret, 4)) {
                checked++;
                assertThat(flat).as("the logs contain: \"" + shingle + "\"").doesNotContain(shingle);
            }
        }
        return checked;
    }

    @Test
    void theCheckWouldCatchALeakEvenATruncatedOne() {
        // A message that quotes only the start of a secret edit, as a careless "preview" log line would.
        String leaky = "WARN something failed for text starting '" + SECRET_EDIT.substring(0, 40) + "...'";

        org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class, () -> assertNoLeaks(leaky, List.of(SECRET_EDIT)));
        // And clean output passes.
        assertThat(assertNoLeaks("INFO resume 1234 onboarded: 1 pages", List.of(SECRET_EDIT))).isGreaterThan(3);
    }

    private static String janeNotes() throws Exception {
        JsonNode jane = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Fixtures.phase4().resolve("intake_jane_doe.json").toFile());
        return jane.path("sections").get(0).path("raw_text").asText();
    }
}
