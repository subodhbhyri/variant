package com.tailor.web.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in for Google's OpenID Connect endpoints (P6-T1 "Google (mocked provider)"): an
 * authorization endpoint that redirects straight back with a code, a token endpoint that returns
 * a real RS256-signed ID token, and a JWKS endpoint. Tests choose who signs in next.
 */
public final class StubOidcProvider implements AutoCloseable {

    public static final String CLIENT_ID = "test-client-id";

    private final HttpServer server;
    private final RSAKey key;
    private final Map<String, Pending> codes = new ConcurrentHashMap<>();
    private volatile Identity next = new Identity("sub-1", "person@example.com", true);
    private volatile String nonceOverride;
    private volatile String audienceOverride;

    public record Identity(String sub, String email, boolean emailVerified) {
    }

    private record Pending(String nonce, Identity identity) {
    }

    private StubOidcProvider() throws IOException, JOSEException {
        this.key = new RSAKeyGenerator(2048).keyID("stub-key").generate();
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/authorize", this::authorize);
        server.createContext("/token", this::token);
        server.createContext("/jwks", this::jwks);
        server.start();
    }

    public static StubOidcProvider start() {
        try {
            return new StubOidcProvider();
        } catch (IOException | JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public String authorizationUri() {
        return issuer() + "/authorize";
    }

    public String tokenUri() {
        return issuer() + "/token";
    }

    public String jwkSetUri() {
        return issuer() + "/jwks";
    }

    /** Who the next sign-in will be. */
    public void signInAs(String sub, String email, boolean emailVerified) {
        this.next = new Identity(sub, email, emailVerified);
        this.nonceOverride = null;
        this.audienceOverride = null;
    }

    /** Make the next ID token carry a nonce that doesn't match the one the app asked for. */
    public void answerWithWrongNonce() {
        this.nonceOverride = "not-the-nonce-you-sent";
    }

    public void answerWithWrongAudience() {
        this.audienceOverride = "someone-elses-client";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void authorize(HttpExchange ex) throws IOException {
        Map<String, String> q = params(ex.getRequestURI().getRawQuery());
        String code = UUID.randomUUID().toString();
        codes.put(code, new Pending(q.get("nonce"), next));
        String location = q.get("redirect_uri") + "?code=" + code + "&state=" + encode(q.get("state"));
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private void token(HttpExchange ex) throws IOException {
        Map<String, String> form = params(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        Pending pending = codes.remove(form.get("code"));
        if (pending == null) {
            respond(ex, 400, "{\"error\":\"invalid_grant\"}");
            return;
        }
        try {
            long now = System.currentTimeMillis();
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuer())
                    .subject(pending.identity().sub())
                    .audience(audienceOverride != null ? audienceOverride : CLIENT_ID)
                    .issueTime(new Date(now))
                    .expirationTime(new Date(now + 300_000))
                    .claim("nonce", nonceOverride != null ? nonceOverride : pending.nonce())
                    .claim("email", pending.identity().email())
                    .claim("email_verified", pending.identity().emailVerified());
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
            jwt.sign(new RSASSASigner(key));
            Map<String, Object> body = new HashMap<>();
            body.put("access_token", UUID.randomUUID().toString());
            body.put("token_type", "Bearer");
            body.put("expires_in", 300);
            body.put("id_token", jwt.serialize());
            respond(ex, 200, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body));
        } catch (JOSEException e) {
            respond(ex, 500, "{\"error\":\"server_error\"}");
        }
    }

    private void jwks(HttpExchange ex) throws IOException {
        respond(ex, 200, new JWKSet(key.toPublicJWK()).toString());
    }

    private static void respond(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static Map<String, String> params(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return out;
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
