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
 * The production cookie flags, on the wire: with {@code app.cookie.secure} left at its default
 * (true) both cookies are Secure, the session cookie is HttpOnly, and both are SameSite=Lax.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"app.mail.mode=test"})
@Import(TestBeans.class)
class SecureCookieTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @LocalServerPort
    int port;
    @Autowired
    TestBeans.CapturingEmailSender mail;

    @Test
    void sessionAndCsrfCookiesCarryTheProductionFlags() {
        TestBrowser b = new TestBrowser("http://localhost:" + port);

        TestBrowser.Response csrf = b.get("/auth/csrf");
        String csrfCookie = csrf.setCookies().stream().filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        assertThat(csrfCookie).contains("Secure").contains("SameSite=Lax").doesNotContain("HttpOnly");

        b.postJson("/auth/email", "{\"email\":\"secure-cookie@example.com\"}");
        TestBrowser.Response verify = b.get("/auth/email/verify?token=" + mail.last().token());

        String session = verify.setCookies().stream().filter(c -> c.startsWith("SESSION=")).findFirst().orElseThrow();
        assertThat(session).contains("Secure").contains("HttpOnly").contains("SameSite=Lax");
    }
}
