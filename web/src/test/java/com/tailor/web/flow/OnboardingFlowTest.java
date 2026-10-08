package com.tailor.web.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.blocks.BlocksAnalyzer;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.web.auth.TestBrowser;
import com.tailor.web.storage.StorageKeys;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * P6-T3, the onboarding half, with the real engine and the real renderer service: an uploaded
 * resume's onboarding job stores exactly what the CLI's {@code tailor onboard} and {@code tailor
 * blocks} produce, and the endpoints around it (status, preview link, roles, accept) behave.
 */
@Tag("corpus")
class OnboardingFlowTest extends FlowTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper CLI_BLOCKS = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private record Uploaded(UUID resumeId, UUID jobId) {
    }

    private Uploaded upload(TestBrowser b, String name, byte[] file) throws Exception {
        TestBrowser.Response r = b.postFile("/resumes", "file", name, file);
        assertThat(r.status()).as(name).isEqualTo(202);
        JsonNode body = JSON.readTree(r.body());
        return new Uploaded(UUID.fromString(body.path("resume_id").asText()), UUID.fromString(body.path("job_id").asText()));
    }

    private JsonNode job(TestBrowser b, UUID id) throws Exception {
        return JSON.readTree(b.get("/jobs/" + id).body());
    }

    private JsonNode resume(TestBrowser b, UUID id) throws Exception {
        return JSON.readTree(b.get("/resumes/" + id).body());
    }

    /** What `tailor onboard` and `tailor blocks` produce for the same bytes, on the local renderer. */
    private record Cli(OnboardReport report, JsonNode blocks, List<PdfLines.Line> previewLines, JsonNode baseline) {
    }

    private Cli cli(byte[] upload) throws Exception {
        Path out = Files.createTempDirectory("cli-onboard");
        OnboardReport report = new OnboardPipeline(new LibreOfficeRenderer(), FontMap.loadDefault()).run(upload, out);
        if (!report.accepted()) {
            return new Cli(report, null, null, null);
        }
        JsonNode blocks = CLI_BLOCKS.valueToTree(BlocksAnalyzer.analyze(out.resolve("normalized.docx")));
        return new Cli(report, blocks, PdfLines.extract(out.resolve("preview.pdf")), JSON.readTree(out.resolve("baseline.json").toFile()));
    }

    @Test
    void anUploadedResumeOnboardsToExactlyWhatTheCliProduces() throws Exception {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        UUID user = userId(email);
        List<Path> files = new ArrayList<>();
        files.add(Fixtures.phase2().resolve("ok_synthetic.docx"));
        files.add(Fixtures.phase2().resolve("link_in_bullet.docx"));
        files.add(Fixtures.phase2().resolve("unknown_font.docx"));
        files.addAll(Fixtures.corpusDocx());

        for (Path file : files) {
            byte[] bytes = Files.readAllBytes(file);
            String name = file.getFileName().toString();
            Uploaded up = upload(b, name, bytes);

            runJobs();

            JsonNode finished = job(b, up.jobId());
            assertThat(finished.path("status").asText()).as(name + " job").isEqualTo("succeeded");
            Cli expected = cli(bytes);
            assertThat(expected.report().accepted()).as(name + " accepted by the CLI").isTrue();

            // The stored onboarding report is the CLI's onboard.json, field for field.
            JsonNode stored = JSON.readTree(jdbc.queryForObject("SELECT onboard_json::text FROM resumes WHERE id = ?", String.class, up.resumeId()));
            assertThat(stored).as(name + " onboard.json").isEqualTo(JSON.valueToTree(expected.report()));
            // ... and `tailor blocks`.
            JsonNode blocks = JSON.readTree(jdbc.queryForObject("SELECT blocks_json::text FROM resumes WHERE id = ?", String.class, up.resumeId()));
            assertThat(blocks).as(name + " blocks").isEqualTo(expected.blocks());
            // The stored files: preview lines and the baseline measurement match.
            Path preview = Files.createTempFile("preview", ".pdf");
            Files.write(preview, storage.get(StorageKeys.preview(user, up.resumeId())));
            assertThat(PdfLines.extract(preview)).as(name + " preview text and positions").isEqualTo(expected.previewLines());
            assertThat(JSON.readTree(storage.get(StorageKeys.baseline(user, up.resumeId()))))
                    .as(name + " baseline.json").isEqualTo(expected.baseline());
            assertThat(storage.exists(StorageKeys.normalized(user, up.resumeId()))).isTrue();
            assertThat(storage.get(StorageKeys.original(user, up.resumeId()))).as("the original is untouched").isEqualTo(bytes);
            // The renderer version is recorded with the onboarding.
            assertThat(jdbc.queryForObject("SELECT renderer_version FROM resumes WHERE id = ?", String.class, up.resumeId()))
                    .isEqualTo(expected.report().rendererVersion());
        }
    }

    @Test
    void theResumeEndpointShowsTheOnboardingWithoutAnyBulletText() throws Exception {
        TestBrowser b = signedInBrowser(uniqueEmail());
        byte[] bytes = Fixtures.phase2("ok_synthetic.docx");
        Uploaded up = upload(b, "ok.docx", bytes);
        assertThat(resume(b, up.resumeId()).path("status").asText()).isEqualTo("uploaded");
        runJobs();

        TestBrowser.Response r = b.get("/resumes/" + up.resumeId());
        JsonNode view = JSON.readTree(r.body());

        assertThat(view.path("status").asText()).isEqualTo("ready");
        assertThat(view.path("job").path("status").asText()).isEqualTo("succeeded");
        assertThat(view.path("onboarding").path("accepted").asBoolean()).isTrue();
        assertThat(view.path("onboarding").path("editableCount").asInt()).isEqualTo(6);
        assertThat(view.path("onboarding").path("slots").size()).isGreaterThan(0);
        assertThat(view.path("sections").size()).isGreaterThan(0);
        assertThat(view.path("sections").get(0).path("heading").asText()).isNotBlank();
        assertThat(view.has("font_substitutions")).isTrue();
        // Bullet text stays out of this view: the engine's slot texts are not copied into it.
        String slotText = JSON.readTree(jdbc.queryForObject("SELECT onboard_json::text FROM resumes WHERE id = ?", String.class, up.resumeId()))
                .path("slots").get(0).path("text").asText();
        assertThat(slotText).isNotBlank();
        assertThat(r.body()).doesNotContain(slotText);
    }

    @Test
    void theOnboardingJobShowsNamedStagesOnly() throws Exception {
        TestBrowser b = signedInBrowser(uniqueEmail());
        Uploaded up = upload(b, "ok.docx", Fixtures.phase2("ok_synthetic.docx"));
        runJobs();

        JsonNode progress = job(b, up.jobId()).path("progress");

        assertThat(progress.path("stage").asText()).isEqualTo("storing");
        assertThat(progress.has("percent")).isFalse();
    }

    @Test
    void thePreviewLinkDownloadsThePdf() throws Exception {
        TestBrowser b = signedInBrowser(uniqueEmail());
        Uploaded up = upload(b, "ok.docx", Fixtures.phase2("ok_synthetic.docx"));
        runJobs();

        JsonNode link = JSON.readTree(b.get("/resumes/" + up.resumeId() + "/preview").body());
        var response = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(link.path("url").asText())).build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void aResumeTheEngineRefusesIsAResultNotAFailure() throws Exception {
        TestBrowser b = signedInBrowser(uniqueEmail());
        for (String[] c : new String[][] {{"too_few_bullets.docx", "TOO_FEW_EDITABLE"}, {"too_many_pages.docx", "TOO_MANY_PAGES"}}) {
            byte[] bytes = Fixtures.phase2(c[0]);
            Uploaded up = upload(b, c[0], bytes);

            runJobs();

            JsonNode job = job(b, up.jobId());
            assertThat(job.path("status").asText()).as(c[0]).isEqualTo("failed");
            assertThat(job.path("rejected").asBoolean()).as(c[0]).isTrue();
            assertThat(job.path("error_code").asText()).as(c[0]).isEqualTo(c[1]);
            assertThat(job.path("result").path("details").path("message").asText()).as(c[0]).isNotBlank();
            JsonNode view = resume(b, up.resumeId());
            assertThat(view.path("status").asText()).as(c[0]).isEqualTo("rejected");
            assertThat(view.path("onboarding").path("reason").asText()).as(c[0]).isEqualTo(c[1]);
            assertThat(count("SELECT count(*) FROM jobs WHERE id = ? AND attempts = 1", up.jobId())).as("never retried").isEqualTo(1);
            // Nothing derived was stored for a refused resume.
            assertThat(b.get("/resumes/" + up.resumeId() + "/preview").status()).isEqualTo(409);
        }
    }

    @Test
    void acceptingMakesTheResumeActiveAndArchivesTheOldOne() throws Exception {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        UUID user = userId(email);
        Uploaded first = upload(b, "one.docx", Fixtures.phase2("ok_synthetic.docx"));
        Uploaded second = upload(b, "two.docx", Fixtures.phase2("link_in_bullet.docx"));
        runJobs();

        // Not accepted yet: nothing is active.
        assertThat(JSON.readTree(b.get("/me").body()).path("active_resume_id").isMissingNode()).isTrue();

        assertThat(b.postJson("/resumes/" + first.resumeId() + "/accept", "{}").status()).isEqualTo(200);
        assertThat(JSON.readTree(b.get("/me").body()).path("active_resume_id").asText()).isEqualTo(first.resumeId().toString());
        // Accepting twice is harmless.
        assertThat(b.postJson("/resumes/" + first.resumeId() + "/accept", "{}").status()).isEqualTo(200);

        assertThat(b.postJson("/resumes/" + second.resumeId() + "/accept", "{}").status()).isEqualTo(200);
        assertThat(JSON.readTree(b.get("/me").body()).path("active_resume_id").asText()).isEqualTo(second.resumeId().toString());
        assertThat(resume(b, first.resumeId()).path("status").asText()).isEqualTo("archived");
        assertThat(resume(b, first.resumeId()).path("active").asBoolean()).isFalse();
        // Archived, never deleted: the old resume's files are all still there.
        assertThat(storage.exists(StorageKeys.original(user, first.resumeId()))).isTrue();
        assertThat(storage.exists(StorageKeys.normalized(user, first.resumeId()))).isTrue();
        assertThat(storage.exists(StorageKeys.preview(user, first.resumeId()))).isTrue();
        // Accepting confirmed every unconfirmed role as suggested.
        for (JsonNode section : resume(b, second.resumeId()).path("sections")) {
            assertThat(section.path("confirmed_role").asText()).isEqualTo(section.path("suggested_role").asText());
        }
    }

    @Test
    void aResumeThatIsNotReadyCannotBeAccepted() throws Exception {
        TestBrowser b = signedInBrowser(uniqueEmail());
        Uploaded queued = upload(b, "ok.docx", Fixtures.phase2("ok_synthetic.docx"));

        TestBrowser.Response r = b.postJson("/resumes/" + queued.resumeId() + "/accept", "{}");
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).contains("RESUME_NOT_READY");

        Uploaded refused = upload(b, "few.docx", Fixtures.phase2("too_few_bullets.docx"));
        runJobs();
        assertThat(b.postJson("/resumes/" + refused.resumeId() + "/accept", "{}").status()).isEqualTo(409);
    }

    @Test
    void confirmingTheSuggestedRoleChangesNothingAndBadRequestsAreRefused() throws Exception {
        TestBrowser b = signedInBrowser(uniqueEmail());
        Uploaded up = upload(b, "proj.docx", Files.readAllBytes(Fixtures.corpusDocx().get(0)));
        runJobs();
        JsonNode before = resume(b, up.resumeId());
        JsonNode first = before.path("sections").get(0);
        String key = first.path("key").asText();
        String suggested = first.path("suggested_role").asText();
        assertThat(first.path("confirmed_role").isNull() || first.path("confirmed_role").isMissingNode()).isTrue();

        TestBrowser.Response confirm = setRole(b, up.resumeId(), key, suggested);
        assertThat(confirm.status()).isEqualTo(200);
        assertThat(JSON.readTree(confirm.body()).get(0).path("confirmed_role").asText()).isEqualTo(suggested);
        assertThat(resume(b, up.resumeId()).path("blocks")).isEqualTo(before.path("blocks"));

        assertThat(setRole(b, up.resumeId(), key, "nonsense").status()).isEqualTo(400);
        assertThat(setRole(b, up.resumeId(), "s999", "other").status()).isEqualTo(404);
    }

    private JsonNode sectionByHeading(JsonNode resume, String heading) {
        for (JsonNode s : resume.path("sections")) {
            if (heading.equalsIgnoreCase(s.path("heading").asText())) {
                return s;
            }
        }
        throw new AssertionError("no section " + heading + " in " + resume.path("sections"));
    }

    private TestBrowser.Response setRole(TestBrowser b, UUID resume, String key, String role) {
        return b.putJson("/resumes/" + resume + "/sections/" + key + "/role", "{\"role\":\"" + role + "\"}");
    }

    private JsonNode storedReport(UUID resumeId) throws Exception {
        return JSON.readTree(jdbc.queryForObject("SELECT onboard_json::text FROM resumes WHERE id = ?", String.class, resumeId));
    }

    /** PHASE6_SPEC.md revision 3: the user can change a section's role; the position analysis is run again. */
    @Test
    void aSectionRoleCanBeChangedAndThePositionsFollow() throws Exception {
        TestBrowser b = signedInBrowser(uniqueEmail());
        Uploaded up = upload(b, "proj.docx", Fixtures.phase3("projects_synthetic.docx"));
        runJobs();
        JsonNode original = resume(b, up.resumeId());
        String projectsKey = sectionByHeading(original, "Projects").path("key").asText();
        assertThat(sectionByHeading(original, "Projects").path("suggested_role").asText()).isEqualTo("projects");
        assertThat(original.path("blocks").path("positions").size()).isGreaterThan(0);
        JsonNode originalReport = storedReport(up.resumeId());
        assertThat(originalReport.has("sectionRoles")).as("no choice, no extra field").isFalse();

        // Projects to other: there is no projects section any more, and the choice is kept for every later step.
        TestBrowser.Response changed = setRole(b, up.resumeId(), projectsKey, "other");
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        JsonNode view = resume(b, up.resumeId());
        assertThat(sectionByHeading(view, "Projects").path("confirmed_role").asText()).isEqualTo("other");
        assertThat(sectionByHeading(view, "Projects").path("suggested_role").asText()).as("the suggestion is kept").isEqualTo("projects");
        assertThat(view.path("blocks").path("positions")).isEmpty();
        assertThat(view.path("blocks").path("projects_section").isNull() || view.path("blocks").path("projects_section").isMissingNode()).isTrue();
        JsonNode report = storedReport(up.resumeId());
        assertThat(report.path("sectionRoles").path("Projects").asText()).isEqualTo("other");
        ((com.fasterxml.jackson.databind.node.ObjectNode) report).remove("sectionRoles");
        assertThat(report).as("everything else in the report is as onboarding made it").isEqualTo(originalReport);
        assertThat(b.get("/resumes/" + up.resumeId() + "/intake").body()).doesNotContain("\"kind\":\"project\"");

        // And back: the same positions as the vocabulary found, and the report as it was.
        assertThat(setRole(b, up.resumeId(), projectsKey, "projects").status()).isEqualTo(200);
        assertThat(resume(b, up.resumeId()).path("blocks")).isEqualTo(original.path("blocks"));
        assertThat(storedReport(up.resumeId())).isEqualTo(originalReport);
    }

    @Test
    void aSectionNoOneCalledProjectsCanBecomeAProjectsSection() throws Exception {
        TestBrowser b = signedInBrowser(uniqueEmail());
        Uploaded up = upload(b, "proj.docx", Fixtures.phase3("projects_synthetic.docx"));
        runJobs();
        JsonNode original = resume(b, up.resumeId());
        String educationKey = sectionByHeading(original, "Education").path("key").asText();

        assertThat(setRole(b, up.resumeId(), educationKey, "projects").status()).isEqualTo(200);

        JsonNode view = resume(b, up.resumeId());
        assertThat(sectionByHeading(view, "Education").path("confirmed_role").asText()).isEqualTo("projects");
        assertThat(view.path("blocks").path("project_sections").size()).as("Education is a projects section too").isEqualTo(2);
        // Roles are about this resume only.
        TestBrowser other = signedInBrowser(uniqueEmail());
        Uploaded theirs = upload(other, "proj.docx", Fixtures.phase3("projects_synthetic.docx"));
        runJobs();
        JsonNode theirEducation = sectionByHeading(resume(other, theirs.resumeId()), "Education");
        assertThat(theirEducation.path("confirmed_role").isMissingNode() || theirEducation.path("confirmed_role").isNull()).isTrue();
        assertThat(resume(other, theirs.resumeId()).path("blocks")).isEqualTo(original.path("blocks"));
    }

    @Test
    void aFontChoiceIsRefusedUntilTheEngineCanTakeOne() throws Exception {
        TestBrowser b = signedInBrowser(uniqueEmail());
        Uploaded up = upload(b, "ok.docx", Fixtures.phase2("ok_synthetic.docx"));
        runJobs();

        TestBrowser.Response r = b.postJson("/resumes/" + up.resumeId() + "/accept", "{\"font_choice\":\"Liberation Serif\"}");

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).contains("FONT_CHOICE_UNSUPPORTED");
        assertThat(resume(b, up.resumeId()).path("status").asText()).isEqualTo("ready");
    }

    @Test
    void aJobThatDiesWithoutReportingLeavesTheResumeShownAsFailed() throws Exception {
        TestBrowser b = signedInBrowser(uniqueEmail());
        Uploaded up = upload(b, "ok.docx", Fixtures.phase2("ok_synthetic.docx"));
        // The worker vanished twice and the reaper gave up on the job (as it does after the second lost lease).
        jdbc.update("UPDATE jobs SET status = 'failed', error_code = 'WORKER_LOST', attempts = 2 WHERE id = ?", up.jobId());

        JsonNode view = resume(b, up.resumeId());

        assertThat(view.path("status").asText()).isEqualTo("failed");
        assertThat(view.path("job").path("status").asText()).isEqualTo("failed");
    }

    @Test
    void deletingTheAccountRemovesEveryRowAndEveryStoredFile() throws Exception {
        // P6-T14 for what exists so far (snapshots join in later steps).
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        UUID user = userId(email);
        Uploaded up = upload(b, "ok.docx", Fixtures.phase2("ok_synthetic.docx"));
        runJobs();
        assertThat(storage.countPrefix(StorageKeys.userPrefix(user))).isEqualTo(4);

        assertThat(b.delete("/me").status()).isEqualTo(204);

        for (String table : new String[] {"resumes", "section_roles", "intake_sections", "libraries", "library_items",
                "generation_runs", "postings", "matches", "snapshots", "jobs"}) {
            assertThat(count("SELECT count(*) FROM " + table + " WHERE user_id = ?", user)).as(table).isZero();
        }
        // The files go when the cleaner runs (the worker runs it every minute).
        assertThat(storage.countPrefix(StorageKeys.userPrefix(user))).isEqualTo(4);
        assertThat(cleaner.runOnce()).isGreaterThanOrEqualTo(1);
        assertThat(storage.countPrefix(StorageKeys.userPrefix(user))).isZero();
        assertThat(count("SELECT count(*) FROM storage_deletions WHERE prefix = ? AND completed_at IS NOT NULL",
                StorageKeys.userPrefix(user))).isEqualTo(1);
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.tailor.web.storage.StorageCleaner cleaner;
}
