package com.tailor.web.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailor.web.auth.TestBrowser;
import com.tailor.web.flow.MatchFlowBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** PHASE6_SPEC.md section 8, all three kinds, with the real jobs: one row per event, whatever the delivery. */
@Tag("corpus")
class LedgerFlowTest extends MatchFlowBase {

    private void redeliver(UUID jobId) {
        jdbc.update("UPDATE jobs SET status = 'queued', attempts = 0, worker_id = NULL, finished_at = NULL, error_code = NULL WHERE id = ?", jobId);
        runJobs();
    }

    @Test
    void aMatchAndAnAlternativeEachWriteOneRowEvenIfDeliveredTwice() throws Exception {
        Seeded s = seededAccount();
        Account a = s.account();
        TestBrowser.Response posted = post(a, jd("platform"));
        UUID matchJob = UUID.fromString(JSON.readTree(posted.body()).path("job_id").asText());
        runJobs();
        assertThat(count("SELECT count(*) FROM usage_ledger WHERE user_id = ? AND kind = 'tailoring'", a.userId())).isEqualTo(1);

        redeliver(matchJob); // the match job again, as after a queue hiccup

        assertThat(count("SELECT count(*) FROM usage_ledger WHERE user_id = ? AND kind = 'tailoring'", a.userId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM matches WHERE user_id = ?", a.userId())).as("and no second match").isEqualTo(1);

        String postingId = JSON.readTree(posted.body()).path("posting_id").asText();
        String alt = JSON.readTree(a.browser().get("/postings/" + postingId + "/match").body()).path("alternatives").get(0).path("id").asText();
        UUID renderJob = UUID.fromString(JSON.readTree(a.browser().postJson("/snapshots/" + alt + "/render", "{}").body()).path("job_id").asText());
        runJobs();
        assertThat(count("SELECT count(*) FROM usage_ledger WHERE user_id = ? AND kind = 'alternative'", a.userId())).isEqualTo(1);

        redeliver(renderJob);

        assertThat(count("SELECT count(*) FROM usage_ledger WHERE user_id = ? AND kind = 'alternative'", a.userId())).isEqualTo(1);
    }

    @Test
    void theTailoringKeyIsUserFingerprintAndLibraryVersion() throws Exception {
        Seeded s = seededAccount();
        Account a = s.account();
        post(a, jd("platform"));
        runJobs();

        List<String> keys = jdbc.queryForList("SELECT idempotency_key FROM usage_ledger WHERE user_id = ? AND kind = 'tailoring'",
                String.class, a.userId());
        String fingerprint = jdbc.queryForObject("SELECT fingerprint FROM postings WHERE user_id = ?", String.class, a.userId());
        Integer version = jdbc.queryForObject("SELECT version FROM libraries WHERE resume_id = ?", Integer.class, a.resumeId());
        assertThat(keys).containsExactly("tailoring:" + a.userId() + ":" + fingerprint + ":" + version);
    }

    @Test
    void aNewLibraryVersionIsANewFirstTailoringForTheSamePosting() throws Exception {
        Seeded s = seededAccount();
        Account a = s.account();
        post(a, jd("platform"));
        runJobs();
        JsonNode library = JSON.readTree(a.browser().get("/resumes/" + a.resumeId() + "/library").body());
        String variant = null;
        for (JsonNode section : library.path("sections")) {
            if (section.path("candidates").size() > 0) {
                variant = section.path("candidates").get(0).path("variants").get(0).path("id").asText();
                break;
            }
        }
        a.browser().postJson("/libraries/" + library.path("library_id").asText() + "/variants/" + variant + "/remove", "{}");

        post(a, jd("platform"));
        runJobs();

        assertThat(count("SELECT count(*) FROM usage_ledger WHERE user_id = ? AND kind = 'tailoring'", a.userId())).isEqualTo(2);
    }

    @Test
    void ledgerRowsCarryIdsAndAmountsAndNothingElse() {
        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns"
                + " WHERE table_name = 'usage_ledger' ORDER BY ordinal_position", String.class);
        assertThat(columns).containsExactly("id", "user_id", "kind", "ref_id", "idempotency_key", "cost_usd", "created_at");
        // The only free-form column is the idempotency key, built from ids, a hash and a number.
        assertThat(count("SELECT count(*) FROM usage_ledger WHERE idempotency_key ~ '[^0-9a-zA-Z:_-]'")).isZero();
    }
}
