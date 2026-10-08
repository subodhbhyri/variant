package com.tailor.web.resumes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Every lookup is by id AND owner in one query (PHASE6_SPEC.md section 9.2). */
@Repository
public class ResumeRepository {

    /** One row of {@code section_roles} plus the heading it belongs to (kept in blocks_json). */
    public record SectionRole(String key, String suggestedRole, String confirmedRole) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ResumeRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insertUploaded(UUID id, UUID userId, String originalKey) {
        jdbc.update("INSERT INTO resumes (id, user_id, status, original_key) VALUES (?, ?, 'uploaded', ?)",
                id, userId, originalKey);
    }

    public void setOnboardJob(UUID id, UUID userId, UUID jobId) {
        jdbc.update("UPDATE resumes SET onboard_job_id = ? WHERE id = ? AND user_id = ?", jobId, id, userId);
    }

    public Optional<Resume> findForUser(UUID id, UUID userId) {
        return jdbc.query("SELECT * FROM resumes WHERE id = ? AND user_id = ?", MAPPER, id, userId).stream().findFirst();
    }

    public Optional<Resume> findActiveForUser(UUID userId) {
        return jdbc.query("SELECT * FROM resumes WHERE user_id = ? AND active", MAPPER, userId).stream().findFirst();
    }

    public void markOnboarding(UUID id, UUID userId) {
        jdbc.update("UPDATE resumes SET status = 'onboarding' WHERE id = ? AND user_id = ? AND status IN ('uploaded', 'onboarding')",
                id, userId);
    }

    /** The engine said no (a gate code, TOO_MANY_PAGES, TOO_FEW_EDITABLE...) or needs the user (NEEDS_USER). */
    public void markRejected(UUID id, UUID userId, String status, JsonNode report) {
        jdbc.update("UPDATE resumes SET status = ?, onboard_json = ?::jsonb WHERE id = ? AND user_id = ?",
                status, write(report), id, userId);
    }

    public void markFailed(UUID id, UUID userId) {
        jdbc.update("UPDATE resumes SET status = 'failed' WHERE id = ? AND user_id = ? AND status IN ('uploaded', 'onboarding')",
                id, userId);
    }

    /** Onboarding produced an accepted report: store its outputs and the section roles it suggests. */
    public void completeOnboarding(UUID id, UUID userId, String normalizedKey, String previewKey, String baselineKey,
            JsonNode report, JsonNode blocks, String rendererVersion, List<SectionRole> sections) {
        jdbc.update("UPDATE resumes SET status = 'ready', normalized_key = ?, preview_key = ?, baseline_key = ?,"
                        + " onboard_json = ?::jsonb, blocks_json = ?::jsonb, renderer_version = ? WHERE id = ? AND user_id = ?",
                normalizedKey, previewKey, baselineKey, write(report), write(blocks), rendererVersion, id, userId);
        jdbc.update("DELETE FROM section_roles WHERE resume_id = ? AND user_id = ?", id, userId);
        for (SectionRole s : sections) {
            jdbc.update("INSERT INTO section_roles (resume_id, user_id, section_key, suggested_role) VALUES (?, ?, ?, ?)",
                    id, userId, s.key(), s.suggestedRole());
        }
    }

    public List<SectionRole> sections(UUID resumeId, UUID userId) {
        return jdbc.query("SELECT section_key, suggested_role, confirmed_role FROM section_roles"
                        + " WHERE resume_id = ? AND user_id = ? ORDER BY length(section_key), section_key",
                (rs, n) -> new SectionRole(rs.getString(1), rs.getString(2), rs.getString(3)), resumeId, userId);
    }

    public boolean confirmRole(UUID resumeId, UUID userId, String key, String role) {
        return jdbc.update("UPDATE section_roles SET confirmed_role = ? WHERE resume_id = ? AND user_id = ? AND section_key = ?",
                role, resumeId, userId, key) == 1;
    }

    /** Makes {@code id} the user's one active resume; the previous one is archived, never deleted. */
    public void activate(UUID id, UUID userId) {
        jdbc.update("UPDATE resumes SET active = false, status = 'archived' WHERE user_id = ? AND active AND id <> ?", userId, id);
        jdbc.update("UPDATE resumes SET active = true, status = 'accepted' WHERE id = ? AND user_id = ?", id, userId);
        jdbc.update("UPDATE section_roles SET confirmed_role = COALESCE(confirmed_role, suggested_role)"
                + " WHERE resume_id = ? AND user_id = ?", id, userId);
    }

    private JsonNode read(ResultSet rs, String column) throws SQLException {
        String text = rs.getString(column);
        try {
            return text == null ? null : json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("bad JSON in resumes." + column, e);
        }
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private final RowMapper<Resume> MAPPER = (rs, n) -> {
        Timestamp created = rs.getTimestamp("created_at");
        return new Resume(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("status"),
                rs.getBoolean("active"),
                rs.getString("original_key"),
                rs.getString("normalized_key"),
                rs.getString("preview_key"),
                rs.getString("baseline_key"),
                read(rs, "onboard_json"),
                read(rs, "blocks_json"),
                rs.getString("renderer_version"),
                rs.getObject("onboard_job_id", UUID.class),
                created.toInstant());
    };
}
