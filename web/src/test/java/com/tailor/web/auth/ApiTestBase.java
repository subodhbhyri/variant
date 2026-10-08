package com.tailor.web.auth;

import com.tailor.web.TestDatabase;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The real application on a real port, PostgreSQL behind it, a stub Google, captured mail and a
 * movable clock. All tests extending this share one Spring context, so each starts from clean
 * rate-limit and token tables and uses its own email addresses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(TestBeans.class)
public abstract class ApiTestBase {

    protected static final StubOidcProvider GOOGLE = StubOidcProvider.start();
    protected static final int PORT = freePort();
    protected static final String BASE = "http://localhost:" + PORT;
    protected static final String FRONTEND = "http://localhost:5173";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("server.port", () -> PORT);
        registry.add("app.public-base-url", () -> BASE);
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

    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM rate_events");
        jdbc.update("DELETE FROM login_tokens");
        mail.clear();
        clock.reset();
        GOOGLE.signInAs("sub-default", "default@example.com", true);
    }

    protected TestBrowser browser() {
        return new TestBrowser(BASE);
    }

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    /** Signs in by email link and returns the browser holding the session. */
    protected TestBrowser signedInBrowser(String email) {
        TestBrowser b = browser();
        b.primeCsrf();
        b.postJson("/auth/email", "{\"email\":\"" + email + "\"}");
        TestBrowser.Response verify = b.get(mail.last().link());
        if (verify.status() != 302 || !FRONTEND.equals(verify.location())) {
            throw new IllegalStateException("sign-in failed: " + verify.status() + " " + verify.location());
        }
        return b;
    }

    protected long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
