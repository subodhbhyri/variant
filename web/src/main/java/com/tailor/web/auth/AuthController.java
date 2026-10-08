package com.tailor.web.auth;

import com.tailor.web.api.ApiError;
import com.tailor.web.config.AppProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PHASE6_SPEC.md section 4.1 (Google sign-in itself, {@code GET /auth/google} and its callback, is
 * served by Spring Security's OpenID Connect filters; see {@link com.tailor.web.config.SecurityConfig}).
 */
@RestController
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class AuthController {

    public record EmailRequest(String email) {
    }

    public record TokenRequest(String token) {
    }

    private final LoginTokenService tokens;
    private final UserRepository users;
    private final SessionSignIn signIn;
    private final AppProperties props;

    public AuthController(LoginTokenService tokens, UserRepository users, SessionSignIn signIn, AppProperties props) {
        this.tokens = tokens;
        this.users = users;
        this.signIn = signIn;
        this.props = props;
    }

    @Operation(summary = "Sets the CSRF cookie (XSRF-TOKEN); send its value back in the X-XSRF-TOKEN header on every "
            + "POST, PUT and DELETE.", operationId = "csrf")
    @ApiResponse(responseCode = "204", description = "The cookie is set.")
    @GetMapping("/auth/csrf")
    public ResponseEntity<Void> csrf() {
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Sends a sign-in link by email. Always 202 for a valid address, whether or not it has an "
            + "account, so it can't be used to probe accounts.", operationId = "requestEmailLink")
    @ApiResponse(responseCode = "202", description = "If the address is valid, a link is on its way.")
    @ApiResponse(responseCode = "400", description = "INVALID_EMAIL",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMITED: 5 links per address and 20 per IP per hour.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/auth/email")
    public ResponseEntity<Map<String, String>> requestLink(@RequestBody EmailRequest body, HttpServletRequest request) {
        tokens.request(body == null ? null : body.email(), request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("message", "If the address is valid, a sign-in link is on its way."));
    }

    @Operation(summary = "Shows a confirmation page for a sign-in link (\"Continue as {email}\", one button) WITHOUT "
            + "spending the link: email scanners open links before people do. A bad, used or expired link redirects "
            + "to {app}/signin?error=SIGNIN_LINK_INVALID.", operationId = "confirmEmailLink")
    @ApiResponse(responseCode = "200", description = "The confirmation page (text/html).")
    @ApiResponse(responseCode = "302", description = "Redirect to the app's sign-in page: the link is not valid.")
    @GetMapping(value = "/auth/email/verify", produces = MediaType.TEXT_HTML_VALUE)
    public void confirm(@RequestParam(required = false) String token, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        var email = tokens.peek(token);
        if (email.isEmpty()) {
            response.sendRedirect(props.frontendUrl() + "/signin?error=SIGNIN_LINK_INVALID");
            return;
        }
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        response.setContentType("text/html;charset=UTF-8");
        // The token is in the address bar and in the page: keep both out of caches, referrers and frames.
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'");
        response.getWriter().write(confirmationPage(email.get(), token, csrf == null ? "" : csrf.getToken(),
                props.publicBaseUrl() + "/auth/email/verify"));
    }

    @Operation(summary = "Spends a sign-in link (single use, 15 minutes) and starts the session. JSON {\"token\"} gets "
            + "200 {\"redirect\"}; the confirmation page's form post gets a redirect to the app. A bad, used or "
            + "expired link is 400 SIGNIN_LINK_INVALID (or a redirect to {app}/signin?error=SIGNIN_LINK_INVALID). "
            + "CSRF-protected like every other POST.", operationId = "verifyEmailLink")
    @ApiResponse(responseCode = "200", description = "Signed in.")
    @ApiResponse(responseCode = "400", description = "SIGNIN_LINK_INVALID",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping(value = "/auth/email/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> verify(@RequestBody TokenRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        var email = tokens.consume(body == null ? null : body.token());
        if (email.isEmpty()) {
            return ResponseEntity.badRequest().body(new ApiError("SIGNIN_LINK_INVALID",
                    "This sign-in link is invalid, expired or already used."));
        }
        signIn.establish(users.signIn(email.get(), null), request, response);
        return ResponseEntity.ok(Map.of("redirect", props.frontendUrl()));
    }

    @PostMapping(value = "/auth/email/verify", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(hidden = true)
    public void verifyForm(@RequestParam(required = false) String token, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        var email = tokens.consume(token);
        if (email.isEmpty()) {
            response.sendRedirect(props.frontendUrl() + "/signin?error=SIGNIN_LINK_INVALID");
            return;
        }
        signIn.establish(users.signIn(email.get(), null), request, response);
        response.setStatus(HttpStatus.SEE_OTHER.value());
        response.setHeader("Location", props.frontendUrl());
    }

    private static String confirmationPage(String email, String token, String csrf, String action) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"referrer\" content=\"no-referrer\"><title>Sign in</title>"
                + "<style>body{font:16px system-ui,sans-serif;max-width:26rem;margin:15vh auto;padding:0 1rem;text-align:center}"
                + "button{font:inherit;padding:.7rem 1.4rem;border:0;border-radius:.4rem;background:#1a56db;color:#fff;cursor:pointer}"
                + "</style></head><body><h1>Sign in</h1><p>You are signing in as <strong>" + escape(email) + "</strong>.</p>"
                + "<form method=\"post\" action=\"" + escape(action) + "\">"
                + "<input type=\"hidden\" name=\"token\" value=\"" + escape(token) + "\">"
                + "<input type=\"hidden\" name=\"_csrf\" value=\"" + escape(csrf) + "\">"
                + "<button type=\"submit\">Continue as " + escape(email) + "</button></form>"
                + "<p><small>If you didn't ask to sign in, close this page.</small></p></body></html>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    @Operation(summary = "Ends the session.", operationId = "logout")
    @ApiResponse(responseCode = "204", description = "Signed out (also when there was no session).")
    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }
}
