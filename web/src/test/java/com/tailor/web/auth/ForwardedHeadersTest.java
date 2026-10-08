package com.tailor.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * PHASE6_SPEC.md section 10A.2: "Spring trusts X-Forwarded-* only from Caddy, so rate limits see the real client IP".
 * The production compose file sets {@code SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES} to Caddy's one fixed address. Here
 * the trusted proxy is some other address, so the test client (loopback) is NOT trusted: a client that invents
 * X-Forwarded-For values must not be able to spread requests over fake addresses and dodge the per-IP limit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(com.tailor.web.jobs.JobTestHandlers.class)
class ForwardedHeadersTest extends AbstractApiTest {

    private static final int PORT = freePort();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        commonProperties(registry, PORT);
        registry.add("server.tomcat.remoteip.internal-proxies", () -> "10\\.99\\.0\\.1");
    }

    @Override
    protected String baseUrl() {
        return "http://localhost:" + PORT;
    }

    @Test
    void aClientThatIsNotTheProxyCannotChooseItsOwnAddress() {
        TestBrowser b = browser();
        b.primeCsrf();
        for (int i = 0; i < 20; i++) {
            // Twenty different claimed addresses; the real peer is always loopback.
            TestBrowser.Response r = b.from("198.51.100." + (i + 1)).postJson("/auth/email", "{\"email\":\"" + uniqueEmail() + "\"}");
            assertThat(r.status()).isEqualTo(202);
        }
        TestBrowser.Response twentyFirst = b.from("198.51.100.99").postJson("/auth/email", "{\"email\":\"" + uniqueEmail() + "\"}");
        assertThat(twentyFirst.status()).as("the 21st request from the same real address is limited whatever it claims").isEqualTo(429);
    }
}
