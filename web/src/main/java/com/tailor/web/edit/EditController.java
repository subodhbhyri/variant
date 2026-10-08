package com.tailor.web.edit;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** PHASE6_SPEC.md section 4.5, edits. */
@RestController
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class EditController {

    public record CheckRequest(String text) {
    }

    public record RevisionRequest(List<EditService.EditRequest> edits) {
    }

    private final EditService edits;

    public EditController(EditService edits) {
        this.edits = edits;
    }

    @Operation(summary = "Checks one bullet's new text: the parent is rendered with just that slot replaced (one render) "
            + "and the verdict is FITS, FITS_WITH_PADDING or TOO_LONG. Synchronous (about a second); 30 a minute. "
            + "Empty text is a blank and always fits. Text is normalized first (trimmed, newlines and tabs to spaces, "
            + "spaces collapsed, at most 1,000 characters).", operationId = "checkSlot")
    @ApiResponse(responseCode = "422", description = "EDIT_TOO_LONG or SLOT_LOCKED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMITED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/snapshots/{id}/slots/{slot}/check")
    public EditService.CheckResult check(@PathVariable UUID id, @PathVariable int slot, @RequestBody CheckRequest body,
            @AuthenticationPrincipal AuthUser user) {
        return edits.check(id, user.id(), slot, body == null ? null : body.text());
    }

    @Operation(summary = "Applies edits as a NEW snapshot (a revision of this one): the edits are applied to this snapshot's "
            + "document, every one is re-checked, the whole document is verified, and only then is the revision created. "
            + "The snapshot being edited never changes. If a check or the verification fails nothing is created and the "
            + "job's rejected result names the slot and the reason (TOO_LONG or VERIFY_FAILED).", operationId = "createRevision")
    @ApiResponse(responseCode = "202", description = "Queued: snapshot_id is the revision's id once the job succeeds.")
    @ApiResponse(responseCode = "422", description = "EDIT_TOO_LONG or SLOT_LOCKED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/snapshots/{id}/revisions")
    public ResponseEntity<EditService.Accepted> revise(@PathVariable UUID id, @RequestBody RevisionRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthUser user) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(edits.createRevision(id, user.id(), body == null ? null : body.edits(), idempotencyKey));
    }
}
