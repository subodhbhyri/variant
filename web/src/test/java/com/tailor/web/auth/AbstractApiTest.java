package com.tailor.web.auth;

import com.tailor.web.TestDatabase;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * The real application on a real port, PostgreSQL behind it, a stub Google, captured mail and a
 * movable clock. Subclasses choose their port and what else the context has; all tests of one
 * subclass share a Spring context, so each starts from clean rate-limit and token tables and uses
 * its own email addresses.
 */
@Import(TestBeans.class)
public abstract class AbstractApiTest {

    protected static final StubOidcProvider GOOGLE = StubOidcProvider.start();
    protected static final String FRONTEND = "http://localhost:5173";

    /** Database, mail, cookie and Google settings shared by every kind of api test. */
    protected static void commonProperties(DynamicPropertyRegistry registry, int port) {
        String base = "http://localhost:" + port;
        TestDatabase.register(registry);
        registry.add("server.port", () -> port);
        registry.add("app.public-base-url", () -> base);
        registry.add("app.frontend-url", () -> FRONTEND);
        registry.add("app.mail.mode", () -> "test");
        registry.add("app.cookie.secure", () -> "false");
        registry.add("app.google.client-id", () -> StubOidcProvider.CLIENT_ID);
        registry.add("app.google.client-secret", () -> "test-secret");
        registry.add("app.google.authorization-uri", GOOGLE::authorizationUri);
        registry.add("app.google.token-uri", GOOGLE::tokenUri);
        registry.add("app.google.jwk-set-uri", GOOGLE::jwkSetUri);
        registry.add("app.google.issuer", GOOGLE::issuer);
    }

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected TestBeans.CapturingEmailSender mail;
    @Autowired
    protected TestBeans.MutableClock clock;

    /** Where this test class's application listens. */
    protected abstract String baseUrl();

    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM rate_events");
        jdbc.update("DELETE FROM login_tokens");
        mail.clear();
        clock.reset();
        GOOGLE.signInAs("sub-default", "default@example.com", true);
    }

    protected TestBrowser browser() {
        return new TestBrowser(baseUrl());
    }

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    /** Signs in by email link and returns the browser holding the session. */
    protected TestBrowser signedInBrowser(String email) {
        TestBrowser b = browser();
        b.primeCsrf();
        b.postJson("/auth/email", "{\"email\":\"" + email + "\"}");
        TestBrowser.Response verify = b.postJson("/auth/email/verify", "{\"token\":\"" + mail.last().token() + "\"}");
        if (verify.status() != 200) {
            throw new IllegalStateException("sign-in failed: " + verify.status() + " " + verify.body());
        }
        return b;
    }

    protected UUID userId(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    protected long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    protected static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
