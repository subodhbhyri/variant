package com.tailor.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** P6-T1, email links: single use, 15-minute life, the same answer for known and unknown addresses. */
class EmailLinkAuthTest extends ApiTestBase {

    private static TestBrowser.Response spend(TestBrowser b, String token) {
        if (b.cookie("XSRF-TOKEN") == null) {
            b.primeCsrf();
        }
        return b.postJson("/auth/email/verify", "{\"token\":\"" + token + "\"}");
    }

    // ---- P6-T16: a scanner's GET must not spend the link ----

    @Test
    void aScannerOpeningTheLinkDoesNotSpendIt() {
        String email = uniqueEmail();
        TestBrowser b = browser();
        b.primeCsrf();
        b.postJson("/auth/email", "{\"email\":\"" + email + "\"}");
        String link = mail.last().link();

        TestBrowser scanner = browser();
        for (int i = 0; i < 3; i++) {
            TestBrowser.Response page = scanner.get(link);
            assertThat(page.status()).isEqualTo(200);
            assertThat(page.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
            assertThat(page.body()).contains("Continue as " + email).contains("method=\"post\"");
            assertThat(page.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            assertThat(page.setCookies().stream().anyMatch(c -> c.startsWith("SESSION="))).isFalse();
        }
        assertThat(scanner.get("/me").status()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM login_tokens WHERE used_at IS NOT NULL")).isZero();
        assertThat(count("SELECT count(*) FROM users WHERE email = ?", email)).isZero();

        // The person then clicks the button on the page: the form post signs them in, once.
        TestBrowser person = browser();
        String page = person.get(link).body();
        String csrf = person.cookie("XSRF-TOKEN");
        assertThat(page).contains("name=\"_csrf\" value=\"" + csrf + "\"");
        TestBrowser.Response signedIn = person.postForm("/auth/email/verify",
                "token=" + mail.last().token() + "&_csrf=" + csrf);
        assertThat(signedIn.status()).isEqualTo(303);
        assertThat(signedIn.location()).isEqualTo(FRONTEND);
        assertThat(person.get("/me").status()).isEqualTo(200);

        TestBrowser.Response second = spend(browser(), mail.last().token());
        assertThat(second.status()).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM users WHERE email = ?", email)).isEqualTo(1);
    }

    @Test
    void configListsTheSigninMethodsThatAreOn() {
        TestBrowser.Response config = browser().get("/config"); // public: no session, no CSRF cookie needed
        assertThat(config.status()).isEqualTo(200);
        assertThat(config.body()).isEqualTo("{\"signin\":[\"google\",\"email\"]}");
    }

    @Test
    void thePostNeedsTheCsrfToken() {
        TestBrowser b = browser();
        b.primeCsrf();
        b.postJson("/auth/email", "{\"email\":\"" + uniqueEmail() + "\"}");
        TestBrowser.Response r = b.postJsonWithoutCsrf("/auth/email/verify", "{\"token\":\"" + mail.last().token() + "\"}");
        assertThat(r.status()).isEqualTo(403);
        TestBrowser.Response form = b.postForm("/auth/email/verify", "token=" + mail.last().token());
        assertThat(form.status()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM login_tokens WHERE used_at IS NOT NULL")).isZero();
    }

    @Test
    void theConfirmationPageNamesTheAccountAndEscapesIt() {
        TestBrowser b = browser();
        b.primeCsrf();
        b.postJson("/auth/email", "{\"email\":\"a'b<i>@example.com\"}");
        String page = browser().get(mail.last().link()).body();
        assertThat(page).doesNotContain("<i>").contains("a&#39;b&lt;i&gt;@example.com");
    }

    @Test
    void aLinkSignsTheUserInOnceAndCreatesTheAccount() {
        String email = uniqueEmail();
        TestBrowser b = browser();
        b.primeCsrf();

        TestBrowser.Response asked = b.postJson("/auth/email", "{\"email\":\"" + email + "\"}");
        assertThat(asked.status()).isEqualTo(202);
        assertThat(mail.all()).hasSize(1);
        assertThat(mail.last().to()).isEqualTo(email);

        TestBrowser.Response verify = spend(b, mail.last().token());
        assertThat(verify.status()).isEqualTo(200);
        assertThat(verify.body()).contains("\"redirect\":\"" + FRONTEND + "\"");

        TestBrowser.Response me = b.get("/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body()).contains("\"email\":\"" + email + "\"");
        assertThat(count("SELECT count(*) FROM users WHERE email = ?", email)).isEqualTo(1);
    }

    @Test
    void aLinkWorksOnceOnly() {
        String email = uniqueEmail();
        TestBrowser first = browser();
        first.primeCsrf();
        first.postJson("/auth/email", "{\"email\":\"" + email + "\"}");
        String link = mail.last().link();
        String token = mail.last().token();

        assertThat(spend(first, token).status()).isEqualTo(200);

        TestBrowser second = browser();
        TestBrowser.Response page = second.get(link); // the page is not shown for a link that is already spent
        assertThat(page.status()).isEqualTo(302);
        assertThat(page.location()).isEqualTo(FRONTEND + "/signin?error=SIGNIN_LINK_INVALID");
        TestBrowser.Response again = spend(second, token);
        assertThat(again.status()).isEqualTo(400);
        assertThat(again.body()).contains("\"code\":\"SIGNIN_LINK_INVALID\"");
        assertThat(second.get("/me").status()).isEqualTo(401);
    }

    @Test
    void aLinkExpiresAfterFifteenMinutes() {
        String email = uniqueEmail();
        TestBrowser b = browser();
        b.primeCsrf();
        b.postJson("/auth/email", "{\"email\":\"" + email + "\"}");

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        TestBrowser.Response verify = b.get(mail.last().link());
        assertThat(verify.location()).isEqualTo(FRONTEND + "/signin?error=SIGNIN_LINK_INVALID");
        assertThat(spend(b, mail.last().token()).status()).isEqualTo(400);
        assertThat(b.get("/me").status()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM users WHERE email = ?", email)).isZero();
    }

    @Test
    void aLinkStillWorksJustInsideFifteenMinutes() {
        TestBrowser b = browser();
        b.primeCsrf();
        b.postJson("/auth/email", "{\"email\":\"" + uniqueEmail() + "\"}");

        clock.advance(Duration.ofMinutes(14).plusSeconds(50));

        assertThat(b.get(mail.last().link()).status()).isEqualTo(200);
        assertThat(spend(b, mail.last().token()).status()).isEqualTo(200);
    }

    @Test
    void aLinkOpenedOnAnotherDeviceSignsThatDeviceIn() {
        String email = uniqueEmail();
        TestBrowser laptop = browser();
        laptop.primeCsrf();
        laptop.postJson("/auth/email", "{\"email\":\"" + email + "\"}");

        TestBrowser phone = browser(); // no cookies from the laptop
        assertThat(spend(phone, mail.last().token()).status()).isEqualTo(200);
        assertThat(phone.get("/me").status()).isEqualTo(200);
        assertThat(laptop.get("/me").status()).isEqualTo(401);
    }

    @Test
    void knownAndUnknownAddressesGetTheSameAnswer() {
        String known = uniqueEmail();
        signedInBrowser(known);
        mail.clear();

        TestBrowser b = browser();
        b.primeCsrf();
        TestBrowser.Response forKnown = b.postJson("/auth/email", "{\"email\":\"" + known + "\"}");
        TestBrowser.Response forUnknown = b.postJson("/auth/email", "{\"email\":\"" + uniqueEmail() + "\"}");

        assertThat(forKnown.status()).isEqualTo(202).isEqualTo(forUnknown.status());
        assertThat(forKnown.body()).isEqualTo(forUnknown.body());
        assertThat(mail.all()).hasSize(2); // a link is sent either way
    }

    @Test
    void theTokenIsStoredOnlyAsItsSha256() throws Exception {
        TestBrowser b = browser();
        b.primeCsrf();
        b.postJson("/auth/email", "{\"email\":\"" + uniqueEmail() + "\"}");
        byte[] raw = Base64.getUrlDecoder().decode(mail.last().token());

        assertThat(raw).hasSize(32);
        byte[] stored = jdbc.queryForObject("SELECT token_hash FROM login_tokens", byte[].class);
        assertThat(stored).isEqualTo(java.security.MessageDigest.getInstance("SHA-256").digest(raw));
        assertThat(stored).isNotEqualTo(raw);
    }

    @Test
    void addressesAreCaseInsensitiveAndOneAccountPerEmail() {
        String email = uniqueEmail();
        TestBrowser a = signedInBrowser(email);
        TestBrowser b = signedInBrowser(email.toUpperCase());

        assertThat(count("SELECT count(*) FROM users WHERE email = ?", email)).isEqualTo(1);
        assertThat(a.get("/me").body()).isEqualTo(b.get("/me").body());
    }

    @Test
    void anInvalidAddressIsRefused() {
        TestBrowser b = browser();
        b.primeCsrf();
        TestBrowser.Response r = b.postJson("/auth/email", "{\"email\":\"not-an-email\"}");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body()).contains("\"code\":\"INVALID_EMAIL\"");
        assertThat(mail.all()).isEmpty();
    }

    @Test
    void garbageTokensAreRefusedWithoutAnError() {
        TestBrowser b = browser();
        for (String token : new String[] {"", "abc", "!!!", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32])}) {
            TestBrowser.Response r = b.get("/auth/email/verify?token=" + token);
            assertThat(r.status()).as(token).isEqualTo(302);
            assertThat(r.location()).as(token).endsWith("error=SIGNIN_LINK_INVALID");
        }
        assertThat(b.get("/auth/email/verify").location()).endsWith("error=SIGNIN_LINK_INVALID");
    }

    @Test
    void sixLinksForOneAddressInAnHourIsTooMany() {
        String email = uniqueEmail();
        TestBrowser b = browser();
        b.primeCsrf();
        for (int i = 0; i < 5; i++) {
            assertThat(b.postJson("/auth/email", "{\"email\":\"" + email + "\"}").status()).isEqualTo(202);
        }
        TestBrowser.Response sixth = b.postJson("/auth/email", "{\"email\":\"" + email + "\"}");
        assertThat(sixth.status()).isEqualTo(429);
        assertThat(sixth.body()).contains("\"code\":\"RATE_LIMITED\"");
        assertThat(mail.all()).hasSize(5);

        clock.advance(Duration.ofMinutes(61));
        assertThat(b.postJson("/auth/email", "{\"email\":\"" + email + "\"}").status()).isEqualTo(202);
    }

    @Test
    void twentyOneLinksFromOneAddressInAnHourIsTooMany() {
        TestBrowser b = browser().from("203.0.113.9");
        b.primeCsrf();
        for (int i = 0; i < 20; i++) {
            assertThat(b.postJson("/auth/email", "{\"email\":\"" + uniqueEmail() + "\"}").status()).isEqualTo(202);
        }
        TestBrowser.Response r = b.postJson("/auth/email", "{\"email\":\"" + uniqueEmail() + "\"}");
        assertThat(r.status()).isEqualTo(429);

        // Another client address is unaffected.
        TestBrowser other = browser().from("203.0.113.10");
        other.primeCsrf();
        assertThat(other.postJson("/auth/email", "{\"email\":\"" + uniqueEmail() + "\"}").status()).isEqualTo(202);
    }
}
