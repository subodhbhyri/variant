package com.tailor.web.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailor.engine.measure.PdfLines;
import com.tailor.web.auth.TestBrowser;
import com.tailor.web.storage.StorageKeys;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** P6-T6 and the cache and snapshot rules of PHASE6_SPEC.md sections 4.4 and 6, with the real engine. */
@Tag("corpus")
class MatchFlowTest extends MatchFlowBase {

    private static final Path GOLDEN = Fixtures.phase5().resolve("match_run").resolve("golden");

    private JsonNode matchOf(Account a, String postingId) throws Exception {
        return JSON.readTree(a.browser().get("/postings/" + postingId + "/match").body());
    }

    private String postAndRun(Account a, String jdName) throws Exception {
        TestBrowser.Response r = post(a, jd(jdName));
        assertThat(r.status()).as(jdName).isEqualTo(202);
        String postingId = JSON.readTree(r.body()).path("posting_id").asText();
        runJobs();
        return postingId;
    }

    private static String documentXml(byte[] docx) throws IOException {
        Path file = Files.createTempFile("snapshot", ".docx");
        Files.write(file, docx);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] download(String url) throws Exception {
        HttpResponse<byte[]> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(r.statusCode()).isEqualTo(200);
        return r.body();
    }

    /** `tailor match` on the same onboarding outputs, library and posting, with the same embedder. */
    private record CliRun(JsonNode matchJson, String documentXml, Path pdf) {
    }

    private CliRun cli(Account a, String jdName) throws Exception {
        Path dir = Files.createTempDirectory("cli-match");
        Files.write(dir.resolve("normalized.docx"), storage.get(StorageKeys.normalized(a.userId(), a.resumeId())));
        Files.write(dir.resolve("preview.pdf"), storage.get(StorageKeys.preview(a.userId(), a.resumeId())));
        Files.write(dir.resolve("baseline.json"), storage.get(StorageKeys.baseline(a.userId(), a.resumeId())));
        Files.writeString(dir.resolve("onboard.json"), jdbc.queryForObject("SELECT onboard_json::text FROM resumes WHERE id = ?", String.class, a.resumeId()));
        Path jdFile = Files.writeString(dir.resolve(jdName + ".txt"), jd(jdName));
        Path out = dir.resolve("out");
        int exit = new picocli.CommandLine(new com.tailor.cli.MatchCommand()).execute(dir.resolve("normalized.docx").toString(),
                Fixtures.phase5().resolve("match_run").resolve("variants.json").toString(), phase3("library.json").toString(),
                jdFile.toString(), out.toString(), "--embedder", embedderKind());
        assertThat(exit).as("tailor match " + jdName).isZero();
        return new CliRun(JSON.readTree(out.resolve("match.json").toFile()), documentXml(Files.readAllBytes(out.resolve("resume-1.docx"))),
                out.resolve("resume-1.pdf"));
    }

    @Test
    void theThreeFixturePostingsGiveTheCliMatchJsonAndPdfText() throws Exception {
        Seeded s = seededAccount();
        Account a = s.account();
        for (String name : List.of("platform", "frontend", "data")) {
            String postingId = postAndRun(a, name);

            JsonNode view = matchOf(a, postingId);
            assertThat(view.path("status").asText()).as(name).isEqualTo("ready");
            assertThat(view.path("cache_hit").asBoolean()).as(name).isFalse();

            // match.json: the stored result is what `tailor match` writes, field for field.
            JsonNode stored = JSON.readTree(jdbc.queryForObject(
                    "SELECT result::text FROM matches WHERE posting_id = ?::uuid", String.class, postingId));
            CliRun cli = cli(a, name);
            assertThat(stored).as(name + " match.json").isEqualTo(cli.matchJson());
            if (System.getenv("VARIANT_MODEL_DIR") != null) {
                // With the real model, also the baseline stored in the repository (fixtures/phase5/match_run/golden).
                assertThat(stored).as(name + " match.json vs golden").isEqualTo(JSON.readTree(GOLDEN.resolve(name).resolve("match.json").toFile()));
            }

            // resume #1: the same document, and so the same PDF text and positions, as the CLI's.
            UUID snapshotId = UUID.fromString(view.path("resume").path("id").asText());
            JsonNode pdfLink = JSON.readTree(a.browser().get("/snapshots/" + snapshotId + "/pdf").body());
            Path pdf = Files.createTempFile("resume-1", ".pdf");
            Files.write(pdf, download(pdfLink.path("url").asText()));
            assertThat(PdfLines.extract(pdf)).as(name + " PDF text and positions").isEqualTo(PdfLines.extract(cli.pdf()));
            assertThat(documentXml(storage.get(StorageKeys.snapshotDocx(a.userId(), snapshotId))))
                    .as(name + " document.xml").isEqualTo(cli.documentXml());
            if (System.getenv("VARIANT_MODEL_DIR") != null) {
                assertThat(documentXml(storage.get(StorageKeys.snapshotDocx(a.userId(), snapshotId))))
                        .as(name + " document.xml vs golden")
                        .isEqualTo(documentXml(Files.readAllBytes(GOLDEN.resolve(name).resolve("resume-1.docx"))));
            }

            // The view: scored alternatives (not yet rendered), missing skills.
            assertThat(view.path("resume").path("rank").asInt()).isEqualTo(1);
            assertThat(view.path("resume").path("status").asText()).isEqualTo("rendered");
            JsonNode alternatives = view.path("alternatives");
            assertThat(alternatives.size()).isEqualTo(cli.matchJson().path("resumes").size() - 1);
            for (int i = 0; i < alternatives.size(); i++) {
                assertThat(alternatives.get(i).path("status").asText()).isEqualTo("stored");
                assertThat(alternatives.get(i).path("score").asDouble())
                        .isEqualTo(cli.matchJson().path("resumes").get(i + 1).path("total").asDouble());
            }
            assertThat(view.path("missing_skills").size()).isEqualTo(cli.matchJson().path("missing").size());
        }
    }

    @Test
    void labelsNameProjectsByTitleNotById() throws Exception {
        Seeded s = seededAccount();
        String postingId = postAndRun(s.account(), "platform");

        JsonNode view = matchOf(s.account(), postingId);

        for (JsonNode alt : view.path("alternatives")) {
            String label = alt.path("label").asText();
            assertThat(label).doesNotContain("sprout").doesNotContain("quill").doesNotContain("harbor")
                    .doesNotContain("relay").doesNotContain("forge");
        }
        // The engine's own label does use ids.
        List<String> raw = jdbc.queryForList("SELECT label FROM snapshots WHERE user_id = ? AND rank > 1", String.class, s.account().userId());
        assertThat(String.join(" ", raw)).containsAnyOf("sprout", "quill", "harbor", "relay", "forge");
    }

    @Test
    void aRepeatedOrRewordedPostingIsACacheHitAndADifferentOneIsNot() throws Exception {
        Seeded s = seededAccount();
        Account a = s.account();
        String first = postAndRun(a, "platform");
        JsonNode firstView = matchOf(a, first);
        long jobsBefore = count("SELECT count(*) FROM jobs WHERE user_id = ? AND type = 'match'", a.userId());
        long snapshotsBefore = count("SELECT count(*) FROM snapshots WHERE user_id = ?", a.userId());

        // The same posting: answered at once (200), no new job, no new snapshots.
        TestBrowser.Response again = post(a, jd("platform"));
        assertThat(again.status()).isEqualTo(200);
        JsonNode hit = JSON.readTree(again.body());
        assertThat(hit.path("cache_hit").asBoolean()).isTrue();
        assertThat(hit.path("resume").path("id").asText()).isEqualTo(firstView.path("resume").path("id").asText());
        // The reworded posting (Jaccard 1.0 on the fixture): also a hit.
        TestBrowser.Response reworded = post(a, jd("platform_reworded"));
        assertThat(reworded.status()).isEqualTo(200);
        assertThat(JSON.readTree(reworded.body()).path("cache_hit").asBoolean()).isTrue();

        assertThat(count("SELECT count(*) FROM jobs WHERE user_id = ? AND type = 'match'", a.userId())).isEqualTo(jobsBefore);
        assertThat(count("SELECT count(*) FROM snapshots WHERE user_id = ?", a.userId())).isEqualTo(snapshotsBefore);
        assertThat(count("SELECT count(*) FROM matches WHERE user_id = ? AND cache_of IS NOT NULL", a.userId())).isEqualTo(2);
        // Hits write nothing to the ledger; the miss wrote one tailoring row.
        assertThat(count("SELECT count(*) FROM usage_ledger WHERE user_id = ? AND kind = 'tailoring'", a.userId())).isEqualTo(1);

        // A different posting is a miss.
        assertThat(post(a, jd("frontend")).status()).isEqualTo(202);
        runJobs();
        assertThat(count("SELECT count(*) FROM usage_ledger WHERE user_id = ? AND kind = 'tailoring'", a.userId())).isEqualTo(2);
    }

    @Test
    void aNewLibraryVersionIsNeverServedAStaleMatchAndOldSnapshotsStayAsTheyWere() throws Exception {
        Seeded s = seededAccount();
        Account a = s.account();
        String first = postAndRun(a, "platform");
        JsonNode oldView = matchOf(a, first);
        UUID oldSnapshot = UUID.fromString(oldView.path("resume").path("id").asText());
        String oldRow = jdbc.queryForObject("SELECT row_to_json(s)::text FROM snapshots s WHERE id = ?", String.class, oldSnapshot);
        byte[] oldDocx = storage.get(StorageKeys.snapshotDocx(a.userId(), oldSnapshot));
        byte[] oldPdf = storage.get(StorageKeys.snapshotPdf(a.userId(), oldSnapshot));

        // The user's material changes: a new library version (one variant of a project is removed).
        JsonNode library = JSON.readTree(a.browser().get("/resumes/" + a.resumeId() + "/library").body());
        String variantId = null;
        for (JsonNode section : library.path("sections")) {
            if (section.path("kind").asText().equals("project") && section.path("candidates").size() > 0) {
                variantId = section.path("candidates").get(0).path("variants").get(0).path("id").asText();
                break;
            }
        }
        assertThat(variantId).isNotNull();
        assertThat(a.browser().postJson("/libraries/" + library.path("library_id").asText() + "/variants/" + variantId + "/remove", "{}").status())
                .isEqualTo(200);

        // The same posting again must not be answered from the old library's match.
        TestBrowser.Response again = post(a, jd("platform"));
        assertThat(again.status()).as("a new library version is a cache miss").isEqualTo(202);
        runJobs();
        String second = JSON.readTree(again.body()).path("posting_id").asText();
        JsonNode newView = matchOf(a, second);
        assertThat(newView.path("status").asText()).isEqualTo("ready");
        assertThat(newView.path("cache_hit").asBoolean()).isFalse();
        UUID newSnapshot = UUID.fromString(newView.path("resume").path("id").asText());
        assertThat(newSnapshot).isNotEqualTo(oldSnapshot);

        // The old snapshot is exactly as it was and still downloads.
        assertThat(jdbc.queryForObject("SELECT row_to_json(s)::text FROM snapshots s WHERE id = ?", String.class, oldSnapshot))
                .isEqualTo(oldRow);
        assertThat(storage.get(StorageKeys.snapshotDocx(a.userId(), oldSnapshot))).isEqualTo(oldDocx);
        assertThat(storage.get(StorageKeys.snapshotPdf(a.userId(), oldSnapshot))).isEqualTo(oldPdf);
        assertThat(download(JSON.readTree(a.browser().get("/snapshots/" + oldSnapshot + "/pdf").body()).path("url").asText())).isEqualTo(oldPdf);
        assertThat(matchOf(a, first).path("resume").path("id").asText()).isEqualTo(oldSnapshot.toString());
    }

    @Test
    void anAlternativeIsRenderedOnRequestAndThenNeverChanges() throws Exception {
        Seeded s = seededAccount();
        Account a = s.account();
        String postingId = postAndRun(a, "platform");
        JsonNode alt = matchOf(a, postingId).path("alternatives").get(0);
        UUID altId = UUID.fromString(alt.path("id").asText());
        assertThat(alt.path("status").asText()).isEqualTo("stored");
        assertThat(a.browser().get("/snapshots/" + altId + "/pdf").status()).as("nothing to download before it is rendered").isEqualTo(409);

        TestBrowser.Response asked = a.browser().postJson("/snapshots/" + altId + "/render", "{}");
        assertThat(asked.status()).isEqualTo(202);
        UUID jobId = UUID.fromString(JSON.readTree(asked.body()).path("job_id").asText());
        // A double click gets the same job.
        assertThat(JSON.readTree(a.browser().postJson("/snapshots/" + altId + "/render", "{}").body()).path("job_id").asText())
                .isEqualTo(jobId.toString());
        runJobs();

        assertThat(JSON.readTree(a.browser().get("/jobs/" + jobId).body()).path("status").asText()).isEqualTo("succeeded");
        JsonNode view = JSON.readTree(a.browser().get("/snapshots/" + altId).body());
        assertThat(view.path("status").asText()).isEqualTo("rendered");
        assertThat(view.path("projects").size()).isGreaterThan(0);
        assertThat(view.path("projects").get(0).path("title").asText()).isNotBlank();
        byte[] pdf = download(JSON.readTree(a.browser().get("/snapshots/" + altId + "/pdf").body()).path("url").asText());
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        assertThat(count("SELECT count(*) FROM usage_ledger WHERE user_id = ? AND kind = 'alternative'", a.userId())).isEqualTo(1);

        // Asking again changes nothing: 200 with the same snapshot, no new job, no new ledger row.
        TestBrowser.Response again = a.browser().postJson("/snapshots/" + altId + "/render", "{}");
        assertThat(again.status()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM usage_ledger WHERE user_id = ? AND kind = 'alternative'", a.userId())).isEqualTo(1);
        // Rendered snapshots are frozen in the database itself.
        assertThatThrownBy(() -> jdbc.update("UPDATE snapshots SET label = 'changed' WHERE id = ?", altId))
                .hasMessageContaining("never changes");
    }

    @Test
    void postingsAreRefusedForTheRightReasons() throws Exception {
        // No resume at all.
        TestBrowser none = signedInBrowser(uniqueEmail());
        TestBrowser.Response noResume = none.postJson("/postings", "{\"text\":" + quote(jd("platform")) + "}");
        assertThat(noResume.status()).isEqualTo(409);
        assertThat(noResume.body()).contains("NO_ACTIVE_RESUME");

        // An accepted resume but no generated material yet.
        Account a = acceptedAccount();
        TestBrowser.Response noLibrary = post(a, jd("platform"));
        assertThat(noLibrary.status()).isEqualTo(409);
        assertThat(noLibrary.body()).contains("NO_LIBRARY");

        Seeded s = seededAccount();
        TestBrowser.Response tooLong = post(s.account(), "Senior engineer. " + "word ".repeat(4000));
        assertThat(tooLong.status()).isEqualTo(422);
        assertThat(JSON.readTree(tooLong.body()).path("code").asText()).isEqualTo("JD_TOO_LONG");
        assertThat(count("SELECT count(*) FROM postings WHERE user_id = ?", s.account().userId())).isZero();
    }

    @Test
    void twoHundredPostingsADayIsTheLimit() throws Exception {
        Seeded s = seededAccount();
        Account a = s.account();
        for (int i = 0; i < 200; i++) {
            jdbc.update("INSERT INTO rate_events (bucket, subject, created_at) VALUES ('postings', ?, now())", a.userId().toString());
        }

        TestBrowser.Response r = post(a, jd("platform"));

        assertThat(r.status()).isEqualTo(429);
        assertThat(JSON.readTree(r.body()).path("code").asText()).isEqualTo("POSTING_LIMIT");
    }

    @Test
    void aRetriedPostingRequestMakesOnePosting() throws Exception {
        Seeded s = seededAccount();
        Account a = s.account();
        String url = FLOW_BASE + "/postings";
        TestBrowser b = a.browser();
        TestBrowser.Response first = postWithKey(b, url, jd("platform"), "post-1");
        TestBrowser.Response again = postWithKey(b, url, jd("platform"), "post-1");

        assertThat(first.status()).isEqualTo(202);
        assertThat(again.status()).isEqualTo(202);
        assertThat(JSON.readTree(again.body()).path("job_id").asText()).isEqualTo(JSON.readTree(first.body()).path("job_id").asText());
        assertThat(JSON.readTree(again.body()).path("posting_id").asText()).isEqualTo(JSON.readTree(first.body()).path("posting_id").asText());
        assertThat(count("SELECT count(*) FROM postings WHERE user_id = ?", a.userId())).isEqualTo(1);
    }

    @Test
    void postingsListNewestFirstAndStatusFollowsTheJob() throws Exception {
        Seeded s = seededAccount();
        Account a = s.account();
        String older = JSON.readTree(post(a, jd("platform")).body()).path("posting_id").asText();
        assertThat(JSON.readTree(a.browser().get("/postings").body()).get(0).path("status").asText()).isEqualTo("pending");
        runJobs();
        String newer = JSON.readTree(post(a, jd("data")).body()).path("posting_id").asText();

        JsonNode list = JSON.readTree(a.browser().get("/postings").body());

        assertThat(list.get(0).path("id").asText()).isEqualTo(newer);
        assertThat(list.get(1).path("id").asText()).isEqualTo(older);
        assertThat(list.get(1).path("status").asText()).isEqualTo("ready");
        assertThat(list.get(0).path("title").asText()).isNotBlank();
        assertThat(JSON.readTree(a.browser().get("/postings/" + newer + "/match").body()).path("status").asText()).isEqualTo("pending");
    }

    @Test
    void anOnboardingFromAnotherLibreOfficeVersionIsNotSilentlyReused() throws Exception {
        Seeded s = seededAccount();
        Account a = s.account();
        jdbc.update("UPDATE resumes SET renderer_version = '1.0.0.0' WHERE id = ?", a.resumeId());
        TestBrowser.Response r = post(a, jd("platform"));
        UUID jobId = UUID.fromString(JSON.readTree(r.body()).path("job_id").asText());

        runJobs();

        JsonNode job = JSON.readTree(a.browser().get("/jobs/" + jobId).body());
        assertThat(job.path("status").asText()).isEqualTo("failed");
        assertThat(job.path("rejected").asBoolean()).isTrue();
        assertThat(job.path("error_code").asText()).isEqualTo("REONBOARD_REQUIRED");
        assertThat(count("SELECT count(*) FROM matches WHERE user_id = ?", a.userId())).isZero();
        assertThat(JSON.readTree(a.browser().get("/postings/" + JSON.readTree(r.body()).path("posting_id").asText() + "/match").body())
                .path("status").asText()).isEqualTo("failed");
    }

    @Test
    void anotherUserGets404OnPostingAndSnapshotEndpointsAndNeverAFileLink() throws Exception {
        Seeded s = seededAccount();
        Account a = s.account();
        String postingId = postAndRun(a, "platform");
        JsonNode view = matchOf(a, postingId);
        String resume1 = view.path("resume").path("id").asText();
        String alt = view.path("alternatives").get(0).path("id").asText();
        TestBrowser stranger = signedInBrowser(uniqueEmail());
        String nobody = UUID.randomUUID().toString();

        String[][] calls = {
                {"GET", "/postings/%s/match", postingId, nobody},
                {"GET", "/snapshots/%s", resume1, nobody},
                {"GET", "/snapshots/%s/pdf", resume1, nobody},
                {"POST", "/snapshots/%s/render", alt, nobody},
        };
        for (String[] c : calls) {
            TestBrowser.Response theirs = c[0].equals("GET") ? stranger.get(c[1].formatted(c[2])) : stranger.postJson(c[1].formatted(c[2]), "{}");
            TestBrowser.Response nothing = c[0].equals("GET") ? stranger.get(c[1].formatted(c[3])) : stranger.postJson(c[1].formatted(c[3]), "{}");
            assertThat(theirs.status()).as(c[1]).isEqualTo(404);
            assertThat(theirs.body()).as(c[1]).isEqualTo(nothing.body()).doesNotContain("http").doesNotContain("url");
        }
        assertThat(count("SELECT count(*) FROM jobs WHERE type = 'render_alternative'")).isZero();
        assertThat(stranger.get("/postings").body()).isEqualTo("[]");
    }

    private static TestBrowser.Response postWithKey(TestBrowser b, String url, String text, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .header("Cookie", "SESSION=" + b.cookie("SESSION") + "; XSRF-TOKEN=" + b.cookie("XSRF-TOKEN"))
                .header("X-XSRF-TOKEN", b.cookie("XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"text\":" + quote(text) + "}")).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return new TestBrowser.Response(response.statusCode(), response.body(), response.headers());
    }
}
