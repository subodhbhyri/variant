package com.tailor.web.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailor.web.auth.TestBrowser;
import java.util.UUID;

/** Flow tests that start from an onboarded, accepted resume (Phase 4's Jane Doe, {@code ok_synthetic.docx}). */
public abstract class AcceptedResumeBase extends FlowTestBase {

    protected static final ObjectMapper JSON = new ObjectMapper();

    protected record Account(TestBrowser browser, UUID userId, UUID resumeId) {
    }

    /** A new user with ok_synthetic.docx uploaded, onboarded for real and accepted. */
    protected Account acceptedAccount() throws Exception {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        TestBrowser.Response up = b.postFile("/resumes", "file", "jane.docx", Fixtures.phase2("ok_synthetic.docx"));
        assertThat(up.status()).isEqualTo(202);
        UUID resumeId = UUID.fromString(JSON.readTree(up.body()).path("resume_id").asText());
        runJobs();
        assertThat(b.postJson("/resumes/" + resumeId + "/accept", "{}").status()).isEqualTo(200);
        return new Account(b, userId(email), resumeId);
    }

    /** Saves a section with the given mode, notes and header fields (null fields keep the template's). */
    protected TestBrowser.Response save(Account a, String sectionId, String mode, String notes, String fieldsJson) {
        String body = "{\"mode\":\"" + mode + "\",\"notes\":" + quote(notes) + (fieldsJson == null ? "" : ",\"fields\":" + fieldsJson) + "}";
        return a.browser().putJson("/resumes/" + a.resumeId() + "/intake/sections/" + sectionId, body);
    }

    protected JsonNode intake(Account a) throws Exception {
        return JSON.readTree(a.browser().get("/resumes/" + a.resumeId() + "/intake").body());
    }

    protected JsonNode section(JsonNode intake, String id) {
        for (JsonNode s : intake.path("sections")) {
            if (id.equals(s.path("id").asText())) {
                return s;
            }
        }
        return null;
    }

    protected static String quote(String s) {
        try {
            return new ObjectMapper().writeValueAsString(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
