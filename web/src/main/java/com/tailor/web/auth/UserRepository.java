package com.tailor.web.auth;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.tailor.web.api.ApiException;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The account for a verified email, created on first sign-in. Google and email-link sign-ins
     * with the same verified email are the same user (PHASE6_SPEC.md section 3); a Google
     * {@code sub} is attached to the account the first time it is seen.
     */
    public AuthUser signIn(String verifiedEmail, String googleSub) {
        String email = normalizeEmail(verifiedEmail);
        Optional<Row> byEmail = findByEmail(email);
        if (byEmail.isEmpty() && googleSub != null) {
            // The Google account changed its address: the stable identity is the sub.
            Optional<Row> bySub = findByGoogleSub(googleSub);
            if (bySub.isPresent()) {
                return bySub.get().toUser();
            }
        }
        if (byEmail.isEmpty()) {
            jdbc.update("INSERT INTO users (id, email, google_sub) VALUES (?, ?, ?) ON CONFLICT (email) DO NOTHING",
                    UUID.randomUUID(), email, googleSub);
            byEmail = findByEmail(email);
        }
        Row row = byEmail.orElseThrow();
        if (googleSub != null) {
            if (row.googleSub() == null) {
                try {
                    jdbc.update("UPDATE users SET google_sub = ? WHERE id = ? AND google_sub IS NULL", googleSub, row.id());
                } catch (org.springframework.dao.DuplicateKeyException e) {
                    throw conflict();
                }
            } else if (!row.googleSub().equals(googleSub)) {
                throw conflict();
            }
        }
        return row.toUser();
    }

    public Optional<AuthUser> findById(UUID id) {
        List<Row> rows = jdbc.query(
                "SELECT id, email, google_sub FROM users WHERE id = ? AND deleted_at IS NULL", ROW, id);
        return rows.stream().findFirst().map(Row::toUser);
    }

    private Optional<Row> findByEmail(String email) {
        return jdbc.query("SELECT id, email, google_sub FROM users WHERE email = ? AND deleted_at IS NULL", ROW, email)
                .stream().findFirst();
    }

    private Optional<Row> findByGoogleSub(String sub) {
        return jdbc.query("SELECT id, email, google_sub FROM users WHERE google_sub = ? AND deleted_at IS NULL", ROW, sub)
                .stream().findFirst();
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "ACCOUNT_CONFLICT",
                "This email is already linked to a different Google account.");
    }

    private record Row(UUID id, String email, String googleSub) {
        AuthUser toUser() {
            return new AuthUser(id, email);
        }
    }

    private static final org.springframework.jdbc.core.RowMapper<Row> ROW = (rs, n) ->
            new Row(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("google_sub"));
}
