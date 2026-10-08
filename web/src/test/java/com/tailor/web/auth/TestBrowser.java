package com.tailor.web.auth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal browser for the tests: keeps cookies, never follows redirects (so tests can look at
 * each hop), and does what the SPA does for CSRF: copies the XSRF-TOKEN cookie into the
 * X-XSRF-TOKEN header on every request that changes state.
 */
public final class TestBrowser {

    public record Response(int status, String body, HttpHeaders headers) {
        public String location() {
            return headers.firstValue("Location").orElse(null);
        }

        public List<String> setCookies() {
            return headers.allValues("Set-Cookie");
        }
    }

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private final String base;
    private String forwardedFor;

    public TestBrowser(String base) {
        this.base = base;
    }

    /** Pretends to be a different client address (the app trusts X-Forwarded-For from loopback). */
    public TestBrowser from(String ip) {
        this.forwardedFor = ip;
        return this;
    }

    public String cookie(String name) {
        return cookies.get(name);
    }

    public void forgetCookie(String name) {
        cookies.remove(name);
    }

    public void setCookie(String name, String value) {
        cookies.put(name, value);
    }

    public Response get(String pathOrUrl) {
        return send("GET", pathOrUrl, null, false);
    }

    public Response postJson(String path, String json) {
        return send("POST", path, json, true);
    }

    public Response postJsonWithoutCsrf(String path, String json) {
        return send("POST", path, json, false);
    }

    public Response delete(String path) {
        return send("DELETE", path, null, true);
    }

    public Response deleteWithoutCsrf(String path) {
        return send("DELETE", path, null, false);
    }

    /** A DELETE whose X-XSRF-TOKEN header is {@code token}, whatever the cookie holds. */
    public Response deleteWithCsrfHeader(String path, String token) {
        String saved = cookies.get("XSRF-TOKEN");
        try {
            cookies.remove("XSRF-TOKEN");
            // Send the real cookie, but the given header value.
            return sendWith("DELETE", path, null, token, saved);
        } finally {
            if (saved != null) {
                cookies.put("XSRF-TOKEN", saved);
            }
        }
    }

    /**
     * Opens a server-sent-events stream and returns every {@code data:} payload received until the
     * server closes the stream (or {@code maxWait} passes, which fails the call).
     */
    public List<String> events(String path, java.time.Duration maxWait) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(maxWait).header("Accept", "text/event-stream");
        if (!cookies.isEmpty()) {
            StringBuilder header = new StringBuilder();
            cookies.forEach((k, v) -> header.append(header.isEmpty() ? "" : "; ").append(k).append('=').append(v));
            request.header("Cookie", header.toString());
        }
        try {
            HttpResponse<java.util.stream.Stream<String>> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("stream answered " + response.statusCode());
            }
            return response.body().filter(l -> l.startsWith("data:")).map(l -> l.substring(5).trim()).toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Asks for the CSRF cookie, as the SPA does before its first state-changing request. */
    public void primeCsrf() {
        get("/auth/csrf");
    }

    private Response send(String method, String pathOrUrl, String body, boolean csrf) {
        return sendWith(method, pathOrUrl, body, csrf ? cookies.get("XSRF-TOKEN") : null, null);
    }

    private Response sendWith(String method, String pathOrUrl, String body, String csrfHeader, String csrfCookie) {
        String url = pathOrUrl.startsWith("http") ? pathOrUrl : base + pathOrUrl;
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url));
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        request.method(method, publisher);
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        Map<String, String> sent = new LinkedHashMap<>(cookies);
        if (csrfCookie != null) {
            sent.put("XSRF-TOKEN", csrfCookie);
        }
        if (!sent.isEmpty()) {
            StringBuilder header = new StringBuilder();
            sent.forEach((k, v) -> header.append(header.isEmpty() ? "" : "; ").append(k).append('=').append(v));
            request.header("Cookie", header.toString());
        }
        if (csrfHeader != null) {
            request.header("X-XSRF-TOKEN", csrfHeader);
        }
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            remember(response.headers());
            return new Response(response.statusCode(), response.body(), response.headers());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void remember(HttpHeaders headers) {
        for (String setCookie : headers.allValues("Set-Cookie")) {
            String[] parts = setCookie.split(";");
            String[] nameValue = parts[0].split("=", 2);
            String name = nameValue[0].trim();
            String value = nameValue.length > 1 ? nameValue[1].trim() : "";
            boolean expired = false;
            for (String attr : parts) {
                String a = attr.trim().toLowerCase();
                if (a.equals("max-age=0") || a.startsWith("expires=thu, 01 jan 1970")) {
                    expired = true;
                }
            }
            if (expired || value.isEmpty()) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
    }
}
