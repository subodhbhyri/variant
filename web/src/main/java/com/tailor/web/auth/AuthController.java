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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
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

    @Operation(summary = "Spends a sign-in link (single use, 15 minutes), starts the session and redirects to the app. "
            + "A bad, used or expired link redirects to {app}/signin?error=SIGNIN_LINK_INVALID.",
            operationId = "verifyEmailLink")
    @ApiResponse(responseCode = "302", description = "Redirect to the app.")
    @GetMapping("/auth/email/verify")
    public void verify(@RequestParam(required = false) String token, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        var email = tokens.consume(token);
        if (email.isEmpty()) {
            response.sendRedirect(props.frontendUrl() + "/signin?error=SIGNIN_LINK_INVALID");
            return;
        }
        AuthUser user = users.signIn(email.get(), null);
        signIn.establish(user, request, response);
        response.sendRedirect(props.frontendUrl());
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
