package com.tailor.web.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailor.engine.gate.GateMessages;
import com.tailor.web.auth.TestBrowser;
import com.tailor.web.storage.StorageKeys;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * P6-T3, the upload half: every gate rejection fixture is refused synchronously with its code and
 * nothing is stored; a good one is stored untouched and queues an onboarding job.
 */
class UploadApiTest extends FlowTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Gate reasons only: the later pipeline stages (page count, editable count) answer through the job. */
    private static final List<String> LATER_STAGE = List.of("TOO_MANY_PAGES", "TOO_FEW_EDITABLE", "NEEDS_USER", "PROCESSING_TIMEOUT");

    private long storedObjects(UUID user) {
        return storage.countPrefix(StorageKeys.userPrefix(user));
    }

    @Test
    void everyGateRejectionFixtureIsRefusedAtOnceWithItsCodeAndNothingIsStored() throws Exception {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        UUID user = userId(email);
        List<String> checked = new ArrayList<>();

        Iterator<Map.Entry<String, JsonNode>> fixtures = Fixtures.phase2Expected().fields();
        while (fixtures.hasNext()) {
            Map.Entry<String, JsonNode> fixture = fixtures.next();
            JsonNode expected = fixture.getValue();
            if (expected.path("accept").asBoolean() || LATER_STAGE.contains(expected.path("reason").asText())) {
                continue;
            }
            String reason = expected.path("reason").asText();

            TestBrowser.Response r = b.postFile("/resumes", "file", fixture.getKey(), Fixtures.phase2(fixture.getKey()));

            assertThat(r.status()).as(fixture.getKey()).isEqualTo(422);
            JsonNode body = JSON.readTree(r.body());
            assertThat(body.path("code").asText()).as(fixture.getKey()).isEqualTo(reason);
            assertThat(body.path("message").asText()).as(fixture.getKey()).isEqualTo(GateMessages.forReason(reason));
            checked.add(fixture.getKey());
        }

        assertThat(checked).as("all the rejection fixtures were exercised").hasSizeGreaterThanOrEqualTo(20);
        assertThat(count("SELECT count(*) FROM resumes WHERE user_id = ?", user)).isZero();
        assertThat(count("SELECT count(*) FROM jobs WHERE user_id = ?", user)).isZero();
        assertThat(storedObjects(user)).isZero();
    }

    @Test
    void aGoodFileIsStoredUntouchedAndQueuesAnOnboardingJob() throws Exception {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        UUID user = userId(email);
        byte[] file = Fixtures.phase2("ok_synthetic.docx");

        TestBrowser.Response r = b.postFile("/resumes", "file", "my resume.docx", file);

        assertThat(r.status()).isEqualTo(202);
        JsonNode body = JSON.readTree(r.body());
        UUID resumeId = UUID.fromString(body.path("resume_id").asText());
        UUID jobId = UUID.fromString(body.path("job_id").asText());
        assertThat(storage.get(StorageKeys.original(user, resumeId))).isEqualTo(file);
        assertThat(count("SELECT count(*) FROM resumes WHERE id = ? AND user_id = ? AND status = 'uploaded'", resumeId, user)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM jobs WHERE id = ? AND user_id = ? AND type = 'onboard' AND status = 'queued'", jobId, user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT onboard_job_id FROM resumes WHERE id = ?", UUID.class, resumeId)).isEqualTo(jobId);
        assertThat(JSON.readTree(b.get("/jobs/" + jobId).body()).path("type").asText()).isEqualTo("onboard");
    }

    @Test
    void anUploadWithTheSameIdempotencyKeyReturnsTheSameJobAndStoresNothingNew() throws Exception {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        UUID user = userId(email);
        byte[] file = Fixtures.phase2("ok_synthetic.docx");

        JsonNode first = JSON.readTree(b.postFile("/resumes", "file", "a.docx", file, "Idempotency-Key", "up-1").body());
        JsonNode again = JSON.readTree(b.postFile("/resumes", "file", "a.docx", file, "Idempotency-Key", "up-1").body());

        assertThat(again.path("job_id").asText()).isEqualTo(first.path("job_id").asText());
        assertThat(again.path("resume_id").asText()).isEqualTo(first.path("resume_id").asText());
        assertThat(count("SELECT count(*) FROM jobs WHERE user_id = ?", user)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM resumes WHERE user_id = ?", user)).isEqualTo(1);
        assertThat(storedObjects(user)).isEqualTo(1);
    }

    @Test
    void aFileOverTwoMegabytesIsRefusedAsTooLarge() throws Exception {
        TestBrowser b = signedInBrowser(uniqueEmail());
        byte[] big = new byte[2 * 1024 * 1024 + 1024];

        TestBrowser.Response r = b.postFile("/resumes", "file", "big.docx", big);

        assertThat(r.status()).isEqualTo(422);
        assertThat(JSON.readTree(r.body()).path("code").asText()).isEqualTo("FILE_TOO_LARGE");
    }

    @Test
    void aFileFarOverTheLimitIsRefusedTheSameWay() throws Exception {
        TestBrowser b = signedInBrowser(uniqueEmail());

        TestBrowser.Response r = b.postFile("/resumes", "file", "huge.docx", new byte[4 * 1024 * 1024]);

        assertThat(r.status()).isEqualTo(422);
        assertThat(JSON.readTree(r.body()).path("code").asText()).isEqualTo("FILE_TOO_LARGE");
    }

    @Test
    void uploadingNeedsASessionAndACsrfToken() {
        byte[] file = Fixtures.phase2("ok_synthetic.docx");
        assertThat(browser().postFile("/resumes", "file", "a.docx", file).status()).isIn(401, 403);

        TestBrowser signedIn = signedInBrowser(uniqueEmail());
        signedIn.forgetCookie("XSRF-TOKEN");
        assertThat(signedIn.postFile("/resumes", "file", "a.docx", file).status()).isEqualTo(403);
    }

    @Test
    void aRequestWithoutTheFilePartIsABadRequest() throws Exception {
        TestBrowser b = signedInBrowser(uniqueEmail());

        TestBrowser.Response r = b.postFile("/resumes", "not-file", "a.docx", new byte[] {1});

        assertThat(r.status()).isEqualTo(400);
        assertThat(JSON.readTree(r.body()).path("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void aNonMultipartUploadIsRefused() {
        TestBrowser b = signedInBrowser(uniqueEmail());
        assertThat(b.postJson("/resumes", "{}").status()).isEqualTo(415);
    }
}
