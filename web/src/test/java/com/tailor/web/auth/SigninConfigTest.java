package com.tailor.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailor.web.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * P6-T16, the switch: with {@code APP_MAIL_MODE=off} (the single-server deployment has no domain to send mail
 * from) {@code POST /auth/email} answers 404 {@code EMAIL_SIGNIN_DISABLED}, sends nothing, stores no token, and
 * {@code GET /config} (public, no session) does not list email.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"app.mail.mode=off"})
@Import(TestBeans.class)
class SigninConfigTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @LocalServerPort
    int port;
    @Autowired
    TestBeans.CapturingEmailSender mail;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void emailSigninIsRefusedAndNotAdvertisedWhenMailIsOff() {
        mail.clear();
        TestBrowser b = new TestBrowser("http://localhost:" + port);
        b.primeCsrf();

        TestBrowser.Response r = b.postJson("/auth/email", "{\"email\":\"someone@example.com\"}");
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.body()).contains("\"code\":\"EMAIL_SIGNIN_DISABLED\"");
        assertThat(mail.all()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_tokens WHERE email = ?", Long.class,
                "someone@example.com")).isZero();

        TestBrowser.Response config = new TestBrowser("http://localhost:" + port).get("/config");
        assertThat(config.status()).isEqualTo(200);
        assertThat(config.body()).doesNotContain("email");
        assertThat(config.body()).contains("\"signin\":[]"); // no Google client is configured in this context either
    }
}
