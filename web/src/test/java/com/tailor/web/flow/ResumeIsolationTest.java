package com.tailor.web.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailor.web.auth.TestBrowser;
import com.tailor.web.storage.StorageKeys;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * P6-T2 for the resume endpoints: user B gets 404 for user A's resource on every endpoint, with the
 * same answer as for an id that never existed, and a presigned link is only issued to the owner.
 */
class ResumeIsolationTest extends FlowTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A ready resume for {@code user}, as if onboarding had finished (rows and files, no engine run). */
    private UUID readyResume(UUID user) {
        UUID id = UUID.randomUUID();
        String preview = StorageKeys.preview(user, id);
        storage.put(preview, "%PDF-1.7 preview".getBytes(), "application/pdf", false);
        jdbc.update("INSERT INTO resumes (id, user_id, status, original_key, preview_key, onboard_json, blocks_json)"
                + " VALUES (?, ?, 'ready', ?, ?, '{\"accepted\":true,\"slots\":[]}'::jsonb,"
                + " '{\"sections\":[{\"heading\":\"Projects\",\"role\":\"projects\"}]}'::jsonb)",
                id, user, StorageKeys.original(user, id), preview);
        jdbc.update("INSERT INTO section_roles (resume_id, user_id, section_key, suggested_role) VALUES (?, ?, 's0', 'projects')",
                id, user);
        return id;
    }

    @Test
    void theOwnerSeesTheirResumeAndGetsAPreviewLink() throws Exception {
        String email = uniqueEmail();
        TestBrowser owner = signedInBrowser(email);
        UUID resume = readyResume(userId(email));

        TestBrowser.Response view = owner.get("/resumes/" + resume);
        assertThat(view.status()).isEqualTo(200);
        assertThat(JSON.readTree(view.body()).path("sections").get(0).path("heading").asText()).isEqualTo("Projects");

        TestBrowser.Response preview = owner.get("/resumes/" + resume + "/preview");
        assertThat(preview.status()).isEqualTo(200);
        String url = JSON.readTree(preview.body()).path("url").asText();
        var download = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(download.body()).startsWith("%PDF");
    }

    @Test
    void everyResumeEndpointAnswers404ToAnotherUser() throws Exception {
        String ownerEmail = uniqueEmail();
        signedInBrowser(ownerEmail);
        UUID resume = readyResume(userId(ownerEmail));
        TestBrowser stranger = signedInBrowser(uniqueEmail());
        UUID neverExisted = UUID.randomUUID();

        String[][] calls = {
                {"GET", "/resumes/%s"},
                {"GET", "/resumes/%s/preview"},
                {"PUT", "/resumes/%s/sections/s0/role"},
                {"POST", "/resumes/%s/accept"},
        };
        for (String[] call : calls) {
            TestBrowser.Response theirs = send(stranger, call[0], call[1].formatted(resume));
            TestBrowser.Response missing = send(stranger, call[0], call[1].formatted(neverExisted));

            assertThat(theirs.status()).as(call[0] + " " + call[1]).isEqualTo(404);
            assertThat(theirs.body()).as(call[0] + " " + call[1] + " must not differ from a missing id")
                    .isEqualTo(missing.body());
            assertThat(theirs.body()).doesNotContain("http").doesNotContain("url");
        }
        // The owner's resume was not touched by any of it.
        assertThat(jdbc.queryForObject("SELECT status FROM resumes WHERE id = ?", String.class, resume)).isEqualTo("ready");
    }

    @Test
    void aMalformedIdIsTheSame404() {
        TestBrowser b = signedInBrowser(uniqueEmail());
        assertThat(b.get("/resumes/not-a-uuid").status()).isEqualTo(404);
    }

    @Test
    void anonymousCallersAreRefused() {
        TestBrowser anon = browser();
        assertThat(anon.get("/resumes/" + UUID.randomUUID()).status()).isEqualTo(401);
        assertThat(anon.get("/resumes/" + UUID.randomUUID() + "/preview").status()).isEqualTo(401);
    }

    private static TestBrowser.Response send(TestBrowser b, String method, String path) {
        return switch (method) {
            case "GET" -> b.get(path);
            case "PUT" -> b.putJson(path, "{\"role\":\"projects\"}");
            case "POST" -> b.postJson(path, "{}");
            default -> throw new IllegalArgumentException(method);
        };
    }
}
