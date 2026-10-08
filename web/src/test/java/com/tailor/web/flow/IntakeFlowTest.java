package com.tailor.web.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailor.web.auth.TestBrowser;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** P6-T4 and the rest of the intake endpoints (PHASE6_SPEC.md section 4.3), on a really onboarded resume. */
@Tag("corpus")
class IntakeFlowTest extends AcceptedResumeBase {

    private static String words(int n) {
        return "word ".repeat(n).trim();
    }

    private TestBrowser.Response addProject(Account a, String body) {
        return a.browser().postJson("/resumes/" + a.resumeId() + "/intake/projects", body);
    }

    @Test
    void theFormIsPrefilledFromTheResumeAndNothingIsSavedYet() throws Exception {
        Account a = acceptedAccount();

        JsonNode form = intake(a);

        JsonNode job0 = section(form, "job-0");
        assertThat(job0).isNotNull();
        assertThat(job0.path("kind").asText()).isEqualTo("job");
        assertThat(job0.path("fields").path("title").asText()).startsWith("Senior Software Engineer");
        assertThat(job0.path("fields").path("date").asText()).isEqualTo("Jan 2023 – Present");
        assertThat(job0.path("saved").asBoolean()).isFalse();
        assertThat(section(form, "job-1")).isNotNull();
        assertThat(form.path("limits").path("max_notes_words").asInt()).isEqualTo(1500);
        assertThat(form.path("limits").path("max_added_projects").asInt()).isEqualTo(8);
    }

    @Test
    void aSavedSectionKeepsExactlyWhatWasSent() throws Exception {
        Account a = acceptedAccount();
        String notes = "Moved order processing to Kafka.\nLatency fell 38% (410 ms to 254 ms).  Two spaces, and 100% of it kept.";

        TestBrowser.Response r = save(a, "job-0", "DETAILED", notes,
                "{\"title\":\"Staff Engineer | Northwind Labs\",\"date\":\"Jan 2023 – Present\"}");

        assertThat(r.status()).isEqualTo(200);
        JsonNode job0 = section(intake(a), "job-0");
        assertThat(job0.path("saved").asBoolean()).isTrue();
        assertThat(job0.path("mode").asText()).isEqualTo("DETAILED");
        assertThat(job0.path("notes").asText()).isEqualTo(notes);
        assertThat(job0.path("fields").path("title").asText()).isEqualTo("Staff Engineer | Northwind Labs");
        // Saving again replaces it (one row per section).
        save(a, "job-0", "SKIPPED", "", null);
        assertThat(section(intake(a), "job-0").path("mode").asText()).isEqualTo("SKIPPED");
        assertThat(count("SELECT count(*) FROM intake_sections WHERE resume_id = ? AND section_id = 'job-0'", a.resumeId())).isEqualTo(1);
    }

    @Test
    void fifteenHundredWordsAreAcceptedWholeAndOneMoreIsRefusedNotCut() throws Exception {
        Account a = acceptedAccount();

        assertThat(save(a, "job-0", "DETAILED", words(1500), null).status()).isEqualTo(200);
        assertThat(section(intake(a), "job-0").path("notes").asText().split(" ")).hasSize(1500);

        TestBrowser.Response over = save(a, "job-0", "DETAILED", words(1501), null);
        assertThat(over.status()).isEqualTo(422);
        JsonNode error = JSON.readTree(over.body());
        assertThat(error.path("code").asText()).isEqualTo("NOTES_TOO_LONG");
        assertThat(error.path("details").path("field").asText()).isEqualTo("notes");
        // Nothing was cut or replaced: the earlier 1,500 words are still what is saved.
        assertThat(section(intake(a), "job-0").path("notes").asText().split(" ")).hasSize(1500);
    }

    @Test
    void anInvalidLinkIsRefusedAtEntryAndNeverStored() throws Exception {
        Account a = acceptedAccount();

        TestBrowser.Response r = addProject(a, "{\"mode\":\"DETAILED\",\"notes\":\"built a thing\","
                + "\"fields\":{\"title\":\"Thing\",\"links\":[{\"label\":\"ok\",\"url\":\"https://example.com/a\"},"
                + "{\"label\":\"bad\",\"url\":\"javascript:alert(1)\"}]}}");

        assertThat(r.status()).isEqualTo(422);
        JsonNode error = JSON.readTree(r.body());
        assertThat(error.path("code").asText()).isEqualTo("INVALID_LINK");
        assertThat(error.path("details").path("field").asText()).isEqualTo("fields.links[1].url");
        assertThat(count("SELECT count(*) FROM intake_sections WHERE resume_id = ?", a.resumeId())).isZero();

        TestBrowser.Response good = addProject(a, "{\"mode\":\"DETAILED\",\"notes\":\"built a thing\","
                + "\"fields\":{\"title\":\"Thing\",\"links\":[{\"label\":\"repo\",\"url\":\"https://example.com/a\"}]}}");
        assertThat(good.status()).isEqualTo(201);
        assertThat(JSON.readTree(good.body()).path("id").asText()).isEqualTo("project-new-0");
    }

    @Test
    void theNinthAddedProjectIsRefused() throws Exception {
        Account a = acceptedAccount();
        for (int i = 0; i < 8; i++) {
            TestBrowser.Response r = addProject(a, "{\"mode\":\"DETAILED\",\"notes\":\"p" + i + "\",\"fields\":{\"title\":\"P" + i + "\"}}");
            assertThat(r.status()).as("project " + i).isEqualTo(201);
            assertThat(JSON.readTree(r.body()).path("id").asText()).isEqualTo("project-new-" + i);
        }

        TestBrowser.Response ninth = addProject(a, "{\"mode\":\"DETAILED\",\"notes\":\"p8\",\"fields\":{\"title\":\"P8\"}}");

        assertThat(ninth.status()).isEqualTo(422);
        assertThat(JSON.readTree(ninth.body()).path("code").asText()).isEqualTo("TOO_MANY_PROJECTS");
        assertThat(count("SELECT count(*) FROM intake_sections WHERE resume_id = ? AND kind = 'project'", a.resumeId())).isEqualTo(8);

        // Removing one makes room; ids are never reused for a different project.
        assertThat(a.browser().delete("/resumes/" + a.resumeId() + "/intake/projects/project-new-3").status()).isEqualTo(204);
        TestBrowser.Response again = addProject(a, "{\"mode\":\"DETAILED\",\"notes\":\"p9\",\"fields\":{\"title\":\"P9\"}}");
        assertThat(again.status()).isEqualTo(201);
        assertThat(JSON.readTree(again.body()).path("id").asText()).isEqualTo("project-new-8");
    }

    @Test
    void editingAnAddedProjectDoesNotCountItAgainstTheLimit() throws Exception {
        Account a = acceptedAccount();
        for (int i = 0; i < 8; i++) {
            addProject(a, "{\"mode\":\"DETAILED\",\"notes\":\"p\",\"fields\":{\"title\":\"P" + i + "\"}}");
        }
        assertThat(save(a, "project-new-7", "DETAILED", "edited", "{\"title\":\"Renamed\"}").status()).isEqualTo(200);
    }

    @Test
    void entriesTheFormDoesNotHaveAreRefused() throws Exception {
        Account a = acceptedAccount();
        // Only the fields this resume's job header has are accepted; find one it does not have.
        JsonNode header = section(intake(a), "job-0").path("fields");
        String absent = java.util.stream.Stream.of("title", "detail", "date", "links")
                .filter(f -> header.path(f).isMissingNode() || header.path(f).isNull()
                        || (header.path(f).isArray() && header.path(f).isEmpty())).findFirst().orElseThrow();
        String value = absent.equals("links") ? "[{\"label\":\"x\",\"url\":\"https://example.com\"}]" : "\"not in this header\"";
        TestBrowser.Response extra = save(a, "job-0", "DETAILED", "x", "{\"" + absent + "\":" + value + "}");
        assertThat(extra.status()).isEqualTo(400);
        assertThat(JSON.readTree(extra.body()).path("details").path("field").asText()).isEqualTo("fields." + absent);

        assertThat(save(a, "job-0", "SOMETIMES", "x", null).status()).isEqualTo(400);
        assertThat(save(a, "job-999", "DETAILED", "x", null).status()).isEqualTo(404);
        assertThat(save(a, "project-new-0", "DETAILED", "x", null).status()).isEqualTo(404); // never added
        assertThat(addProject(a, "{\"mode\":\"EXISTING_ONLY\",\"notes\":\"\"}").status()).isEqualTo(400);
        assertThat(a.browser().delete("/resumes/" + a.resumeId() + "/intake/projects/job-0").status()).isEqualTo(404);
        assertThat(a.browser().delete("/resumes/" + a.resumeId() + "/intake/projects/project-new-5").status()).isEqualTo(404);
    }

    @Test
    void theFormOpensOnlyForAnAcceptedResume() throws Exception {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        UUID resumeId = UUID.fromString(JSON.readTree(
                b.postFile("/resumes", "file", "a.docx", Fixtures.phase2("ok_synthetic.docx")).body()).path("resume_id").asText());

        TestBrowser.Response notOnboarded = b.get("/resumes/" + resumeId + "/intake");
        assertThat(notOnboarded.status()).isEqualTo(409);
        assertThat(notOnboarded.body()).contains("RESUME_NOT_ACCEPTED");

        runJobs(); // onboarded ("ready") but not accepted
        assertThat(b.get("/resumes/" + resumeId + "/intake").status()).isEqualTo(409);
    }

    @Test
    void anotherUserGets404OnEveryIntakeEndpoint() throws Exception {
        Account a = acceptedAccount();
        addProject(a, "{\"mode\":\"DETAILED\",\"notes\":\"p\",\"fields\":{\"title\":\"P\"}}");
        TestBrowser stranger = signedInBrowser(uniqueEmail());
        String base = "/resumes/" + a.resumeId() + "/intake";
        String missing = "/resumes/" + UUID.randomUUID() + "/intake";

        String[][] calls = {{"GET", ""}, {"PUT", "/sections/job-0"}, {"POST", "/projects"}, {"DELETE", "/projects/project-new-0"}};
        for (String[] c : calls) {
            TestBrowser.Response theirs = send(stranger, c[0], base + c[1]);
            TestBrowser.Response nothing = send(stranger, c[0], missing + c[1]);
            assertThat(theirs.status()).as(c[0] + c[1]).isEqualTo(404);
            assertThat(theirs.body()).as(c[0] + c[1]).isEqualTo(nothing.body());
        }
        assertThat(count("SELECT count(*) FROM intake_sections WHERE resume_id = ?", a.resumeId())).isEqualTo(1);
    }

    private static TestBrowser.Response send(TestBrowser b, String method, String path) {
        String body = "{\"mode\":\"DETAILED\",\"notes\":\"x\"}";
        return switch (method) {
            case "GET" -> b.get(path);
            case "PUT" -> b.putJson(path, body);
            case "POST" -> b.postJson(path, body);
            default -> b.delete(path);
        };
    }
}
