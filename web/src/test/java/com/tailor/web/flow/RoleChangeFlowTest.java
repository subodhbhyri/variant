package com.tailor.web.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailor.web.auth.TestBrowser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** PHASE6_SPEC.md revision 3: a role is fixed once the intake has answers or a library exists. */
@Tag("corpus")
class RoleChangeFlowTest extends AcceptedResumeBase {

    private TestBrowser.Response setRole(Account a, String key, String role) {
        return a.browser().putJson("/resumes/" + a.resumeId() + "/sections/" + key + "/role", "{\"role\":\"" + role + "\"}");
    }

    @Test
    void aRoleCannotBeChangedOnceTheIntakeHasAnswers() throws Exception {
        Account a = acceptedAccount();
        JsonNode view = JSON.readTree(a.browser().get("/resumes/" + a.resumeId()).body());
        JsonNode section = view.path("sections").get(0);
        String key = section.path("key").asText();
        String suggested = section.path("suggested_role").asText();
        String other = suggested.equals("other") ? "projects" : "other";

        // Before any answer a change is allowed (and can be undone).
        assertThat(setRole(a, key, other).status()).isEqualTo(200);
        assertThat(setRole(a, key, suggested).status()).isEqualTo(200);

        String sid = intake(a).path("sections").get(0).path("id").asText();
        assertThat(save(a, sid, "DETAILED", "my own notes about this", null).status()).isEqualTo(200);

        TestBrowser.Response late = setRole(a, key, other);
        assertThat(late.status()).isEqualTo(409);
        assertThat(JSON.readTree(late.body()).path("code").asText()).isEqualTo("ROLE_CHANGE_TOO_LATE");
        // Confirming the role it already has is still fine.
        assertThat(setRole(a, key, suggested).status()).isEqualTo(200);
    }
}
