package com.tailor.web.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Libraries are immutable (PHASE6_SPEC.md sections 3 and 6): this only ever inserts a new version;
 * the database also refuses updates to existing libraries and items.
 */
@Repository
public class LibraryRepository {

    public record Library(UUID id, UUID resumeId, int version, Instant createdAt) {
    }

    /** One variant text. {@code projectHeader} is set for a project's items (title, detail, links, date). */
    public record Item(String sectionId, String candidateId, int length, String text, String homeSection,
            JsonNode projectHeader) {
    }

    public record Run(UUID id, UUID resumeId, UUID userId, UUID libraryId, String status, JsonNode report,
            double costUsd, Instant startedAt, Instant finishedAt) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final java.time.Clock clock;

    public LibraryRepository(JdbcTemplate jdbc, ObjectMapper json, java.time.Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    // ---- libraries -----------------------------------------------------------------------------

    public Optional<Library> latest(UUID resumeId, UUID userId) {
        return jdbc.query("SELECT * FROM libraries WHERE resume_id = ? AND user_id = ? ORDER BY version DESC LIMIT 1",
                (rs, n) -> library(rs), resumeId, userId).stream().findFirst();
    }

    public Optional<Library> find(UUID id, UUID userId) {
        return jdbc.query("SELECT * FROM libraries WHERE id = ? AND user_id = ?", (rs, n) -> library(rs), id, userId)
                .stream().findFirst();
    }

    public List<Item> items(UUID libraryId, UUID userId) {
        return jdbc.query("SELECT section_id, candidate_id, length, text, home_section, project_header::text AS header"
                        + " FROM library_items WHERE library_id = ? AND user_id = ?"
                        + " ORDER BY section_id, candidate_id, length",
                (rs, n) -> new Item(rs.getString("section_id"), rs.getString("candidate_id"), rs.getInt("length"),
                        rs.getString("text"), rs.getString("home_section"), read(rs.getString("header"))),
                libraryId, userId);
    }

    /** Inserts the next version of the resume's library with exactly these items. */
    public Library create(UUID resumeId, UUID userId, List<Item> items) {
        Integer max = jdbc.queryForObject("SELECT coalesce(max(version), 0) FROM libraries WHERE resume_id = ?",
                Integer.class, resumeId);
        int version = (max == null ? 0 : max) + 1;
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO libraries (id, resume_id, user_id, version) VALUES (?, ?, ?, ?)",
                id, resumeId, userId, version);
        for (Item item : items) {
            jdbc.update("INSERT INTO library_items (library_id, user_id, section_id, candidate_id, length, text, source,"
                            + " home_section, project_header) VALUES (?, ?, ?, ?, ?, ?, 'generated', ?, ?::jsonb)",
                    id, userId, item.sectionId(), item.candidateId(), item.length(), item.text(), item.homeSection(),
                    item.projectHeader() == null ? null : write(item.projectHeader()));
        }
        return jdbc.query("SELECT * FROM libraries WHERE id = ?", (rs, n) -> library(rs), id).get(0);
    }

    // ---- generation runs -----------------------------------------------------------------------

    public void createRun(UUID id, UUID resumeId, UUID userId, UUID jobId) {
        jdbc.update("INSERT INTO generation_runs (id, resume_id, user_id, job_id, status, started_at)"
                + " VALUES (?, ?, ?, ?, 'queued', ?)", id, resumeId, userId, jobId, Timestamp.from(clock.instant()));
    }

    public Optional<Run> findRun(UUID id, UUID userId) {
        return jdbc.query("SELECT * FROM generation_runs WHERE id = ? AND user_id = ?", (rs, n) -> run(rs), id, userId)
                .stream().findFirst();
    }

    /** The resume's runs, newest first. */
    public List<Run> runs(UUID resumeId, UUID userId) {
        return jdbc.query("SELECT * FROM generation_runs WHERE resume_id = ? AND user_id = ? ORDER BY started_at DESC, id",
                (rs, n) -> run(rs), resumeId, userId);
    }

    public int runsSince(UUID resumeId, UUID userId, Instant since) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM generation_runs WHERE resume_id = ? AND user_id = ?"
                + " AND started_at > ?", Integer.class, resumeId, userId, Timestamp.from(since));
        return n == null ? 0 : n;
    }

    public void markRunning(UUID id, UUID userId) {
        jdbc.update("UPDATE generation_runs SET status = 'running' WHERE id = ? AND user_id = ? AND status IN ('queued', 'running')",
                id, userId);
    }

    public void completeRun(UUID id, UUID userId, UUID libraryId, JsonNode report, double costUsd, Instant finished) {
        jdbc.update("UPDATE generation_runs SET status = 'succeeded', library_id = ?, report = ?::jsonb, cost_usd = ?,"
                        + " finished_at = ? WHERE id = ? AND user_id = ?",
                libraryId, write(report), costUsd, Timestamp.from(finished), id, userId);
    }

    public void failRun(UUID id, UUID userId, Instant finished) {
        jdbc.update("UPDATE generation_runs SET status = 'failed', finished_at = ? WHERE id = ? AND user_id = ?"
                + " AND status IN ('queued', 'running')", Timestamp.from(finished), id, userId);
    }

    // ---- mapping -------------------------------------------------------------------------------

    private static Library library(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Library(rs.getObject("id", UUID.class), rs.getObject("resume_id", UUID.class), rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant());
    }

    private Run run(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new Run(rs.getObject("id", UUID.class), rs.getObject("resume_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getObject("library_id", UUID.class), rs.getString("status"),
                read(rs.getString("report")), rs.getBigDecimal("cost_usd").doubleValue(),
                rs.getTimestamp("started_at").toInstant(), finished == null ? null : finished.toInstant());
    }

    private JsonNode read(String text) {
        try {
            return text == null ? null : json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
