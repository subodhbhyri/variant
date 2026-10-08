package com.tailor.web.match;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailor.web.match.MatchRows.Match;
import com.tailor.web.match.MatchRows.Posting;
import com.tailor.web.match.MatchRows.Snapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Postings, matches and snapshots. Every lookup is by id AND owner (PHASE6_SPEC.md section 9.2). */
@Repository
public class MatchRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public MatchRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---- postings ------------------------------------------------------------------------------

    public void insertPosting(Posting p) {
        jdbc.update("INSERT INTO postings (id, user_id, text, parsed, fingerprint, library_id, job_id, created_at)"
                        + " VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                p.id(), p.userId(), p.text(), write(p.parsed()), p.fingerprint(), p.libraryId(), p.jobId(),
                Timestamp.from(p.createdAt()));
    }

    public void setPostingJob(UUID postingId, UUID userId, UUID jobId) {
        jdbc.update("UPDATE postings SET job_id = ? WHERE id = ? AND user_id = ?", jobId, postingId, userId);
    }

    public Optional<Posting> findPosting(UUID id, UUID userId) {
        return jdbc.query("SELECT * FROM postings WHERE id = ? AND user_id = ?", (rs, n) -> posting(rs), id, userId)
                .stream().findFirst();
    }

    /** Newest first. */
    public List<Posting> postings(UUID userId, int limit) {
        return jdbc.query("SELECT * FROM postings WHERE user_id = ? ORDER BY created_at DESC, id LIMIT ?",
                (rs, n) -> posting(rs), userId, limit);
    }

    // ---- matches -------------------------------------------------------------------------------

    public void insertMatch(Match m) {
        jdbc.update("INSERT INTO matches (id, user_id, posting_id, library_id, result, cache_of, created_at)"
                        + " VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)",
                m.id(), m.userId(), m.postingId(), m.libraryId(), write(m.result()), m.cacheOf(),
                Timestamp.from(m.createdAt()));
    }

    public Optional<Match> findMatchForPosting(UUID postingId, UUID userId) {
        return jdbc.query("SELECT * FROM matches WHERE posting_id = ? AND user_id = ?", (rs, n) -> match(rs), postingId, userId)
                .stream().findFirst();
    }

    public Optional<Match> findMatch(UUID id, UUID userId) {
        return jdbc.query("SELECT * FROM matches WHERE id = ? AND user_id = ?", (rs, n) -> match(rs), id, userId)
                .stream().findFirst();
    }

    /**
     * The user's own (not reused) matches made against {@code libraryId}, newest first: the only ones a new
     * posting may reuse. A match against any other library version is never offered (PHASE6_SPEC.md section 6).
     */
    public List<Match> reusableMatches(UUID userId, UUID libraryId) {
        return jdbc.query("SELECT * FROM matches WHERE user_id = ? AND library_id = ? AND cache_of IS NULL"
                + " ORDER BY created_at DESC", (rs, n) -> match(rs), userId, libraryId);
    }

    // ---- snapshots -----------------------------------------------------------------------------

    public void insertSnapshot(Snapshot s) {
        jdbc.update("INSERT INTO snapshots (id, user_id, match_id, rank, label, assembly, parent_id, edits, docx_key,"
                        + " pdf_key, status, created_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?)",
                s.id(), s.userId(), s.matchId(), s.rank(), s.label(), write(s.assembly()), s.parentId(),
                s.edits() == null ? null : write(s.edits()), s.docxKey(), s.pdfKey(), s.status(),
                Timestamp.from(s.createdAt()));
    }

    public Optional<Snapshot> findSnapshot(UUID id, UUID userId) {
        return jdbc.query("SELECT * FROM snapshots WHERE id = ? AND user_id = ?", (rs, n) -> snapshot(rs), id, userId)
                .stream().findFirst();
    }

    /** The match's original snapshots by rank (revisions are reached through their parents). */
    public List<Snapshot> originals(UUID matchId, UUID userId) {
        return jdbc.query("SELECT * FROM snapshots WHERE match_id = ? AND user_id = ? AND parent_id IS NULL ORDER BY rank",
                (rs, n) -> snapshot(rs), matchId, userId);
    }

    /** {@code parentId}'s revisions, oldest first. */
    public List<Snapshot> revisionsOf(UUID parentId, UUID userId) {
        return jdbc.query("SELECT * FROM snapshots WHERE parent_id = ? AND user_id = ? ORDER BY created_at, id",
                (rs, n) -> snapshot(rs), parentId, userId);
    }

    /**
     * Marks a stored, previously failed or interrupted ({@code rendering}, as after a lost worker) snapshot as being
     * rendered; false if it is already rendered, which never changes.
     */
    public boolean startRendering(UUID id, UUID userId) {
        return jdbc.update("UPDATE snapshots SET status = 'rendering' WHERE id = ? AND user_id = ?"
                + " AND status IN ('stored', 'failed', 'rendering')", id, userId) == 1;
    }

    /** The one transition that freezes a snapshot: it now has its files, and never changes again. */
    public boolean markRendered(UUID id, UUID userId, String docxKey, String pdfKey) {
        return jdbc.update("UPDATE snapshots SET status = 'rendered', docx_key = ?, pdf_key = ? WHERE id = ? AND user_id = ?"
                + " AND status = 'rendering'", docxKey, pdfKey, id, userId) == 1;
    }

    public void markFailed(UUID id, UUID userId) {
        jdbc.update("UPDATE snapshots SET status = 'failed' WHERE id = ? AND user_id = ? AND status = 'rendering'", id, userId);
    }

    // ---- mapping -------------------------------------------------------------------------------

    private Posting posting(ResultSet rs) throws SQLException {
        return new Posting(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("text"),
                read(rs.getString("parsed")), rs.getString("fingerprint"), rs.getObject("library_id", UUID.class),
                rs.getObject("job_id", UUID.class), instant(rs, "created_at"));
    }

    private Match match(ResultSet rs) throws SQLException {
        return new Match(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("posting_id", UUID.class), rs.getObject("library_id", UUID.class),
                read(rs.getString("result")), rs.getObject("cache_of", UUID.class), instant(rs, "created_at"));
    }

    private Snapshot snapshot(ResultSet rs) throws SQLException {
        return new Snapshot(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("match_id", UUID.class), rs.getInt("rank"), rs.getString("label"),
                read(rs.getString("assembly")), rs.getObject("parent_id", UUID.class), read(rs.getString("edits")),
                rs.getString("docx_key"), rs.getString("pdf_key"), rs.getString("status"), instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
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
