package com.tailor.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The {@code app.*} settings (see application.yml). */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue("api") String role,
        /** Where the api is reached from outside: used to build sign-in links and the OIDC redirect URI. */
        @DefaultValue("http://localhost:8080") String publicBaseUrl,
        /** The SPA's origin: successful and failed browser sign-ins end here; the only CORS origin. */
        @DefaultValue("http://localhost:5173") String frontendUrl,
        @DefaultValue Mail mail,
        @DefaultValue Cookie cookie,
        @DefaultValue Google google) {

    /** {@code mode}: {@code log} prints links to the log (local development only), {@code ses} sends them. */
    public record Mail(@DefaultValue("log") String mode, @DefaultValue("noreply@localhost") String from) {
    }

    /** {@code secure} is true everywhere except local plain-HTTP development. */
    public record Cookie(@DefaultValue("true") boolean secure, String domain) {
    }

    /** Google sign-in is off until a client id is configured. The endpoints default to Google's. */
    public record Google(
            String clientId,
            String clientSecret,
            @DefaultValue("https://accounts.google.com/o/oauth2/v2/auth") String authorizationUri,
            @DefaultValue("https://oauth2.googleapis.com/token") String tokenUri,
            @DefaultValue("https://www.googleapis.com/oauth2/v3/certs") String jwkSetUri,
            @DefaultValue("https://accounts.google.com") String issuer) {

        public boolean enabled() {
            return clientId != null && !clientId.isBlank();
        }
    }
}
