package com.tailor.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** P6-T1, Google (mocked provider): state and nonce, verified emails only, one account per email. */
class GoogleSignInTest extends ApiTestBase {

    /** Runs the browser through GET /auth/google, the provider and back to the callback URL (not yet visited). */
    private String startAndReachCallback(TestBrowser browser) {
        TestBrowser.Response start = browser.get("/auth/google");
        assertThat(start.status()).isEqualTo(302);
        // The provider redirects straight back with a code (as Google does once the user consents).
        TestBrowser.Response provider = browser.get(start.location());
        assertThat(provider.status()).isEqualTo(302);
        return provider.location();
    }

    private static Map<String, String> query(String url) {
        Map<String, String> q = new HashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            q.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return q;
    }

    @Test
    void theAuthorizationRequestCarriesStateAndNonce() {
        TestBrowser b = browser();
        TestBrowser.Response start = b.get("/auth/google");

        assertThat(start.status()).isEqualTo(302);
        assertThat(start.location()).startsWith(GOOGLE.authorizationUri());
        Map<String, String> q = query(start.location());
        assertThat(q.get("state")).isNotBlank();
        assertThat(q.get("nonce")).isNotBlank();
        assertThat(q.get("response_type")).isEqualTo("code");
        assertThat(q.get("client_id")).isEqualTo(StubOidcProvider.CLIENT_ID);
        assertThat(q.get("redirect_uri")).isEqualTo(BASE + "/auth/google/callback");
        assertThat(q.get("scope")).contains("openid").contains("email");

        // Each attempt gets fresh values.
        Map<String, String> again = query(browser().get("/auth/google").location());
        assertThat(again.get("state")).isNotEqualTo(q.get("state"));
        assertThat(again.get("nonce")).isNotEqualTo(q.get("nonce"));
    }

    @Test
    void aVerifiedGoogleAccountSignsIn() {
        String email = uniqueEmail();
        GOOGLE.signInAs("google-sub-" + email, email, true);
        TestBrowser b = browser();

        TestBrowser.Response callback = b.get(startAndReachCallback(b));

        assertThat(callback.status()).isEqualTo(302);
        assertThat(callback.location()).isEqualTo(FRONTEND);
        TestBrowser.Response me = b.get("/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body()).contains("\"email\":\"" + email + "\"");
        assertThat(jdbc.queryForObject("SELECT google_sub FROM users WHERE email = ?", String.class, email))
                .isEqualTo("google-sub-" + email);
    }

    @Test
    void anUnverifiedEmailIsRejectedAndNothingIsCreated() {
        String email = uniqueEmail();
        GOOGLE.signInAs("sub-unverified-" + email, email, false);
        TestBrowser b = browser();

        TestBrowser.Response callback = b.get(startAndReachCallback(b));

        assertThat(callback.location()).isEqualTo(FRONTEND + "/signin?error=EMAIL_NOT_VERIFIED");
        assertThat(b.get("/me").status()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM users WHERE email = ?", email)).isZero();
    }

    @Test
    void aTamperedStateIsRejected() {
        String email = uniqueEmail();
        GOOGLE.signInAs("sub-state-" + email, email, true);
        TestBrowser b = browser();
        String callback = startAndReachCallback(b).replaceAll("state=[^&]+", "state=forged-state");

        TestBrowser.Response r = b.get(callback);

        assertThat(r.location()).isEqualTo(FRONTEND + "/signin?error=GOOGLE_SIGNIN_FAILED");
        assertThat(b.get("/me").status()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM users WHERE email = ?", email)).isZero();
    }

    @Test
    void aCallbackWithNoStartedFlowIsRejected() {
        // A callback replayed in a browser that never started a flow has no stored state to match.
        TestBrowser starter = browser();
        String callback = startAndReachCallback(starter);

        TestBrowser stranger = browser();
        TestBrowser.Response r = stranger.get(callback);

        assertThat(r.location()).isEqualTo(FRONTEND + "/signin?error=GOOGLE_SIGNIN_FAILED");
        assertThat(stranger.get("/me").status()).isEqualTo(401);
    }

    @Test
    void anIdTokenWithTheWrongNonceIsRejected() {
        String email = uniqueEmail();
        GOOGLE.signInAs("sub-nonce-" + email, email, true);
        GOOGLE.answerWithWrongNonce();
        TestBrowser b = browser();

        TestBrowser.Response r = b.get(startAndReachCallback(b));

        assertThat(r.location()).isEqualTo(FRONTEND + "/signin?error=GOOGLE_SIGNIN_FAILED");
        assertThat(b.get("/me").status()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM users WHERE email = ?", email)).isZero();
    }

    @Test
    void anIdTokenForAnotherClientIsRejected() {
        String email = uniqueEmail();
        GOOGLE.signInAs("sub-aud-" + email, email, true);
        GOOGLE.answerWithWrongAudience();
        TestBrowser b = browser();

        TestBrowser.Response r = b.get(startAndReachCallback(b));

        assertThat(r.location()).isEqualTo(FRONTEND + "/signin?error=GOOGLE_SIGNIN_FAILED");
        assertThat(count("SELECT count(*) FROM users WHERE email = ?", email)).isZero();
    }

    @Test
    void googleAndEmailLinkWithTheSameVerifiedEmailAreOneUser() {
        String email = uniqueEmail();
        TestBrowser viaEmail = signedInBrowser(email);
        String idViaEmail = viaEmail.get("/me").body();

        GOOGLE.signInAs("sub-same-" + email, email, true);
        TestBrowser viaGoogle = browser();
        viaGoogle.get(startAndReachCallback(viaGoogle));

        assertThat(count("SELECT count(*) FROM users WHERE email = ?", email)).isEqualTo(1);
        assertThat(viaGoogle.get("/me").body()).isEqualTo(idViaEmail);
    }

    @Test
    void anotherGoogleAccountWithTheSameEmailIsAConflictNotATakeover() {
        String email = uniqueEmail();
        GOOGLE.signInAs("first-sub-" + email, email, true);
        TestBrowser first = browser();
        first.get(startAndReachCallback(first));

        GOOGLE.signInAs("second-sub-" + email, email, true);
        TestBrowser second = browser();
        TestBrowser.Response r = second.get(startAndReachCallback(second));

        assertThat(r.location()).isEqualTo(FRONTEND + "/signin?error=ACCOUNT_CONFLICT");
        assertThat(second.get("/me").status()).isEqualTo(401);
    }

    @Test
    void signingInChangesTheSessionId() {
        String email = uniqueEmail();
        GOOGLE.signInAs("sub-fixation-" + email, email, true);
        TestBrowser b = browser();
        String callback = startAndReachCallback(b); // the flow's own session now exists
        String before = b.cookie("SESSION");
        assertThat(before).isNotBlank();

        b.get(callback);

        assertThat(b.cookie("SESSION")).isNotBlank().isNotEqualTo(before);
    }

    @Test
    void otherAuthPathsAreNotTreatedAsProviders() {
        // /auth/<anything> must not be mistaken for an OAuth2 registration id.
        TestBrowser b = browser();
        assertThat(b.get("/auth/csrf").status()).isEqualTo(204);
        assertThat(b.get("/auth/nonexistent").status()).isNotEqualTo(500);
    }
}
