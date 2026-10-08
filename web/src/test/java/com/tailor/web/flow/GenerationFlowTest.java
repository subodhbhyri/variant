package com.tailor.web.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailor.cli.GenerateCommand;
import com.tailor.web.auth.TestBrowser;
import com.tailor.web.generation.LibraryRepository;
import com.tailor.web.generation.LibraryService;
import com.tailor.web.storage.StorageKeys;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine;

/**
 * P6-T5 and the library rules of section 6: generation with recorded responses equals the CLI's
 * result, the ledger row is written once even if the job is delivered twice, and every change to
 * material is a new immutable library version.
 */
@Tag("corpus")
class GenerationFlowTest extends AcceptedResumeBase {

    @Autowired
    LibraryRepository libraryRepository;
    @Autowired
    LibraryService libraryService;

    @BeforeEach
    void reset() {
        RecordedModels.failing = false;
        RecordedModels.CALLS.set(0);
    }

    /** Saves Jane Doe's two intake sections (job-0 DETAILED, job-1 EXISTING_ONLY) through the API. */
    private void saveJaneDoe(Account a) throws Exception {
        JsonNode janeDoe = JSON.readTree(Fixtures.phase4().resolve("intake_jane_doe.json").toFile());
        for (JsonNode s : janeDoe.path("sections")) {
            TestBrowser.Response r = save(a, s.path("id").asText(), s.path("mode").asText(), s.path("raw_text").asText(""),
                    s.path("fields").toString());
            assertThat(r.status()).as(s.path("id").asText()).isEqualTo(200);
        }
    }

    private UUID generate(Account a, String body) throws Exception {
        TestBrowser.Response r = a.browser().postJson("/resumes/" + a.resumeId() + "/generate", body);
        assertThat(r.status()).isEqualTo(202);
        return UUID.fromString(JSON.readTree(r.body()).path("job_id").asText());
    }

    private JsonNode job(Account a, UUID id) throws Exception {
        return JSON.readTree(a.browser().get("/jobs/" + id).body());
    }

    private JsonNode library(Account a) throws Exception {
        return JSON.readTree(a.browser().get("/resumes/" + a.resumeId() + "/library").body());
    }

    private JsonNode sectionOf(JsonNode library, String id) {
        for (JsonNode s : library.path("sections")) {
            if (id.equals(s.path("id").asText())) {
                return s;
            }
        }
        return null;
    }

    @Test
    void generationWithRecordedResponsesEqualsTheCliResult() throws Exception {
        Account a = acceptedAccount();
        saveJaneDoe(a);
        UUID jobId = generate(a, "{}");

        runJobs();

        JsonNode job = job(a, jobId);
        assertThat(job.path("status").asText()).isEqualTo("succeeded");
        assertThat(job.path("progress").path("stage").asText()).isEqualTo("storing");

        // The CLI on the same normalized document, intake and recorded responses.
        Path dir = Files.createTempDirectory("cli-generate");
        Path normalized = dir.resolve("normalized.docx");
        Files.write(normalized, storage.get(StorageKeys.normalized(a.userId(), a.resumeId())));
        Path out = dir.resolve("out");
        int exit = new CommandLine(new GenerateCommand()).execute(normalized.toString(),
                Fixtures.phase4().resolve("intake_jane_doe.json").toString(), out.toString(), "--recorded", RecordedModels.recordedFile().toString());
        assertThat(exit).isZero();
        JsonNode cliVariants = JSON.readTree(out.resolve("variants.json").toFile());
        JsonNode cliReport = JSON.readTree(out.resolve("generation-report.json").toFile());

        // The same variants, candidate by candidate and length by length.
        LibraryRepository.Library library = libraryRepository.latest(a.resumeId(), a.userId()).orElseThrow();
        assertThat(library.version()).isEqualTo(1);
        Map<String, Map<String, Map<String, String>>> web = new LinkedHashMap<>();
        libraryService.engineLibrary(library.id(), a.userId()).jobs().forEach((section, candidates) -> {
            Map<String, Map<String, String>> byCandidate = new LinkedHashMap<>();
            candidates.forEach((id, outcome) -> byCandidate.put(id, outcome.variants()));
            web.put(section, byCandidate);
        });
        Map<String, Map<String, Map<String, String>>> cli = new LinkedHashMap<>();
        cliVariants.path("jobs").fields().forEachRemaining(sec -> {
            Map<String, Map<String, String>> byCandidate = new LinkedHashMap<>();
            sec.getValue().fields().forEachRemaining(c -> {
                Map<String, String> byLength = new LinkedHashMap<>();
                c.getValue().path("variants").fields().forEachRemaining(v -> byLength.put(v.getKey(), v.getValue().asText()));
                byCandidate.put(c.getKey(), byLength);
            });
            cli.put(sec.getKey(), byCandidate);
        });
        assertThat(web).isEqualTo(cli);
        assertThat(web).as("something was actually generated").isNotEmpty();
        assertThat(cliVariants.path("projects").path("projects").size()).isEqualTo(
                libraryService.engineLibrary(library.id(), a.userId()).projects().projects().size());

        // The same generation report: statuses, rounds, per-candidate outcomes and reasons, attempts, tokens, cost.
        LibraryRepository.Run run = libraryRepository.runs(a.resumeId(), a.userId()).get(0);
        assertThat(run.status()).isEqualTo("succeeded");
        assertThat(run.report()).isEqualTo(cliReport);
    }

    @Test
    void theLedgerRowIsWrittenOnceEvenIfTheJobIsDeliveredTwice() throws Exception {
        Account a = acceptedAccount();
        saveJaneDoe(a);
        UUID jobId = generate(a, "{}");
        runJobs();
        assertThat(count("SELECT count(*) FROM usage_ledger WHERE user_id = ? AND kind = 'generation'", a.userId())).isEqualTo(1);
        long libraries = count("SELECT count(*) FROM libraries WHERE resume_id = ?", a.resumeId());
        int callsBefore = RecordedModels.CALLS.get();

        // The same job is delivered again (a duplicate delivery, as after a queue hiccup).
        jdbc.update("UPDATE jobs SET status = 'queued', attempts = 0, worker_id = NULL, finished_at = NULL, error_code = NULL WHERE id = ?", jobId);
        runJobs();

        assertThat(job(a, jobId).path("status").asText()).isEqualTo("succeeded");
        assertThat(count("SELECT count(*) FROM usage_ledger WHERE user_id = ? AND kind = 'generation'", a.userId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM libraries WHERE resume_id = ?", a.resumeId())).isEqualTo(libraries);
        assertThat(RecordedModels.CALLS.get()).as("the model is not called again").isEqualTo(callsBefore);
        // The row holds ids and an amount, keyed by the run.
        UUID run = libraryRepository.runs(a.resumeId(), a.userId()).get(0).id();
        assertThat(jdbc.queryForObject("SELECT ref_id FROM usage_ledger WHERE user_id = ? AND kind = 'generation'", UUID.class, a.userId()))
                .isEqualTo(run);
        assertThat(count("SELECT count(*) FROM usage_ledger WHERE idempotency_key = ?", run.toString())).isEqualTo(1);
    }

    @Test
    void theLibraryShowsVariantsAndWhatWasDroppedAndWhy() throws Exception {
        Account a = acceptedAccount();
        assertThat(a.browser().get("/resumes/" + a.resumeId() + "/library").status()).isEqualTo(404);
        saveJaneDoe(a);
        generate(a, "{}");
        runJobs();

        JsonNode lib = library(a);

        assertThat(lib.path("version").asInt()).isEqualTo(1);
        JsonNode job0 = sectionOf(lib, "job-0");
        assertThat(job0.path("kind").asText()).isEqualTo("job");
        assertThat(job0.path("status").asText()).isEqualTo("GENERATED");
        assertThat(job0.path("candidates").size()).isGreaterThan(0);
        JsonNode variant = job0.path("candidates").get(0).path("variants").get(0);
        assertThat(variant.path("id").asText()).startsWith("job-0:");
        assertThat(variant.path("text").asText()).isNotBlank();
        assertThat(variant.path("length").asInt()).isGreaterThan(0);
        assertThat(sectionOf(lib, "job-1")).isNotNull();
    }

    @Test
    void regeneratingOneSectionIsANewVersionAndOthersCarryOver() throws Exception {
        Account a = acceptedAccount();
        saveJaneDoe(a);
        generate(a, "{}");
        runJobs();
        LibraryRepository.Library v1 = libraryRepository.latest(a.resumeId(), a.userId()).orElseThrow();
        List<LibraryRepository.Item> v1Items = libraryRepository.items(v1.id(), a.userId());
        JsonNode job1Before = sectionOf(library(a), "job-1");

        // The user changes job-0's notes and regenerates just that section.
        save(a, "job-0", "DETAILED", "Different notes about the same job.", null);
        UUID jobId = generate(a, "{\"sections\":[\"job-0\"]}");
        runJobs();

        assertThat(job(a, jobId).path("status").asText()).isEqualTo("succeeded");
        LibraryRepository.Library v2 = libraryRepository.latest(a.resumeId(), a.userId()).orElseThrow();
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.id()).isNotEqualTo(v1.id());
        // Version 1 is exactly as it was: libraries never change.
        assertThat(libraryRepository.items(v1.id(), a.userId())).isEqualTo(v1Items);
        // job-1 was not regenerated: its material is carried over unchanged.
        List<LibraryRepository.Item> v2Job1 = libraryRepository.items(v2.id(), a.userId()).stream()
                .filter(i -> i.sectionId().equals("job-1")).toList();
        assertThat(v2Job1).isEqualTo(v1Items.stream().filter(i -> i.sectionId().equals("job-1")).toList());
        assertThat(sectionOf(library(a), "job-1").path("candidates")).isEqualTo(job1Before.path("candidates"));
        // The model was asked about job-0 only.
        assertThat(count("SELECT count(*) FROM usage_ledger WHERE user_id = ? AND kind = 'generation'", a.userId())).isEqualTo(2);
    }

    @Test
    void removingAVariantWritesANewVersionAndLeavesTheOldOneAlone() throws Exception {
        Account a = acceptedAccount();
        saveJaneDoe(a);
        generate(a, "{}");
        runJobs();
        JsonNode lib = library(a);
        UUID v1 = UUID.fromString(lib.path("library_id").asText());
        String variantId = sectionOf(lib, "job-0").path("candidates").get(0).path("variants").get(0).path("id").asText();
        List<LibraryRepository.Item> before = libraryRepository.items(v1, a.userId());

        TestBrowser.Response removed = a.browser().postJson("/libraries/" + v1 + "/variants/" + variantId + "/remove", "{}");

        assertThat(removed.status()).isEqualTo(200);
        JsonNode v2 = JSON.readTree(removed.body());
        assertThat(v2.path("version").asInt()).isEqualTo(2);
        List<LibraryRepository.Item> after = libraryRepository.items(UUID.fromString(v2.path("library_id").asText()), a.userId());
        assertThat(after).hasSize(before.size() - 1);
        assertThat(after.stream().anyMatch(i -> LibraryService.variantId(i.sectionId(), i.candidateId(), i.length()).equals(variantId))).isFalse();
        assertThat(libraryRepository.items(v1, a.userId())).as("version 1 is untouched").isEqualTo(before);
        assertThat(JSON.readTree(a.browser().get("/resumes/" + a.resumeId() + "/library").body()).path("version").asInt()).isEqualTo(2);

        // A page still showing version 1 cannot change version 1 behind the user's back.
        TestBrowser.Response stale = a.browser().postJson("/libraries/" + v1 + "/variants/" + variantId + "/remove", "{}");
        assertThat(stale.status()).isEqualTo(409);
        assertThat(JSON.readTree(stale.body()).path("code").asText()).isEqualTo("LIBRARY_STALE");
        // An unknown variant is a 404 and writes nothing.
        UUID latest = UUID.fromString(v2.path("library_id").asText());
        assertThat(a.browser().postJson("/libraries/" + latest + "/variants/job-0:zz:9/remove", "{}").status()).isEqualTo(404);
        assertThat(count("SELECT count(*) FROM libraries WHERE resume_id = ?", a.resumeId())).isEqualTo(2);
    }

    @Test
    void aLibraryIsImmutableInTheDatabaseToo() throws Exception {
        Account a = acceptedAccount();
        saveJaneDoe(a);
        generate(a, "{}");
        runJobs();
        UUID library = libraryRepository.latest(a.resumeId(), a.userId()).orElseThrow().id();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> jdbc.update("UPDATE library_items SET text = 'edited' WHERE library_id = ?", library))
                .hasMessageContaining("immutable");
    }

    @Test
    void aFailedModelCallLeavesNoLibraryNoLedgerRowAndIsNotRetried() throws Exception {
        Account a = acceptedAccount();
        saveJaneDoe(a);
        RecordedModels.failing = true;
        UUID jobId = generate(a, "{}");

        runJobs();

        JsonNode job = job(a, jobId);
        assertThat(job.path("status").asText()).isEqualTo("failed");
        assertThat(job.path("rejected").asBoolean()).isFalse();
        assertThat(job.path("error_code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(count("SELECT attempts FROM jobs WHERE id = ?", jobId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM libraries WHERE resume_id = ?", a.resumeId())).isZero();
        assertThat(count("SELECT count(*) FROM usage_ledger WHERE user_id = ?", a.userId())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM generation_runs WHERE resume_id = ?", String.class, a.resumeId())).isEqualTo("failed");
        assertThat(a.browser().get("/resumes/" + a.resumeId() + "/library").status()).isEqualTo(404);
    }

    @Test
    void generatingNeedsAnAcceptedResumeAndAtLeastOneSavedSection() throws Exception {
        Account a = acceptedAccount();

        TestBrowser.Response none = a.browser().postJson("/resumes/" + a.resumeId() + "/generate", "{}");
        assertThat(none.status()).isEqualTo(409);
        assertThat(none.body()).contains("NO_INTAKE");

        saveJaneDoe(a);
        TestBrowser.Response noLibrary = a.browser().postJson("/resumes/" + a.resumeId() + "/generate", "{\"sections\":[\"job-0\"]}");
        assertThat(noLibrary.status()).isEqualTo(409);
        assertThat(noLibrary.body()).contains("NO_LIBRARY");

        TestBrowser.Response unsaved = a.browser().postJson("/resumes/" + a.resumeId() + "/generate", "{\"sections\":[\"job-7\"]}");
        assertThat(unsaved.status()).isEqualTo(400);

        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        UUID resume = UUID.fromString(JSON.readTree(b.postFile("/resumes", "file", "a.docx", Fixtures.phase2("ok_synthetic.docx")).body()).path("resume_id").asText());
        runJobs();
        TestBrowser.Response notAccepted = b.postJson("/resumes/" + resume + "/generate", "{}");
        assertThat(notAccepted.status()).isEqualTo(409);
        assertThat(notAccepted.body()).contains("RESUME_NOT_ACCEPTED");
        assertThat(count("SELECT count(*) FROM generation_runs WHERE resume_id = ?", resume)).isZero();
    }

    @Test
    void atMostFiveRunsPerResumePerDay() throws Exception {
        Account a = acceptedAccount();
        saveJaneDoe(a);
        for (int i = 0; i < 5; i++) {
            jdbc.update("INSERT INTO generation_runs (id, resume_id, user_id, status, started_at) VALUES (?, ?, ?, 'succeeded', now())",
                    UUID.randomUUID(), a.resumeId(), a.userId());
        }

        TestBrowser.Response sixth = a.browser().postJson("/resumes/" + a.resumeId() + "/generate", "{}");

        assertThat(sixth.status()).isEqualTo(429);
        assertThat(JSON.readTree(sixth.body()).path("code").asText()).isEqualTo("GENERATION_LIMIT");
        assertThat(count("SELECT count(*) FROM jobs WHERE user_id = ? AND type = 'generate'", a.userId())).isZero();

        clock.advance(java.time.Duration.ofHours(25)); // a day later the runs no longer count
        assertThat(a.browser().postJson("/resumes/" + a.resumeId() + "/generate", "{}").status()).isEqualTo(202);
    }

    @Test
    void aRetriedGenerateRequestStartsOnlyOneRun() throws Exception {
        Account a = acceptedAccount();
        saveJaneDoe(a);
        String url = "/resumes/" + a.resumeId() + "/generate";
        TestBrowser.Response first = postWithKey(a.browser(), url, "gen-1");
        TestBrowser.Response again = postWithKey(a.browser(), url, "gen-1");

        assertThat(first.status()).isEqualTo(202);
        assertThat(again.status()).isEqualTo(202);
        assertThat(JSON.readTree(again.body()).path("job_id").asText()).isEqualTo(JSON.readTree(first.body()).path("job_id").asText());
        assertThat(JSON.readTree(again.body()).path("run_id").asText()).isEqualTo(JSON.readTree(first.body()).path("run_id").asText());
        assertThat(count("SELECT count(*) FROM generation_runs WHERE resume_id = ?", a.resumeId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM jobs WHERE user_id = ? AND type = 'generate'", a.userId())).isEqualTo(1);
    }

    @Test
    void anotherUserGets404OnGenerationAndLibraryEndpoints() throws Exception {
        Account a = acceptedAccount();
        saveJaneDoe(a);
        generate(a, "{}");
        runJobs();
        JsonNode lib = library(a);
        String libraryId = lib.path("library_id").asText();
        String variantId = sectionOf(lib, "job-0").path("candidates").get(0).path("variants").get(0).path("id").asText();
        TestBrowser stranger = signedInBrowser(uniqueEmail());

        for (String[] c : new String[][] {
                {"POST", "/resumes/" + a.resumeId() + "/generate"},
                {"GET", "/resumes/" + a.resumeId() + "/library"},
                {"POST", "/libraries/" + libraryId + "/variants/" + variantId + "/remove"}}) {
            TestBrowser.Response r = c[0].equals("GET") ? stranger.get(c[1]) : stranger.postJson(c[1], "{}");
            assertThat(r.status()).as(c[1]).isEqualTo(404);
            assertThat(r.body()).doesNotContain(variantId);
        }
        assertThat(count("SELECT count(*) FROM libraries WHERE resume_id = ?", a.resumeId())).isEqualTo(1);
    }

    private static TestBrowser.Response postWithKey(TestBrowser b, String path, String key) {
        // TestBrowser has no header-taking JSON POST; the multipart helper takes headers, so use a tiny raw request.
        try {
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(FLOW_BASE + path))
                    .header("Content-Type", "application/json").header("Idempotency-Key", key)
                    .header("Cookie", "SESSION=" + b.cookie("SESSION") + "; XSRF-TOKEN=" + b.cookie("XSRF-TOKEN"))
                    .header("X-XSRF-TOKEN", b.cookie("XSRF-TOKEN"))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build();
            var response = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            return new TestBrowser.Response(response.statusCode(), response.body(), response.headers());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
