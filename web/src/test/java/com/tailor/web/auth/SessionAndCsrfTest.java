package com.tailor.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** PHASE6_SPEC.md section 9.1: session cookie and expiry, CSRF on every state-changing request. */
class SessionAndCsrfTest extends ApiTestBase {

    @Test
    void anonymousRequestsToProtectedEndpointsAre401InTheSharedShape() {
        TestBrowser b = browser();
        TestBrowser.Response r = b.get("/me");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body()).contains("\"code\":\"UNAUTHENTICATED\"");
        // Unknown paths look the same to an anonymous caller: nothing to probe.
        assertThat(b.get("/definitely-not-a-route").status()).isEqualTo(401);
    }

    @Test
    void theSessionCookieIsHttpOnlyLaxAndNamedSession() {
        TestBrowser.Response verify = rawVerify(uniqueEmail());
        String cookie = verify.setCookies().stream().filter(c -> c.startsWith("SESSION=")).findFirst().orElseThrow();

        assertThat(cookie).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");
    }

    @Test
    void theCsrfCookieIsReadableByTheSpa() {
        TestBrowser b = browser();
        TestBrowser.Response r = b.get("/auth/csrf");

        String cookie = r.setCookies().stream().filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        assertThat(cookie).doesNotContain("HttpOnly").contains("SameSite=Lax");
    }

    @Test
    void sessionsExpireAfterFourteenIdleDays() {
        signedInBrowser(uniqueEmail());
        Long seconds = jdbc.queryForObject("SELECT max(max_inactive_interval) FROM spring_session", Long.class);
        assertThat(seconds).isEqualTo(14L * 24 * 60 * 60);
    }

    @Test
    void stateChangingRequestsWithoutACsrfTokenAreRefused() {
        TestBrowser b = signedInBrowser(uniqueEmail());

        TestBrowser.Response del = b.deleteWithoutCsrf("/me");
        assertThat(del.status()).isEqualTo(403);
        assertThat(del.body()).contains("\"code\":\"CSRF\"");
        assertThat(b.get("/me").status()).isEqualTo(200); // nothing was deleted

        assertThat(b.postJsonWithoutCsrf("/auth/logout", "").status()).isEqualTo(403);
        assertThat(b.postJsonWithoutCsrf("/auth/email", "{\"email\":\"a@example.com\"}").status()).isEqualTo(403);
    }

    @Test
    void aCsrfHeaderThatDoesNotMatchTheCookieIsRefused() {
        TestBrowser b = signedInBrowser(uniqueEmail());
        TestBrowser.Response r = b.deleteWithCsrfHeader("/me", "some-other-value");
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body()).contains("\"code\":\"CSRF\"");
        assertThat(b.get("/me").status()).isEqualTo(200);
    }

    @Test
    void logoutEndsTheSession() {
        TestBrowser b = signedInBrowser(uniqueEmail());
        assertThat(b.get("/me").status()).isEqualTo(200);
        long sessionsBefore = count("SELECT count(*) FROM spring_session");

        assertThat(b.postJson("/auth/logout", "").status()).isEqualTo(204);

        assertThat(b.get("/me").status()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM spring_session")).isLessThan(sessionsBefore);
    }

    @Test
    void corsAllowsOnlyTheAppOrigin() {
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        for (String[] c : new String[][] {{FRONTEND, "yes"}, {"https://evil.example", "no"}}) {
            try {
                var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(BASE + "/me"))
                        .method("OPTIONS", java.net.http.HttpRequest.BodyPublishers.noBody())
                        .header("Origin", c[0]).header("Access-Control-Request-Method", "GET").build();
                var res = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                boolean allowed = res.headers().firstValue("Access-Control-Allow-Origin").isPresent();
                assertThat(allowed).as(c[0]).isEqualTo("yes".equals(c[1]));
                if (allowed) {
                    assertThat(res.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private TestBrowser.Response rawVerify(String email) {
        TestBrowser b = browser();
        b.primeCsrf();
        b.postJson("/auth/email", "{\"email\":\"" + email + "\"}");
        return b.get(mail.last().link());
    }
}
