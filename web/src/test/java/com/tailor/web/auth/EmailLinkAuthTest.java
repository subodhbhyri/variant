package com.tailor.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** P6-T1, email links: single use, 15-minute life, the same answer for known and unknown addresses. */
class EmailLinkAuthTest extends ApiTestBase {

    @Test
    void aLinkSignsTheUserInOnceAndCreatesTheAccount() {
        String email = uniqueEmail();
        TestBrowser b = browser();
        b.primeCsrf();

        TestBrowser.Response asked = b.postJson("/auth/email", "{\"email\":\"" + email + "\"}");
        assertThat(asked.status()).isEqualTo(202);
        assertThat(mail.all()).hasSize(1);
        assertThat(mail.last().to()).isEqualTo(email);

        TestBrowser.Response verify = b.get(mail.last().link());
        assertThat(verify.status()).isEqualTo(302);
        assertThat(verify.location()).isEqualTo(FRONTEND);

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

        assertThat(first.get(link).location()).isEqualTo(FRONTEND);

        TestBrowser second = browser();
        TestBrowser.Response again = second.get(link);
        assertThat(again.status()).isEqualTo(302);
        assertThat(again.location()).isEqualTo(FRONTEND + "/signin?error=SIGNIN_LINK_INVALID");
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
        assertThat(b.get("/me").status()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM users WHERE email = ?", email)).isZero();
    }

    @Test
    void aLinkStillWorksJustInsideFifteenMinutes() {
        TestBrowser b = browser();
        b.primeCsrf();
        b.postJson("/auth/email", "{\"email\":\"" + uniqueEmail() + "\"}");

        clock.advance(Duration.ofMinutes(14).plusSeconds(50));

        assertThat(b.get(mail.last().link()).location()).isEqualTo(FRONTEND);
    }

    @Test
    void aLinkOpenedOnAnotherDeviceSignsThatDeviceIn() {
        String email = uniqueEmail();
        TestBrowser laptop = browser();
        laptop.primeCsrf();
        laptop.postJson("/auth/email", "{\"email\":\"" + email + "\"}");

        TestBrowser phone = browser(); // no cookies from the laptop
        assertThat(phone.get(mail.last().link()).location()).isEqualTo(FRONTEND);
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
