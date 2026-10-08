package com.tailor.web.edit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailor.engine.calibrate.BatchValidator;
import com.tailor.engine.generate.BulletEnding;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.measure.PdfPageCounter;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.slots.BulletText;
import com.tailor.web.auth.TestBrowser;
import com.tailor.web.flow.MatchFlowBase;
import com.tailor.web.storage.StorageKeys;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * P6-T7 and P6-T8 (PHASE6_SPEC.md sections 6 and 12): a check gives the engine's verdict; a revision is a NEW
 * snapshot and leaves its parent byte-identical; a revision that fails is not created; edits never change the
 * library; and regenerating never changes an existing snapshot, edited or not.
 */
@Tag("corpus")
class EditFlowTest extends MatchFlowBase {

    /** A matched account: the fixture library, the "platform" posting matched, resume #1 rendered. */
    private record Matched(Account account, UUID snapshotId, JsonNode view) {
    }

    private Matched matched() throws Exception {
        Seeded s = seededAccount();
        TestBrowser.Response r = post(s.account(), jd("platform"));
        assertThat(r.status()).isEqualTo(202);
        runJobs();
        String postingId = JSON.readTree(r.body()).path("posting_id").asText();
        UUID snapshot = UUID.fromString(JSON.readTree(s.account().browser().get("/postings/" + postingId + "/match").body())
                .path("resume").path("id").asText());
        return new Matched(s.account(), snapshot, snapshotOf(s.account(), snapshot));
    }

    private JsonNode snapshotOf(Account a, UUID id) throws Exception {
        TestBrowser.Response r = a.browser().get("/snapshots/" + id);
        assertThat(r.status()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private JsonNode slot(JsonNode view, int index) {
        for (JsonNode s : view.path("slots")) {
            if (s.path("slot").asInt() == index) {
                return s;
            }
        }
        return null;
    }

    /** The first editable slot with at least {@code minLines} lines (and not in {@code except}). */
    private JsonNode editableSlot(JsonNode view, int minLines, int... except) {
        outer:
        for (JsonNode s : view.path("slots")) {
            if (!s.path("editable").asBoolean() || s.path("lines").asInt() < minLines) {
                continue;
            }
            for (int e : except) {
                if (s.path("slot").asInt() == e) {
                    continue outer;
                }
            }
            return s;
        }
        throw new AssertionError("no editable slot with " + minLines + "+ lines in " + view.path("slots"));
    }

    /**
     * A check waits for the worker's answer, so a worker thread runs while the request is open (the revision tests
     * run their jobs by hand instead, to look at the state in between).
     */
    private TestBrowser.Response check(Matched m, int slot, String text) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        Thread worker = new Thread(() -> {
            while (!stop.get()) {
                if (!processor.runNext()) {
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "test-worker");
        worker.setDaemon(true);
        worker.start();
        try {
            return m.account().browser().postJson("/snapshots/" + m.snapshotId() + "/slots/" + slot + "/check", "{\"text\":" + quote(text) + "}");
        } finally {
            stop.set(true);
            worker.join(10_000);
        }
    }

    private UUID revise(Matched m, UUID parent, String editsJson) throws Exception {
        TestBrowser.Response r = m.account().browser().postJson("/snapshots/" + parent + "/revisions", "{\"edits\":" + editsJson + "}");
        assertThat(r.status()).as(r.body()).isEqualTo(202);
        runJobs();
        return UUID.fromString(JSON.readTree(r.body()).path("job_id").asText());
    }

    private JsonNode job(Account a, UUID id) throws Exception {
        return JSON.readTree(a.browser().get("/jobs/" + id).body());
    }

    private String snapshotRow(UUID id) {
        return jdbc.queryForObject("SELECT row_to_json(s)::text FROM snapshots s WHERE id = ?", String.class, id);
    }

    private static byte[] download(String url) throws Exception {
        HttpResponse<byte[]> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(r.statusCode()).isEqualTo(200);
        return r.body();
    }

    private byte[] pdfOf(Account a, UUID snapshot) throws Exception {
        return download(JSON.readTree(a.browser().get("/snapshots/" + snapshot + "/pdf").body()).path("url").asText());
    }

    private static List<String> lineTexts(byte[] pdf) throws Exception {
        Path file = Files.createTempFile("snapshot", ".pdf");
        Files.write(file, pdf);
        return PdfLines.extract(file).stream().map(PdfLines.Line::normalizedText).collect(Collectors.toList());
    }

    private static int pageCount(byte[] pdf) throws Exception {
        Path file = Files.createTempFile("snapshot", ".pdf");
        Files.write(file, pdf);
        return PdfPageCounter.count(file);
    }

    private String joined(byte[] pdf) throws Exception {
        return String.join(" ", lineTexts(pdf));
    }

    // ---- the snapshot's slots ------------------------------------------------------------------

    @Test
    void theSnapshotListsItsJobAndProjectBulletsWithTheirHints() throws Exception {
        Matched m = matched();

        JsonNode slots = m.view().path("slots");

        assertThat(slots.size()).isGreaterThan(5);
        boolean job = false;
        boolean project = false;
        for (JsonNode s : slots) {
            job |= s.path("kind").asText().equals("job");
            project |= s.path("kind").asText().equals("project");
            assertThat(s.path("text").asText()).as("slot " + s.path("slot")).isNotNull();
            if (s.path("editable").asBoolean()) {
                assertThat(s.path("lines").asInt()).isGreaterThan(0);
                assertThat(s.path("hintChars").asInt()).isGreaterThan(0);
            }
        }
        assertThat(job).isTrue();
        assertThat(project).isTrue();
        assertThat(m.view().path("latest_revision_id").asText()).isEqualTo(m.snapshotId().toString());
        // The text shown for a slot is what the PDF has.
        String pdf = joined(pdfOf(m.account(), m.snapshotId()));
        JsonNode first = editableSlot(m.view(), 1);
        assertThat(pdf).contains(first.path("text").asText().split(" ")[0]);
    }

    // ---- check ---------------------------------------------------------------------------------

    @Test
    void checkVerdictsMatchTheEngineOnFixtureSlots() throws Exception {
        Matched m = matched();
        Account a = m.account();
        Path dir = Files.createTempDirectory("check-vs-engine");
        Path parentDocx = dir.resolve("parent.docx");
        Files.write(parentDocx, storage.get(StorageKeys.snapshotDocx(a.userId(), m.snapshotId())));
        OnboardReport report = new com.fasterxml.jackson.databind.ObjectMapper().treeToValue(JSON.readTree(
                jdbc.queryForObject("SELECT onboard_json::text FROM resumes WHERE id = ?", String.class, a.resumeId())), OnboardReport.class);
        Map<Integer, Integer> lines = new HashMap<>();
        Map<Integer, Integer> hints = new HashMap<>();
        report.slots().forEach(s -> {
            lines.put(s.index(), s.lines());
            hints.put(s.index(), s.hintChars());
        });
        String convention = BulletEnding.convention(report.slots().stream().map(OnboardReport.SlotReport::text).toList());

        int checked = 0;
        for (JsonNode slot : m.view().path("slots")) {
            if (!slot.path("editable").asBoolean() || checked >= 4) {
                continue;
            }
            int index = slot.path("slot").asInt();
            String own = slot.path("text").asText();
            for (String text : List.of(own, String.join(" ", List.of(own.split(" ")).subList(0, Math.min(3, own.split(" ").length))),
                    own + " " + own + " " + own)) {
                String normalized = EditText.normalize(text, convention).text();
                TestBrowser.Response r = check(m, index, text);
                assertThat(r.status()).as("slot " + index).isEqualTo(200);
                JsonNode got = JSON.readTree(r.body());

                BatchValidator.CandidateResult engine = BatchValidator.validate(parentDocx, new LibreOfficeRenderer(), lines, hints,
                        Map.of(index, List.of(new BulletText(normalized, List.of()))), dir).get(0);
                String expected = switch (engine.outcome()) {
                    case FITS -> "FITS";
                    case FITS_WITH_PADDING -> "FITS_WITH_PADDING";
                    default -> "TOO_LONG";
                };
                assertThat(got.path("verdict").asText()).as("slot " + index + " text '" + text.length() + " chars'").isEqualTo(expected);
                if (engine.measuredLines() != null) {
                    assertThat(got.path("measured_lines").asInt()).isEqualTo(engine.measuredLines());
                }
                assertThat(got.path("target_lines").asInt()).isEqualTo(lines.get(index));
            }
            checked++;
        }
        assertThat(checked).isGreaterThan(0);
        // A check stores nothing: no snapshot, no revision, no file.
        assertThat(count("SELECT count(*) FROM snapshots WHERE parent_id IS NOT NULL AND user_id = ?", a.userId())).isZero();
    }

    @Test
    void aBlankAlwaysFitsWithoutRendering() throws Exception {
        Matched m = matched();
        int slot = editableSlot(m.view(), 1).path("slot").asInt();
        long jobsBefore = count("SELECT count(*) FROM jobs WHERE type = 'edit_revision'");

        TestBrowser.Response r = check(m, slot, "  \t\n ");

        assertThat(r.status()).isEqualTo(200);
        assertThat(JSON.readTree(r.body()).path("verdict").asText()).isEqualTo("FITS");
        assertThat(count("SELECT count(*) FROM jobs WHERE type = 'edit_revision'")).isEqualTo(jobsBefore);
    }

    @Test
    void checksAreLimitedToThirtyAMinute() throws Exception {
        Matched m = matched();
        int slot = editableSlot(m.view(), 1).path("slot").asInt();
        for (int i = 0; i < 30; i++) {
            jdbc.update("INSERT INTO rate_events (bucket, subject, created_at) VALUES ('slot-check', ?, now())", m.account().userId().toString());
        }

        TestBrowser.Response r = check(m, slot, "short text");

        assertThat(r.status()).isEqualTo(429);
        assertThat(JSON.readTree(r.body()).path("code").asText()).isEqualTo("RATE_LIMITED");
    }

    // ---- revisions -----------------------------------------------------------------------------

    @Test
    void aRevisionIsANewSnapshotAndTheParentStaysByteIdentical() throws Exception {
        Matched m = matched();
        Account a = m.account();
        JsonNode target = editableSlot(m.view(), 1);
        int index = target.path("slot").asInt();
        String words = target.path("text").asText().split(" ")[0];
        String newText = "Reworked " + words.toLowerCase() + " with my own words and a real result worth keeping";

        String parentRow = snapshotRow(m.snapshotId());
        byte[] parentDocx = storage.get(StorageKeys.snapshotDocx(a.userId(), m.snapshotId()));
        byte[] parentPdf = storage.get(StorageKeys.snapshotPdf(a.userId(), m.snapshotId()));
        String libraryBefore = jdbc.queryForObject("SELECT string_agg(library_id || section_id || candidate_id || length || text, ',' ORDER BY library_id, section_id, candidate_id, length)"
                + " FROM library_items WHERE user_id = ?", String.class, a.userId());
        long snapshotsBefore = count("SELECT count(*) FROM snapshots WHERE user_id = ?", a.userId());
        long ledgerBefore = count("SELECT count(*) FROM usage_ledger WHERE user_id = ?", a.userId());

        UUID jobId = revise(m, m.snapshotId(), "[{\"slot\":" + index + ",\"text\":" + quote(newText) + "}]");

        JsonNode job = job(a, jobId);
        assertThat(job.path("status").asText()).isEqualTo("succeeded");
        UUID revision = UUID.fromString(job.path("result").path("snapshotId").asText());
        assertThat(count("SELECT count(*) FROM snapshots WHERE user_id = ?", a.userId())).isEqualTo(snapshotsBefore + 1);

        // The parent: not one byte, not one column.
        assertThat(snapshotRow(m.snapshotId())).isEqualTo(parentRow);
        assertThat(storage.get(StorageKeys.snapshotDocx(a.userId(), m.snapshotId()))).isEqualTo(parentDocx);
        assertThat(storage.get(StorageKeys.snapshotPdf(a.userId(), m.snapshotId()))).isEqualTo(parentPdf);
        assertThat(pdfOf(a, m.snapshotId())).isEqualTo(parentPdf);

        // The revision: linked to the parent, verified, rendered, with the edit recorded.
        JsonNode rev = snapshotOf(a, revision);
        assertThat(rev.path("parent_id").asText()).isEqualTo(m.snapshotId().toString());
        assertThat(rev.path("rank").asInt()).isEqualTo(m.view().path("rank").asInt());
        assertThat(rev.path("status").asText()).isEqualTo("rendered");
        assertThat(rev.path("edits").get(0).path("slot").asInt()).isEqualTo(index);
        assertThat(rev.path("edits").get(0).path("text").asText()).startsWith("Reworked");
        assertThat(slot(rev, index).path("text").asText()).startsWith("Reworked");
        // Every other bullet is exactly as in the parent.
        for (JsonNode s : m.view().path("slots")) {
            if (s.path("slot").asInt() != index) {
                assertThat(slot(rev, s.path("slot").asInt()).path("text").asText()).isEqualTo(s.path("text").asText());
            }
        }
        byte[] revPdf = pdfOf(a, revision);
        assertThat(pageCount(revPdf)).isEqualTo(pageCount(parentPdf));
        assertThat(joined(revPdf)).contains("Reworked");
        assertThat(joined(parentPdf)).doesNotContain("Reworked");

        // The chain: the parent lists it, and the newest is what is offered by default.
        JsonNode parent = snapshotOf(a, m.snapshotId());
        assertThat(parent.path("revisions").get(0).asText()).isEqualTo(revision.toString());
        assertThat(parent.path("latest_revision_id").asText()).isEqualTo(revision.toString());
        String postingId = jdbc.queryForObject("SELECT posting_id FROM matches WHERE user_id = ?", String.class, a.userId());
        assertThat(JSON.readTree(a.browser().get("/postings/" + postingId + "/match").body()).path("resume").path("latest_id").asText())
                .isEqualTo(revision.toString());

        // Edits apply to this resume only: the library is unchanged and nothing was charged.
        assertThat(jdbc.queryForObject("SELECT string_agg(library_id || section_id || candidate_id || length || text, ',' ORDER BY library_id, section_id, candidate_id, length)"
                + " FROM library_items WHERE user_id = ?", String.class, a.userId())).isEqualTo(libraryBefore);
        assertThat(count("SELECT count(*) FROM usage_ledger WHERE user_id = ?", a.userId())).isEqualTo(ledgerBefore);
    }

    @Test
    void editsArePaddedBlankedAndNormalizedAndEndedLikeTheResume() throws Exception {
        Matched m = matched();
        Account a = m.account();
        JsonNode padded = editableSlot(m.view(), 2);
        JsonNode blanked = editableSlot(m.view(), 1, padded.path("slot").asInt());
        JsonNode plain = editableSlot(m.view(), 1, padded.path("slot").asInt(), blanked.path("slot").asInt());
        byte[] parentPdf = pdfOf(a, m.snapshotId());

        UUID jobId = revise(m, m.snapshotId(), "["
                + "{\"slot\":" + padded.path("slot").asInt() + ",\"text\":\"Shorter\\nthan\\tits\\u0020\\u0020slot\"},"
                + "{\"slot\":" + blanked.path("slot").asInt() + ",\"text\":\"   \"},"
                + "{\"slot\":" + plain.path("slot").asInt() + ",\"text\":\"  Kept a tidy sentence  \"}]");

        JsonNode job = job(a, jobId);
        assertThat(job.path("status").asText()).as(job.toString()).isEqualTo("succeeded");
        UUID revision = UUID.fromString(job.path("result").path("snapshotId").asText());
        JsonNode edits = snapshotOf(a, revision).path("edits");
        Map<Integer, JsonNode> byslot = new HashMap<>();
        edits.forEach(e -> byslot.put(e.path("slot").asInt(), e));

        // The padded slot holds its height with filler; the blank holds its height empty.
        assertThat(byslot.get(padded.path("slot").asInt()).path("result").asText()).isEqualTo("FITS_WITH_PADDING");
        assertThat(byslot.get(blanked.path("slot").asInt()).path("result").asText()).isEqualTo("BLANKED");
        assertThat(byslot.get(blanked.path("slot").asInt()).path("text").asText()).isEmpty();
        // Newlines and tabs became spaces and runs of spaces collapsed.
        String paddedText = byslot.get(padded.path("slot").asInt()).path("text").asText();
        assertThat(paddedText).startsWith("Shorter than its slot");
        // The ending follows the resume's convention.
        OnboardReport report = new com.fasterxml.jackson.databind.ObjectMapper().treeToValue(JSON.readTree(
                jdbc.queryForObject("SELECT onboard_json::text FROM resumes WHERE id = ?", String.class, a.resumeId())), OnboardReport.class);
        String convention = BulletEnding.convention(report.slots().stream().map(OnboardReport.SlotReport::text).toList());
        assertThat(byslot.get(plain.path("slot").asInt()).path("text").asText())
                .isEqualTo(BulletEnding.apply("Kept a tidy sentence", convention));

        byte[] revPdf = pdfOf(a, revision);
        assertThat(pageCount(revPdf)).isEqualTo(pageCount(parentPdf));
        assertThat(joined(revPdf)).doesNotContain(blanked.path("text").asText());
        assertThat(slot(snapshotOf(a, revision), blanked.path("slot").asInt()).path("text").asText().replace(' ', ' ').strip()).isEmpty();
    }

    @Test
    void aBlankedBulletCanGetTextAgainInALaterRevision() throws Exception {
        Matched m = matched();
        Account a = m.account();
        JsonNode target = editableSlot(m.view(), 1);
        int index = target.path("slot").asInt();
        UUID first = UUID.fromString(job(a, revise(m, m.snapshotId(), "[{\"slot\":" + index + ",\"text\":\"\"}]"))
                .path("result").path("snapshotId").asText());
        String firstRow = snapshotRow(first);

        UUID secondJob = revise(m, first, "[{\"slot\":" + index + ",\"text\":\"Back again with new words here\"}]");

        JsonNode job = job(a, secondJob);
        assertThat(job.path("status").asText()).as(job.toString()).isEqualTo("succeeded");
        UUID second = UUID.fromString(job.path("result").path("snapshotId").asText());
        assertThat(snapshotOf(a, second).path("parent_id").asText()).isEqualTo(first.toString());
        assertThat(slot(snapshotOf(a, second), index).path("text").asText()).startsWith("Back again");
        assertThat(joined(pdfOf(a, second))).as("PDF line text has its spaces stripped").contains("Backagainwithnewwords");
        // The chain, newest last; the earlier revision is untouched.
        assertThat(snapshotRow(first)).isEqualTo(firstRow);
        assertThat(snapshotOf(a, m.snapshotId()).path("latest_revision_id").asText()).isEqualTo(second.toString());
    }

    @Test
    void aRevisionThatFailsIsNotCreatedAndStoresNothing() throws Exception {
        Matched m = matched();
        Account a = m.account();
        JsonNode target = editableSlot(m.view(), 1);
        int index = target.path("slot").asInt();
        long snapshotsBefore = count("SELECT count(*) FROM snapshots WHERE user_id = ?", a.userId());
        int filesBefore = storage.countPrefix(StorageKeys.userPrefix(a.userId()) + "snapshots/");
        String tooLong = ("Overlong " + "words that cannot possibly fit within the slot ").repeat(8);

        UUID jobId = revise(m, m.snapshotId(), "[{\"slot\":" + index + ",\"text\":" + quote(tooLong) + "}]");

        JsonNode job = job(a, jobId);
        assertThat(job.path("status").asText()).isEqualTo("failed");
        assertThat(job.path("rejected").asBoolean()).isTrue();
        assertThat(job.path("error_code").asText()).isEqualTo("TOO_LONG");
        assertThat(job.path("result").path("details").path("slot").asInt()).isEqualTo(index);
        assertThat(job.path("result").path("details").path("reason").asText()).isNotBlank();
        // Nothing downloadable, no row, no file, and the failure was not retried.
        assertThat(count("SELECT count(*) FROM snapshots WHERE user_id = ?", a.userId())).isEqualTo(snapshotsBefore);
        assertThat(storage.countPrefix(StorageKeys.userPrefix(a.userId()) + "snapshots/")).isEqualTo(filesBefore);
        assertThat(count("SELECT attempts FROM jobs WHERE id = ?", jobId)).isEqualTo(1);
    }

    @Test
    void oneBadEditStopsTheWholeRevision() throws Exception {
        Matched m = matched();
        Account a = m.account();
        JsonNode good = editableSlot(m.view(), 1);
        JsonNode bad = editableSlot(m.view(), 1, good.path("slot").asInt());
        long before = count("SELECT count(*) FROM snapshots WHERE user_id = ?", a.userId());

        UUID jobId = revise(m, m.snapshotId(), "[{\"slot\":" + good.path("slot").asInt() + ",\"text\":\"A fine short edit\"},"
                + "{\"slot\":" + bad.path("slot").asInt() + ",\"text\":" + quote("far too long ".repeat(60)) + "}]");

        assertThat(job(a, jobId).path("error_code").asText()).isEqualTo("TOO_LONG");
        assertThat(job(a, jobId).path("result").path("details").path("slot").asInt()).isEqualTo(bad.path("slot").asInt());
        assertThat(count("SELECT count(*) FROM snapshots WHERE user_id = ?", a.userId())).isEqualTo(before);
    }

    // ---- what is refused before anything renders -----------------------------------------------

    @Test
    void anEditOverAThousandCharactersIsRefusedBeforeAnyRender() throws Exception {
        Matched m = matched();
        int slot = editableSlot(m.view(), 1).path("slot").asInt();
        long jobsBefore = count("SELECT count(*) FROM jobs WHERE type = 'edit_revision'");
        String long1001 = "a".repeat(1001);

        TestBrowser.Response check = check(m, slot, long1001);
        TestBrowser.Response revision = m.account().browser().postJson("/snapshots/" + m.snapshotId() + "/revisions",
                "{\"edits\":[{\"slot\":" + slot + ",\"text\":\"" + long1001 + "\"}]}");

        for (TestBrowser.Response r : List.of(check, revision)) {
            assertThat(r.status()).isEqualTo(422);
            assertThat(JSON.readTree(r.body()).path("code").asText()).isEqualTo("EDIT_TOO_LONG");
            assertThat(JSON.readTree(r.body()).path("details").path("slot").asInt()).isEqualTo(slot);
        }
        assertThat(count("SELECT count(*) FROM jobs WHERE type = 'edit_revision'")).isEqualTo(jobsBefore);
        // Exactly 1,000 characters is allowed (it then fails to fit, which is a different answer).
        assertThat(check(m, slot, "a".repeat(1000)).status()).isNotEqualTo(422);
    }

    @Test
    void onlyEditableJobAndProjectBulletsCanBeEdited() throws Exception {
        Matched m = matched();
        Account a = m.account();
        JsonNode editable = editableSlot(m.view(), 1);

        assertThat(check(m, 9999, "text").status()).isEqualTo(404);
        assertThat(check(m, -1, "text").status()).isEqualTo(404);
        // Duplicate slots in one revision, and an empty revision.
        int slot = editable.path("slot").asInt();
        TestBrowser.Response dup = a.browser().postJson("/snapshots/" + m.snapshotId() + "/revisions",
                "{\"edits\":[{\"slot\":" + slot + ",\"text\":\"a\"},{\"slot\":" + slot + ",\"text\":\"b\"}]}");
        assertThat(dup.status()).isEqualTo(400);
        assertThat(a.browser().postJson("/snapshots/" + m.snapshotId() + "/revisions", "{\"edits\":[]}").status()).isEqualTo(400);
        assertThat(a.browser().postJson("/snapshots/" + m.snapshotId() + "/revisions", "{}").status()).isEqualTo(400);
        // A locked slot, if this resume has one among its job and project bullets.
        for (JsonNode s : m.view().path("slots")) {
            if (!s.path("editable").asBoolean()) {
                TestBrowser.Response locked = check(m, s.path("slot").asInt(), "text");
                assertThat(locked.status()).isEqualTo(422);
                assertThat(JSON.readTree(locked.body()).path("code").asText()).isEqualTo("SLOT_LOCKED");
                break;
            }
        }
        assertThat(count("SELECT count(*) FROM snapshots WHERE parent_id IS NOT NULL AND user_id = ?", a.userId())).isZero();
    }

    @Test
    void anAlternativeCanOnlyBeEditedOnceItIsRendered() throws Exception {
        Matched m = matched();
        Account a = m.account();
        String postingId = jdbc.queryForObject("SELECT posting_id FROM matches WHERE user_id = ?", String.class, a.userId());
        String alt = JSON.readTree(a.browser().get("/postings/" + postingId + "/match").body()).path("alternatives").get(0).path("id").asText();

        TestBrowser.Response r = a.browser().postJson("/snapshots/" + alt + "/revisions", "{\"edits\":[{\"slot\":0,\"text\":\"x\"}]}");

        assertThat(r.status()).isEqualTo(409);
        assertThat(JSON.readTree(r.body()).path("code").asText()).isEqualTo("SNAPSHOT_NOT_RENDERED");
    }

    @Test
    void aRetriedRevisionRequestMakesOneRevision() throws Exception {
        Matched m = matched();
        Account a = m.account();
        int slot = editableSlot(m.view(), 1).path("slot").asInt();
        String body = "{\"edits\":[{\"slot\":" + slot + ",\"text\":\"Once only\"}]}";
        TestBrowser b = a.browser();

        var first = postWithKey(b, "/snapshots/" + m.snapshotId() + "/revisions", body, "rev-1");
        var again = postWithKey(b, "/snapshots/" + m.snapshotId() + "/revisions", body, "rev-1");
        runJobs();

        assertThat(first.status()).isEqualTo(202);
        assertThat(JSON.readTree(again.body()).path("job_id").asText()).isEqualTo(JSON.readTree(first.body()).path("job_id").asText());
        assertThat(JSON.readTree(again.body()).path("snapshot_id").asText()).isEqualTo(JSON.readTree(first.body()).path("snapshot_id").asText());
        assertThat(count("SELECT count(*) FROM snapshots WHERE parent_id = ?", m.snapshotId())).isEqualTo(1);
    }

    @Test
    void anotherUserGets404OnCheckAndRevisions() throws Exception {
        Matched m = matched();
        int slot = editableSlot(m.view(), 1).path("slot").asInt();
        TestBrowser stranger = signedInBrowser(uniqueEmail());
        long jobsBefore = count("SELECT count(*) FROM jobs WHERE type = 'edit_revision'");

        TestBrowser.Response c = stranger.postJson("/snapshots/" + m.snapshotId() + "/slots/" + slot + "/check", "{\"text\":\"x\"}");
        TestBrowser.Response r = stranger.postJson("/snapshots/" + m.snapshotId() + "/revisions", "{\"edits\":[{\"slot\":" + slot + ",\"text\":\"x\"}]}");
        TestBrowser.Response nothing = stranger.postJson("/snapshots/" + UUID.randomUUID() + "/revisions", "{\"edits\":[{\"slot\":" + slot + ",\"text\":\"x\"}]}");

        assertThat(c.status()).isEqualTo(404);
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.body()).isEqualTo(nothing.body());
        assertThat(count("SELECT count(*) FROM jobs WHERE type = 'edit_revision'")).isEqualTo(jobsBefore);
    }

    // ---- P6-T8 ---------------------------------------------------------------------------------

    @Test
    void regeneratingNeverChangesAnExistingSnapshotEditedOrNot() throws Exception {
        Matched m = matched();
        Account a = m.account();
        int slot = editableSlot(m.view(), 1).path("slot").asInt();
        UUID revision = UUID.fromString(job(a, revise(m, m.snapshotId(), "[{\"slot\":" + slot + ",\"text\":\"Hand written line\"}]"))
                .path("result").path("snapshotId").asText());
        Map<UUID, String> rows = new HashMap<>();
        Map<UUID, byte[]> pdfs = new HashMap<>();
        for (UUID id : List.of(m.snapshotId(), revision)) {
            rows.put(id, snapshotRow(id));
            pdfs.put(id, storage.get(StorageKeys.snapshotPdf(a.userId(), id)));
        }

        // The user's material changes: a new library version (a variant removed), then the same posting again.
        JsonNode library = JSON.readTree(a.browser().get("/resumes/" + a.resumeId() + "/library").body());
        String variantId = null;
        for (JsonNode section : library.path("sections")) {
            if (section.path("candidates").size() > 0) {
                variantId = section.path("candidates").get(0).path("variants").get(0).path("id").asText();
                break;
            }
        }
        assertThat(a.browser().postJson("/libraries/" + library.path("library_id").asText() + "/variants/" + variantId + "/remove", "{}").status()).isEqualTo(200);
        TestBrowser.Response again = post(a, jd("platform"));
        assertThat(again.status()).as("a new library version means a new match").isEqualTo(202);
        runJobs();

        // Old snapshots, edited and not: unchanged and still downloadable. The new match has new snapshots.
        for (UUID id : rows.keySet()) {
            assertThat(snapshotRow(id)).isEqualTo(rows.get(id));
            assertThat(pdfOf(a, id)).isEqualTo(pdfs.get(id));
            assertThat(storage.get(StorageKeys.snapshotPdf(a.userId(), id))).isEqualTo(pdfs.get(id));
        }
        String newPosting = JSON.readTree(again.body()).path("posting_id").asText();
        UUID fresh = UUID.fromString(JSON.readTree(a.browser().get("/postings/" + newPosting + "/match").body()).path("resume").path("id").asText());
        assertThat(rows).doesNotContainKey(fresh);
        assertThat(slot(snapshotOf(a, fresh), slot).path("text").asText()).isNotEqualTo("Hand written line");
        assertThat(snapshotOf(a, m.snapshotId()).path("latest_revision_id").asText()).isEqualTo(revision.toString());
    }

    private static TestBrowser.Response postWithKey(TestBrowser b, String path, String body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(FLOW_BASE + path))
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .header("Cookie", "SESSION=" + b.cookie("SESSION") + "; XSRF-TOKEN=" + b.cookie("XSRF-TOKEN"))
                .header("X-XSRF-TOKEN", b.cookie("XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return new TestBrowser.Response(response.statusCode(), response.body(), response.headers());
    }
}
