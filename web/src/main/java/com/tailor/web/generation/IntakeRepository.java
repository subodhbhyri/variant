package com.tailor.web.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The user's saved answers, one row per intake section (PHASE6_SPEC.md section 3). */
@Repository
public class IntakeRepository {

    /** {@code fields} is the header JSON ({@code title, detail, links, date}); {@code notes} is the engine's raw_text. */
    public record Saved(String sectionId, String kind, String mode, JsonNode fields, String notes) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public IntakeRepository(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public List<Saved> list(UUID resumeId, UUID userId) {
        return jdbc.query("SELECT section_id, kind, mode, fields::text AS fields, raw_text FROM intake_sections"
                        + " WHERE resume_id = ? AND user_id = ? ORDER BY length(section_id), section_id",
                (rs, n) -> new Saved(rs.getString("section_id"), rs.getString("kind"), rs.getString("mode"),
                        read(rs.getString("fields")), rs.getString("raw_text")), resumeId, userId);
    }

    public Optional<Saved> find(UUID resumeId, UUID userId, String sectionId) {
        return list(resumeId, userId).stream().filter(s -> s.sectionId().equals(sectionId)).findFirst();
    }

    public void upsert(UUID resumeId, UUID userId, Saved s) {
        jdbc.update("INSERT INTO intake_sections (resume_id, user_id, section_id, kind, mode, fields, raw_text, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)"
                        + " ON CONFLICT (resume_id, section_id) DO UPDATE SET mode = EXCLUDED.mode,"
                        + " fields = EXCLUDED.fields, raw_text = EXCLUDED.raw_text, updated_at = EXCLUDED.updated_at",
                resumeId, userId, s.sectionId(), s.kind(), s.mode(), write(s.fields()), s.notes(),
                Timestamp.from(clock.instant()));
    }

    public boolean delete(UUID resumeId, UUID userId, String sectionId) {
        return jdbc.update("DELETE FROM intake_sections WHERE resume_id = ? AND user_id = ? AND section_id = ?",
                resumeId, userId, sectionId) == 1;
    }

    private JsonNode read(String text) {
        try {
            return text == null ? json.createObjectNode() : json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node == null ? json.createObjectNode() : node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
