package com.tailor.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** PHASE6_SPEC.md section 3 (the data model) and the database-enforced rules of section 6. */
class MigrationsTest {

    private static String schema;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        schema = TestDatabase.createMigratedSchema();
        DataSource ds = TestDatabase.inSchema(schema);
        jdbc = new JdbcTemplate(ds);
    }

    @AfterAll
    static void drop() throws SQLException {
        TestDatabase.dropSchema(schema);
    }

    @Test
    void everySectionThreeTableExists() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ?", String.class, schema);
        assertThat(tables).contains("users", "login_tokens", "resumes", "section_roles", "intake_sections",
                "libraries", "library_items", "generation_runs", "postings", "matches", "snapshots", "jobs",
                "usage_ledger", "alias_queue", "spring_session", "spring_session_attributes", "rate_events",
                "storage_deletions");
    }

    @Test
    void everyUserOwnedTableCarriesUserId() {
        // Section 3: "Every user-owned row carries user_id, and every query filters by it."
        for (String table : List.of("resumes", "section_roles", "intake_sections", "libraries", "library_items",
                "generation_runs", "postings", "matches", "snapshots", "jobs", "usage_ledger")) {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.columns"
                            + " WHERE table_schema = ? AND table_name = ? AND column_name = 'user_id'",
                    Integer.class, schema, table);
            assertThat(n).as(table + ".user_id").isEqualTo(1);
        }
    }

    @Test
    void emailsAreUniqueAndLowerCase() {
        jdbc.update("INSERT INTO users (id, email) VALUES (?, 'a@example.com')", UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO users (id, email) VALUES (?, 'a@example.com')", UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO users (id, email) VALUES (?, 'Mixed@Example.com')", UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void oneActiveResumePerUser() {
        UUID user = user("active@example.com");
        jdbc.update("INSERT INTO resumes (id, user_id, status, active) VALUES (?, ?, 'accepted', true)",
                UUID.randomUUID(), user);
        jdbc.update("INSERT INTO resumes (id, user_id, status, active) VALUES (?, ?, 'archived', false)",
                UUID.randomUUID(), user);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO resumes (id, user_id, status, active) VALUES (?, ?, 'accepted', true)",
                UUID.randomUUID(), user))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void postingTextOverTheLimitIsRejectedByTheDatabase() {
        UUID user = user("long@example.com");
        jdbc.update("INSERT INTO postings (id, user_id, text, fingerprint) VALUES (?, ?, ?, 'f')",
                UUID.randomUUID(), user, "x".repeat(20_000));
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO postings (id, user_id, text, fingerprint) VALUES (?, ?, ?, 'f')",
                UUID.randomUUID(), user, "x".repeat(20_001)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void ledgerIdempotencyKeyIsUnique() {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO usage_ledger (id, user_id, kind, idempotency_key, cost_usd)"
                + " VALUES (?, ?, 'generation', 'run-1', 0.12)", UUID.randomUUID(), user);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO usage_ledger (id, user_id, kind, idempotency_key, cost_usd)"
                + " VALUES (?, ?, 'generation', 'run-1', 0.12)", UUID.randomUUID(), user))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void ledgerRowsSurviveAccountDeletion() {
        UUID user = user("gone@example.com");
        jdbc.update("INSERT INTO usage_ledger (id, user_id, kind, idempotency_key, cost_usd)"
                + " VALUES (?, ?, 'alternative', 'alt-gone', 0)", UUID.randomUUID(), user);
        jdbc.update("DELETE FROM users WHERE id = ?", user);
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM usage_ledger WHERE idempotency_key = 'alt-gone'", Integer.class);
        assertThat(n).isEqualTo(1);
    }

    @Test
    void deletingAUserRemovesAllTheirRows() {
        UUID user = user("cascade@example.com");
        UUID resume = resume(user);
        UUID library = library(resume, user, 1);
        item(library, user);
        UUID posting = posting(user);
        UUID match = match(user, posting, library);
        snapshot(user, match, "stored");
        jdbc.update("INSERT INTO jobs (id, user_id, type, status, priority) VALUES (?, ?, 'match', 'queued', 1)",
                UUID.randomUUID(), user);

        jdbc.update("DELETE FROM users WHERE id = ?", user);

        for (String table : List.of("resumes", "libraries", "library_items", "postings", "matches", "snapshots",
                "jobs")) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class, user);
            assertThat(n).as(table).isZero();
        }
    }

    @Test
    void libraryRowsAreImmutable() {
        UUID user = user("lib@example.com");
        UUID resume = resume(user);
        UUID library = library(resume, user, 1);
        item(library, user);

        assertThatThrownBy(() -> jdbc.update("UPDATE libraries SET version = 2 WHERE id = ?", library))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE library_items SET text = 'changed' WHERE library_id = ?", library))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");

        // A change is a new version.
        UUID v2 = library(resume, user, 2);
        assertThat(v2).isNotEqualTo(library);
    }

    @Test
    void aRenderedSnapshotNeverChanges() {
        UUID user = user("snap@example.com");
        UUID library = library(resume(user), user, 1);
        UUID match = match(user, posting(user), library);
        UUID snapshot = snapshot(user, match, "stored");

        // Before it is rendered it can be filled in: stored -> rendered sets the files once.
        jdbc.update("UPDATE snapshots SET status = 'rendered', docx_key = 'd', pdf_key = 'p' WHERE id = ?", snapshot);

        for (String change : List.of("label = 'other'", "assembly = '{\"x\":1}'::jsonb", "docx_key = 'd2'",
                "pdf_key = 'p2'", "edits = '[]'::jsonb", "rank = 2", "status = 'stored'", "parent_id = id")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE snapshots SET " + change + " WHERE id = ?", snapshot))
                    .as(change).isInstanceOf(DataAccessException.class).hasMessageContaining("never changes");
        }
        // A no-op write is allowed; nothing changed.
        jdbc.update("UPDATE snapshots SET label = label WHERE id = ?", snapshot);
    }

    @Test
    void aRenderedSnapshotMustHaveBothFiles() {
        UUID user = user("files@example.com");
        UUID library = library(resume(user), user, 1);
        UUID match = match(user, posting(user), library);
        UUID snapshot = snapshot(user, match, "stored");
        assertThatThrownBy(() -> jdbc.update("UPDATE snapshots SET status = 'rendered' WHERE id = ?", snapshot))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void jobStatusAndTypeAreConstrained() {
        UUID user = user("jobs@example.com");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO jobs (id, user_id, type, status, priority) VALUES (?, ?, 'bogus', 'queued', 1)",
                UUID.randomUUID(), user)).isInstanceOf(DataAccessException.class);
        jdbc.update("INSERT INTO jobs (id, user_id, type, status, priority, idempotency_key)"
                + " VALUES (?, ?, 'generate', 'queued', 4, 'k1')", UUID.randomUUID(), user);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO jobs (id, user_id, type, status, priority, idempotency_key)"
                + " VALUES (?, ?, 'generate', 'queued', 4, 'k1')", UUID.randomUUID(), user))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void flywayRecordsOneMigrationPerFile() {
        Set<String> versions = Set.copyOf(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL", String.class));
        assertThat(versions).contains("1", "2");
    }

    // ---- row helpers -------------------------------------------------------------------------

    private static UUID user(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, email);
        return id;
    }

    private static UUID resume(UUID user) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO resumes (id, user_id, status) VALUES (?, ?, 'ready')", id, user);
        return id;
    }

    private static UUID library(UUID resume, UUID user, int version) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO libraries (id, resume_id, user_id, version) VALUES (?, ?, ?, ?)",
                id, resume, user, version);
        return id;
    }

    private static void item(UUID library, UUID user) {
        jdbc.update("INSERT INTO library_items (library_id, user_id, section_id, candidate_id, text)"
                + " VALUES (?, ?, 'job-1', 'c1', 'did a thing')", library, user);
    }

    private static UUID posting(UUID user) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO postings (id, user_id, text, fingerprint) VALUES (?, ?, 'jd', 'fp')", id, user);
        return id;
    }

    private static UUID match(UUID user, UUID posting, UUID library) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO matches (id, user_id, posting_id, library_id, result) VALUES (?, ?, ?, ?, '{}'::jsonb)",
                id, user, posting, library);
        return id;
    }

    private static UUID snapshot(UUID user, UUID match, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO snapshots (id, user_id, match_id, rank, label, assembly, status)"
                + " VALUES (?, ?, ?, 1, 'resume', '{}'::jsonb, ?)", id, user, match, status);
        return id;
    }
}
