package com.tailor.web.auth;

import com.tailor.web.api.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class MeController {

    /** Counts and cost from the usage ledger (PHASE6_SPEC.md section 8); nothing is charged in Phase 6. */
    public record Usage(long generations, long tailorings, long alternatives, BigDecimal costUsd) {
    }

    public record Me(UUID id, String email, UUID activeResumeId, Usage usage) {
    }

    private final JdbcTemplate jdbc;
    private final AccountService accounts;

    public MeController(JdbcTemplate jdbc, AccountService accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    @Operation(summary = "The signed-in user, their active resume id (null if none) and a usage summary.",
            operationId = "getMe")
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal AuthUser user) {
        UUID activeResume = jdbc.queryForList(
                "SELECT id FROM resumes WHERE user_id = ? AND active", UUID.class, user.id())
                .stream().findFirst().orElse(null);
        Usage usage = jdbc.queryForObject(
                "SELECT count(*) FILTER (WHERE kind = 'generation'),"
                        + " count(*) FILTER (WHERE kind = 'tailoring'),"
                        + " count(*) FILTER (WHERE kind = 'alternative'),"
                        + " coalesce(sum(cost_usd), 0)"
                        + " FROM usage_ledger WHERE user_id = ?",
                (rs, n) -> new Usage(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getBigDecimal(4)), user.id());
        return new Me(user.id(), user.email(), activeResume, usage);
    }

    @Operation(summary = "Deletes the account and all its data. Database rows go at once; stored files within 24 hours.",
            operationId = "deleteMe")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMe(@AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        accounts.delete(user.id());
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }
}
