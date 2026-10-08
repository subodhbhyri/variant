package com.tailor.web.match;

import com.tailor.web.api.ApiError;
import com.tailor.web.auth.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** PHASE6_SPEC.md section 4.4, postings and matches. */
@RestController
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class PostingController {

    public record PostingRequest(String text) {
    }

    public record Accepted(UUID postingId, UUID jobId) {
    }

    private final MatchService matches;

    public PostingController(MatchService matches) {
        this.matches = matches;
    }

    @Operation(summary = "Adds a job posting (text, at most 20,000 characters) and matches it against the active resume's "
            + "newest library. A cache hit (same library version and a same or near-identical posting) is answered at "
            + "once with 200 and the match; otherwise 202 with a job to follow.", operationId = "createPosting")
    @ApiResponse(responseCode = "200", description = "Cache hit: the match.")
    @ApiResponse(responseCode = "202", description = "Matching queued.")
    @ApiResponse(responseCode = "422", description = "JD_TOO_LONG or another posting problem",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "NO_ACTIVE_RESUME or NO_LIBRARY",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "429", description = "POSTING_LIMIT: 200 postings a day",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/postings")
    public ResponseEntity<?> create(@RequestBody PostingRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthUser user) {
        MatchService.Created created = matches.createPosting(user.id(), body == null ? null : body.text(), idempotencyKey);
        if (created.hit() != null) {
            return ResponseEntity.ok(created.hit());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new Accepted(created.queued().postingId(), created.queued().jobId()));
    }

    @Operation(summary = "The user's postings, newest first.", operationId = "listPostings")
    @GetMapping("/postings")
    public List<MatchService.PostingSummary> list(@RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal AuthUser user) {
        return matches.list(user.id(), limit);
    }

    @Operation(summary = "The match: resume #1's snapshot, the scored alternatives, missing skills and infeasible "
            + "reasons. Project ids in labels are already replaced by titles. status is pending, ready or failed.",
            operationId = "getMatch")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (also for another user's posting)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/postings/{id}/match")
    public MatchService.MatchView match(@PathVariable UUID id, @AuthenticationPrincipal AuthUser user) {
        return matches.matchView(id, user.id());
    }
}
