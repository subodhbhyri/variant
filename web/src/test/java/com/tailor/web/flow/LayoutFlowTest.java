package com.tailor.web.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailor.web.auth.TestBrowser;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PHASE6_SPEC.md revision 3, the page layout for the UI: pages in PDF points, every bullet slot and position with the page and
 * box of each line, editable or why not, and, for a snapshot, what changed from the onboarded resume. The numbers are the
 * engine's own measurement of the stored PDF.
 */
@Tag("corpus")
class LayoutFlowTest extends MatchFlowBase {

    private JsonNode get(TestBrowser b, String path) throws Exception {
        TestBrowser.Response r = b.get(path);
        assertThat(r.status()).as(path + " " + r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private record Matched(Account account, String postingId, String snapshotId) {
    }

    private Matched matched() throws Exception {
        Seeded s = seededAccount();
        TestBrowser.Response posted = post(s.account(), jd("platform"));
        String postingId = JSON.readTree(posted.body()).path("posting_id").asText();
        runJobs();
        String snapshot = get(s.account().browser(), "/postings/" + postingId + "/match").path("resume").path("id").asText();
        return new Matched(s.account(), postingId, snapshot);
    }

    private static void assertGeometry(JsonNode layout) {
        assertThat(layout.path("pages").size()).isGreaterThanOrEqualTo(1);
        for (JsonNode page : layout.path("pages")) {
            assertThat(page.path("width").asDouble()).isBetween(500.0, 650.0);
            assertThat(page.path("height").asDouble()).isBetween(700.0, 850.0);
        }
        for (JsonNode slot : layout.path("slots")) {
            int count = slot.path("line_count").isNull() || slot.path("line_count").isMissingNode() ? 0 : slot.path("line_count").asInt();
            assertThat(slot.path("lines").size()).as("slot " + slot.path("slot")).isEqualTo(count);
            for (JsonNode box : slot.path("lines")) {
                JsonNode page = layout.path("pages").get(box.path("page").asInt());
                assertThat(box.path("w").asDouble()).isGreaterThan(0);
                assertThat(box.path("h").asDouble()).isGreaterThan(0);
                assertThat(box.path("x").asDouble() + box.path("w").asDouble()).isLessThanOrEqualTo(page.path("width").asDouble() + 1);
                assertThat(box.path("y").asDouble() + box.path("h").asDouble()).isLessThanOrEqualTo(page.path("height").asDouble() + 1);
            }
        }
    }

    @Test
    void aSnapshotsLayoutHasPagesBoxesLocksAndPositions() throws Exception {
        Matched m = matched();
        JsonNode layout = get(m.account().browser(), "/snapshots/" + m.snapshotId() + "/layout");
        assertGeometry(layout);

        // The same slots as the snapshot lists, with the same verdict on editing.
        JsonNode snapshot = get(m.account().browser(), "/snapshots/" + m.snapshotId());
        for (JsonNode listed : snapshot.path("slots")) {
            JsonNode slot = null;
            for (JsonNode candidate : layout.path("slots")) {
                if (candidate.path("slot").asInt() == listed.path("slot").asInt()) {
                    slot = candidate;
                }
            }
            assertThat(slot).as("slot " + listed.path("slot")).isNotNull();
            assertThat(slot.path("editable").asBoolean()).isEqualTo(listed.path("editable").asBoolean());
            assertThat(slot.path("lock_reason").asText("")).isEqualTo(listed.path("lock_reason").asText(""));
            assertThat(slot.path("kind").asText()).isEqualTo(listed.path("kind").asText());
            if (listed.path("editable").asBoolean()) {
                assertThat(slot.path("line_count").asInt()).as("the calibrated line count").isEqualTo(listed.path("lines").asInt());
            }
        }
        assertThat(layout.path("positions").size()).isGreaterThanOrEqualTo(2);
        boolean project = false;
        for (JsonNode position : layout.path("positions")) {
            assertThat(position.path("lines").size()).as(position.path("id").asText()).isGreaterThan(0);
            project |= position.path("id").asText().startsWith("P");
        }
        assertThat(project).isTrue();
    }

    @Test
    void theChangesNameTheRewrittenBulletsTheProjectsPlacedAndTheirPreviousOccupants() throws Exception {
        Matched m = matched();
        JsonNode layout = get(m.account().browser(), "/snapshots/" + m.snapshotId() + "/layout");
        JsonNode changes = layout.path("changes");
        assertThat(changes.isArray()).isTrue();
        JsonNode report = JSON.readTree(jdbc.queryForObject("SELECT onboard_json::text FROM resumes WHERE id = ?", String.class, m.account().resumeId()));

        int rewritten = 0;
        int placed = 0;
        for (JsonNode change : changes) {
            switch (change.path("type").asText()) {
                case "bullet_rewritten" -> {
                    rewritten++;
                    int slot = change.path("slot").asInt();
                    assertThat(change.path("old_text").asText()).as("old text is the onboarded bullet")
                            .isEqualTo(report.path("slots").get(slot).path("text").asText());
                    assertThat(change.path("new_text").asText()).isNotEqualTo(change.path("old_text").asText());
                    assertThat(change.path("words_added").asInt()).isBetween(0, change.path("new_text").asText().split("\\s+").length);
                    assertThat(change.path("lines").asInt()).isGreaterThan(0);
                    assertThat(change.path("position").asText()).isNotBlank();
                }
                case "project_placed" -> {
                    placed++;
                    assertThat(change.path("position").asText()).matches("P\\d+");
                    assertThat(change.path("project").asText()).as("a title, not a library id").isNotBlank().doesNotContain("project-new");
                    assertThat(change.path("previous").asText()).as("the title the position had").isNotBlank();
                }
                case "stack_trimmed" -> {
                    assertThat(change.path("dropped").size()).isGreaterThan(0);
                    assertThat(change.path("kept").isArray()).isTrue();
                }
                default -> throw new AssertionError("unknown change " + change);
            }
        }
        assertThat(rewritten).as("the match rewrote job bullets").isGreaterThan(0);
        JsonNode assembly = JSON.readTree(jdbc.queryForObject("SELECT assembly::text FROM snapshots WHERE id = ?::uuid", String.class, m.snapshotId()));
        assertThat(placed).isEqualTo(assembly.path("projects").size());
    }

    @Test
    void anEditedRevisionShowsItsEditAsAChangeAndLeavesTheParentsLayoutAlone() throws Exception {
        Matched m = matched();
        TestBrowser b = m.account().browser();
        JsonNode before = get(b, "/snapshots/" + m.snapshotId() + "/layout");
        JsonNode snapshot = get(b, "/snapshots/" + m.snapshotId());
        JsonNode slot = null;
        for (JsonNode s : snapshot.path("slots")) {
            if (s.path("editable").asBoolean() && s.path("lines").asInt() >= 1) {
                slot = s;
                break;
            }
        }
        assertThat(slot).isNotNull();
        String text = "Reworked in my own words with a result worth keeping";
        TestBrowser.Response r = b.postJson("/snapshots/" + m.snapshotId() + "/revisions",
                "{\"edits\":[{\"slot\":" + slot.path("slot").asInt() + ",\"text\":" + quote(text) + "}]}");
        assertThat(r.status()).isEqualTo(202);
        String revision = JSON.readTree(r.body()).path("snapshot_id").asText();
        runJobs();

        JsonNode layout = get(b, "/snapshots/" + revision + "/layout");
        assertGeometry(layout);
        JsonNode edit = null;
        for (JsonNode change : layout.path("changes")) {
            if ("bullet_rewritten".equals(change.path("type").asText()) && change.path("slot").asInt() == slot.path("slot").asInt()) {
                edit = change;
            }
        }
        assertThat(edit).isNotNull();
        assertThat(edit.path("new_text").asText()).as("the engine adds the resume's bullet ending").startsWith(text);
        assertThat(edit.path("words_added").asInt()).isGreaterThan(0);
        // The parent is as it was, to the digit.
        assertThat(get(b, "/snapshots/" + m.snapshotId() + "/layout")).isEqualTo(before);
    }

    @Test
    void anAlternativeIsNotMeasuredUntilItIsRenderedAndOthersGetA404() throws Exception {
        Matched m = matched();
        TestBrowser b = m.account().browser();
        JsonNode match = get(b, "/postings/" + m.postingId() + "/match");
        if (match.path("alternatives").size() > 0) {
            String alternative = match.path("alternatives").get(0).path("id").asText();
            TestBrowser.Response r = b.get("/snapshots/" + alternative + "/layout");
            assertThat(r.status()).isEqualTo(409);
            assertThat(JSON.readTree(r.body()).path("code").asText()).isEqualTo("SNAPSHOT_NOT_RENDERED");
        }
        TestBrowser stranger = signedInBrowser(uniqueEmail());
        TestBrowser.Response theirs = stranger.get("/snapshots/" + m.snapshotId() + "/layout");
        TestBrowser.Response nothing = stranger.get("/snapshots/" + UUID.randomUUID() + "/layout");
        assertThat(theirs.status()).isEqualTo(404);
        assertThat(theirs.body()).isEqualTo(nothing.body()).doesNotContain("lines");
        assertThat(browser().get("/snapshots/" + m.snapshotId() + "/layout").status()).isEqualTo(401);
    }

    @Test
    void anOnboardedResumesLayoutIsItsPreviewsAndFollowsAChosenRole() throws Exception {
        Seeded s = seededAccount();
        TestBrowser b = s.account().browser();
        UUID resumeId = s.account().resumeId();
        JsonNode layout = get(b, "/resumes/" + resumeId + "/layout");
        assertGeometry(layout);
        assertThat(layout.has("changes") && !layout.path("changes").isNull()).as("nothing has been changed yet").isFalse();
        JsonNode resume = get(b, "/resumes/" + resumeId);
        int projectPositions = 0;
        for (JsonNode p : layout.path("positions")) {
            if (p.path("id").asText().startsWith("P")) {
                projectPositions++;
            }
        }
        assertThat(projectPositions).isEqualTo(resume.path("blocks").path("positions").size());
        assertThat(projectPositions).isGreaterThan(0);

        TestBrowser stranger = signedInBrowser(uniqueEmail());
        assertThat(stranger.get("/resumes/" + resumeId + "/layout").status()).isEqualTo(404);

        // Role overrides need a resume with no intake or library yet; a fresh upload is one.
        TestBrowser fresh = signedInBrowser(uniqueEmail());
        TestBrowser.Response up = fresh.postFile("/resumes", "file", "projects.docx", java.nio.file.Files.readAllBytes(phase3("projects_synthetic.docx")));
        UUID freshId = UUID.fromString(JSON.readTree(up.body()).path("resume_id").asText());
        runJobs();
        JsonNode freshResume = get(fresh, "/resumes/" + freshId);
        String projectsKey = null;
        for (JsonNode sec : freshResume.path("sections")) {
            if ("Projects".equalsIgnoreCase(sec.path("heading").asText())) {
                projectsKey = sec.path("key").asText();
            }
        }
        assertThat(fresh.putJson("/resumes/" + freshId + "/sections/" + projectsKey + "/role", "{\"role\":\"other\"}").status()).isEqualTo(200);
        JsonNode demoted = get(fresh, "/resumes/" + freshId + "/layout");
        for (JsonNode p : demoted.path("positions")) {
            assertThat(p.path("id").asText()).as("no project positions once Projects is 'other'").doesNotStartWith("P");
        }
    }
}
