package com.tailor.web.auth;

import com.tailor.web.api.ApiException;
import com.tailor.web.config.AppProperties;
import com.tailor.web.ratelimit.RateLimiter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Email sign-in links (PHASE6_SPEC.md sections 3, 4.1 and 9.1): 32 random bytes, only their SHA-256
 * stored, 15-minute life, single use, openable on a different device from the one that asked.
 */
@Service
public class LoginTokenService {

    public static final Duration LIFETIME = Duration.ofMinutes(15);
    static final int LINKS_PER_ADDRESS_PER_HOUR = 5;
    static final int LINKS_PER_IP_PER_HOUR = 20;

    private static final Logger log = LoggerFactory.getLogger(LoginTokenService.class);
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final RateLimiter limiter;
    private final EmailSender mail;
    private final AppProperties props;
    private final Clock clock;

    public LoginTokenService(JdbcTemplate jdbc, RateLimiter limiter, EmailSender mail, AppProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.limiter = limiter;
        this.mail = mail;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Sends a sign-in link to {@code rawEmail}. The caller answers 202 whether or not an account
     * exists, so none of this depends on that.
     */
    public void request(String rawEmail, String clientIp) {
        String email = UserRepository.normalizeEmail(rawEmail == null ? "" : rawEmail);
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL", "Enter a valid email address.");
        }
        boolean allowed = limiter.tryAcquire("email-link-address", hex(sha256(email.getBytes(StandardCharsets.UTF_8))),
                LINKS_PER_ADDRESS_PER_HOUR, Duration.ofHours(1))
                && limiter.tryAcquire("email-link-ip", clientIp, LINKS_PER_IP_PER_HOUR, Duration.ofHours(1));
        if (!allowed) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many sign-in links requested. Try again later.");
        }

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        Instant now = clock.instant();
        jdbc.update("INSERT INTO login_tokens (id, email, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), email, sha256(raw), Timestamp.from(now.plus(LIFETIME)), Timestamp.from(now));

        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String link = props.publicBaseUrl() + "/auth/email/verify?token=" + token;
        try {
            mail.sendSignInLink(email, link);
        } catch (RuntimeException e) {
            // Same outcome for every address, so it reveals nothing about accounts. No address in the log.
            log.error("sign-in email could not be sent: {}", e.getClass().getName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_FAILED",
                    "We couldn't send the email. Please try again.");
        }
    }

    /** Spends the link and returns its email, or empty if it is unknown, expired or already used. */
    public Optional<String> consume(String token) {
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(token == null ? "" : token);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (raw.length != 32) {
            return Optional.empty();
        }
        Timestamp now = Timestamp.from(clock.instant());
        // One statement: two concurrent clicks can't both win.
        List<String> emails = jdbc.queryForList(
                "UPDATE login_tokens SET used_at = ? WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?"
                        + " RETURNING email", String.class, now, sha256(raw), now);
        return emails.stream().findFirst();
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
