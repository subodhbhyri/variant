package com.tailor.web.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailor.web.auth.TestBrowser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * P6-T2 (PHASE6_SPEC.md section 12): for EVERY endpoint that takes a resource id, user B gets 404 for user A's
 * resource, with exactly the body an id that never existed gets (so ids cannot be probed), and no file link
 * is ever issued. The endpoints come from the OpenAPI document the code generates, not from a list kept by
 * hand: an endpoint added later either gets exercised here or fails this test for lack of a substitute id.
 */
@Tag("corpus")
class IsolationTest extends MatchFlowBase {

    private static final Pattern PARAM = Pattern.compile("\\{([^}]+)}");

    /** A request body each id-bearing endpoint accepts, so ownership (not validation) is what decides the answer. */
    private static final Map<String, String> BODIES = Map.of(
            "PUT /resumes/{id}/sections/{key}/role", "{\"role\":\"projects\"}",
            "PUT /resumes/{id}/intake/sections/{sid}", "{\"mode\":\"DETAILED\",\"notes\":\"x\"}",
            "POST /resumes/{id}/intake/projects", "{\"mode\":\"DETAILED\",\"notes\":\"x\"}",
            "POST /snapshots/{id}/revisions", "{\"edits\":[{\"slot\":0,\"text\":\"x\"}]}",
            "POST /snapshots/{id}/slots/{slot}/check", "{\"text\":\"x\"}");

    private record Op(String method, String template) {
    }

    private List<Op> idBearingOperations() throws Exception {
        JsonNode doc = JSON.readTree(browser().get("/v3/api-docs").body());
        List<Op> ops = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> paths = doc.path("paths").fields();
        while (paths.hasNext()) {
            var path = paths.next();
            if (!path.getKey().contains("{")) {
                continue;
            }
            Iterator<String> methods = path.getValue().fieldNames();
            while (methods.hasNext()) {
                ops.add(new Op(methods.next().toUpperCase(), path.getKey()));
            }
        }
        return ops;
    }

    @Test
    void everyEndpointThatTakesAnIdAnswers404ToAnotherUserWithNoLeak() throws Exception {
        Seeded s = seededAccount();
        Account a = s.account();
        // User A has everything: a library, a posting with its match and snapshots, an intake section, a job.
        assertThat(save(a, "job-0", "DETAILED", "my own notes about this job", null).status()).isEqualTo(200);
        TestBrowser.Response posted = post(a, jd("platform"));
        String postingId = JSON.readTree(posted.body()).path("posting_id").asText();
        String jobId = JSON.readTree(posted.body()).path("job_id").asText();
        runJobs();
        JsonNode match = JSON.readTree(a.browser().get("/postings/" + postingId + "/match").body());
        JsonNode library = JSON.readTree(a.browser().get("/resumes/" + a.resumeId() + "/library").body());
        String variant = null;
        for (JsonNode section : library.path("sections")) {
            if (section.path("candidates").size() > 0) {
                variant = section.path("candidates").get(0).path("variants").get(0).path("id").asText();
                break;
            }
        }

        Map<String, String> ids = new HashMap<>();
        ids.put("/resumes", a.resumeId().toString());
        ids.put("/libraries", library.path("library_id").asText());
        ids.put("/postings", postingId);
        ids.put("/snapshots", match.path("resume").path("id").asText());
        ids.put("/jobs", jobId);
        Map<String, String> others = Map.of("key", "s0", "sid", "job-0", "vid", variant, "slot", "0");

        TestBrowser stranger = signedInBrowser(uniqueEmail());
        long strangerObjects = 0;
        List<Op> ops = idBearingOperations();
        assertThat(ops.size()).as("the OpenAPI document lists the id-bearing endpoints").isGreaterThanOrEqualTo(19);

        for (Op op : ops) {
            String prefix = "/" + op.template().split("/")[1];
            String realId = ids.get(prefix);
            assertThat(realId).as("this test needs a real id to substitute for " + op).isNotNull();
            String theirs = fill(op.template(), realId, others);
            String nobody = fill(op.template(), UUID.randomUUID().toString(), others);
            String body = BODIES.getOrDefault(op.method() + " " + op.template(), "{}");

            TestBrowser.Response forOwnersResource = send(stranger, op.method(), theirs, body);
            TestBrowser.Response forMissing = send(stranger, op.method(), nobody, body);

            assertThat(forOwnersResource.status()).as(op + " for another user's resource").isEqualTo(404);
            assertThat(forOwnersResource.body()).as(op + " must answer exactly as it does for an id that never existed")
                    .isEqualTo(forMissing.body());
            assertThat(forOwnersResource.body()).as(op + " must not leak a link").doesNotContain("http").doesNotContain("\"url\"");
        }

        // And none of those calls changed anything of A's or created anything for B.
        assertThat(count("SELECT count(*) FROM jobs WHERE type IN ('render_alternative', 'edit_revision', 'generate') AND user_id <> ?", a.userId()))
                .isEqualTo(strangerObjects);
        assertThat(count("SELECT count(*) FROM snapshots WHERE user_id <> ?", a.userId())).isZero();
        assertThat(count("SELECT count(*) FROM intake_sections WHERE user_id <> ?", a.userId())).isZero();
        assertThat(count("SELECT count(*) FROM libraries WHERE user_id <> ?", a.userId())).isZero();
        assertThat(count("SELECT count(*) FROM intake_sections WHERE resume_id = ?", a.resumeId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM libraries WHERE resume_id = ?", a.resumeId())).isEqualTo(1);
    }

    @Test
    void theOwnerGetsTheLinksThatThoseEndpointsRefuseToAnyoneElse() throws Exception {
        Seeded s = seededAccount();
        Account a = s.account();
        post(a, jd("platform"));
        runJobs();
        String postingId = jdbc.queryForObject("SELECT posting_id FROM matches WHERE user_id = ?", String.class, a.userId());
        String snapshot = JSON.readTree(a.browser().get("/postings/" + postingId + "/match").body()).path("resume").path("id").asText();

        assertThat(a.browser().get("/snapshots/" + snapshot + "/pdf").body()).contains("\"url\"");
        assertThat(a.browser().get("/resumes/" + a.resumeId() + "/preview").body()).contains("\"url\"");
    }

    private static String fill(String template, String id, Map<String, String> others) {
        Matcher m = PARAM.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            m.appendReplacement(out, Matcher.quoteReplacement("id".equals(name) ? id : others.getOrDefault(name, "x")));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static TestBrowser.Response send(TestBrowser b, String method, String path, String body) {
        return switch (method) {
            case "GET" -> b.get(path);
            case "PUT" -> b.putJson(path, body);
            case "DELETE" -> b.delete(path);
            default -> b.postJson(path, body);
        };
    }
}
