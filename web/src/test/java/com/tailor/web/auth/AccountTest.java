package com.tailor.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** GET /me and DELETE /me (PHASE6_SPEC.md sections 4.1 and 9.4). */
class AccountTest extends ApiTestBase {

    private UUID idOf(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private void giveRows(UUID user) {
        UUID resume = UUID.randomUUID();
        jdbc.update("INSERT INTO resumes (id, user_id, status, active) VALUES (?, ?, 'accepted', true)", resume, user);
        UUID library = UUID.randomUUID();
        jdbc.update("INSERT INTO libraries (id, resume_id, user_id, version) VALUES (?, ?, ?, 1)", library, resume, user);
        jdbc.update("INSERT INTO library_items (library_id, user_id, section_id, candidate_id, length, text)"
                + " VALUES (?, ?, 's', 'c', 1, 'text')", library, user);
        UUID posting = UUID.randomUUID();
        jdbc.update("INSERT INTO postings (id, user_id, text, fingerprint) VALUES (?, ?, 'jd', 'fp')", posting, user);
        UUID match = UUID.randomUUID();
        jdbc.update("INSERT INTO matches (id, user_id, posting_id, library_id, result) VALUES (?, ?, ?, ?, '{}'::jsonb)",
                match, user, posting, library);
        jdbc.update("INSERT INTO snapshots (id, user_id, match_id, rank, label, assembly, status)"
                + " VALUES (?, ?, ?, 1, 'l', '{}'::jsonb, 'stored')", UUID.randomUUID(), user, match);
        jdbc.update("INSERT INTO jobs (id, user_id, type, status, priority) VALUES (?, ?, 'match', 'queued', 1)",
                UUID.randomUUID(), user);
    }

    @Test
    void meShowsTheUserTheActiveResumeAndUsage() {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        UUID user = idOf(email);
        assertThat(b.get("/me").body()).contains("\"usage\":{\"generations\":0,\"tailorings\":0,\"alternatives\":0,\"cost_usd\":0");

        giveRows(user);
        jdbc.update("INSERT INTO usage_ledger (id, user_id, kind, idempotency_key, cost_usd) VALUES (?, ?, 'generation', ?, 0.25)",
                UUID.randomUUID(), user, "g-" + user);
        jdbc.update("INSERT INTO usage_ledger (id, user_id, kind, idempotency_key, cost_usd) VALUES (?, ?, 'alternative', ?, 0)",
                UUID.randomUUID(), user, "a-" + user);

        String body = b.get("/me").body();
        assertThat(body).contains("\"id\":\"" + user + "\"").contains("\"email\":\"" + email + "\"");
        assertThat(body).contains("\"active_resume_id\":\"")
                .contains("\"generations\":1").contains("\"alternatives\":1").contains("\"cost_usd\":0.25");
    }

    @Test
    void deletingTheAccountRemovesEverythingButTheLedgerAndEndsTheSession() {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        UUID user = idOf(email);
        giveRows(user);
        jdbc.update("INSERT INTO usage_ledger (id, user_id, kind, idempotency_key, cost_usd) VALUES (?, ?, 'generation', ?, 0.25)",
                UUID.randomUUID(), user, "g-" + user);

        TestBrowser bystanderBrowser = signedInBrowser(uniqueEmail());
        UUID bystander = jdbc.queryForObject(
                "SELECT id FROM users WHERE id <> ? ORDER BY created_at DESC LIMIT 1", UUID.class, user);
        giveRows(bystander);

        assertThat(b.delete("/me").status()).isEqualTo(204);

        for (String table : new String[] {"resumes", "libraries", "library_items", "postings", "matches",
                "snapshots", "jobs"}) {
            assertThat(count("SELECT count(*) FROM " + table + " WHERE user_id = ?", user)).as(table).isZero();
        }
        assertThat(count("SELECT count(*) FROM users WHERE id = ?", user)).isZero();
        assertThat(count("SELECT count(*) FROM login_tokens WHERE email = ?", email)).isZero();
        assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name = ?", user.toString())).isZero();
        // Ledger rows stay: ids and amounts only.
        assertThat(count("SELECT count(*) FROM usage_ledger WHERE user_id = ?", user)).isEqualTo(1);
        // The stored files are promised for removal within 24 hours.
        assertThat(count("SELECT count(*) FROM storage_deletions WHERE prefix = ? AND completed_at IS NULL",
                "users/" + user + "/")).isEqualTo(1);

        assertThat(b.get("/me").status()).isEqualTo(401);
        // Someone else is untouched.
        assertThat(count("SELECT count(*) FROM resumes WHERE user_id = ?", bystander)).isEqualTo(1);
        assertThat(bystanderBrowser.get("/me").status()).isEqualTo(200);
    }

    @Test
    void aDeletedAccountsEmailCanSignUpAgainAsANewUser() {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        UUID old = idOf(email);
        b.delete("/me");

        signedInBrowser(email);

        assertThat(idOf(email)).isNotEqualTo(old);
    }

    @Test
    void aDeletedUsersOtherSessionsDieToo() {
        String email = uniqueEmail();
        TestBrowser laptop = signedInBrowser(email);
        TestBrowser phone = signedInBrowser(email);

        laptop.delete("/me");

        assertThat(phone.get("/me").status()).isEqualTo(401);
    }
}
