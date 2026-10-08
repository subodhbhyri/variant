package com.tailor.web.auth;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PHASE6_SPEC.md section 9.4: {@code DELETE /me} removes the account and all of the user's rows
 * immediately. Their S3 objects are removed within 24 hours: the promise is written to
 * {@code storage_deletions} in the same transaction, so a crash can't lose it.
 * Ledger rows stay, holding only ids and amounts.
 */
@Service
public class AccountService {

    private final JdbcTemplate jdbc;

    public AccountService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void delete(UUID userId) {
        jdbc.update("DELETE FROM login_tokens WHERE email = (SELECT email FROM users WHERE id = ?)", userId);
        jdbc.update("DELETE FROM spring_session WHERE principal_name = ?", userId.toString());
        jdbc.update("INSERT INTO storage_deletions (prefix) VALUES (?) ON CONFLICT (prefix) DO NOTHING",
                storagePrefix(userId));
        // Every user-owned table references users ON DELETE CASCADE.
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    public static String storagePrefix(UUID userId) {
        return "users/" + userId + "/";
    }
}
