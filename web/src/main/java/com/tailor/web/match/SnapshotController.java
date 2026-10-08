package com.tailor.web.match;

import com.tailor.web.api.ApiError;
import com.tailor.web.auth.AuthUser;
import com.tailor.web.layout.LayoutService;
import com.tailor.web.storage.FileStorage;
import com.tailor.web.storage.StorageProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** PHASE6_SPEC.md section 4.4, snapshots: a tailored resume. A rendered snapshot never changes. */
@RestController
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class SnapshotController {

    public record Rendering(UUID jobId) {
    }

    public record LinkResponse(String url, Instant expiresAt) {
    }

    private final MatchService matches;
    private final StorageProperties storage;
    private final LayoutService layouts;

    public SnapshotController(MatchService matches, StorageProperties storage, LayoutService layouts) {
        this.matches = matches;
        this.storage = storage;
        this.layouts = layouts;
    }

    @Operation(summary = "Where everything is on the page, for the UI: page sizes in PDF points; every bullet slot and every job "
            + "and project position with the page and box (x, y, w, h from the page's top-left) of each of its lines, whether it "
            + "is editable and the lock reason; and the changes from the onboarded resume (bullet_rewritten, project_placed, "
            + "stack_trimmed). Measured by the engine from the stored PDF.", operationId = "getSnapshotLayout")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (also for another user's snapshot)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "SNAPSHOT_NOT_RENDERED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/snapshots/{id}/layout")
    public LayoutService.LayoutView layout(@PathVariable UUID id, @AuthenticationPrincipal AuthUser user) {
        return layouts.forSnapshot(id, user.id());
    }

    @Operation(summary = "Renders an alternative on request. 202 with a job; 200 with the snapshot if it is already rendered.",
            operationId = "renderAlternative")
    @ApiResponse(responseCode = "202", description = "Rendering queued.")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/snapshots/{id}/render")
    public ResponseEntity<?> render(@PathVariable UUID id, @AuthenticationPrincipal AuthUser user) {
        MatchService.RenderRequest request = matches.requestRender(id, user.id());
        if (request.rendered() != null) {
            return ResponseEntity.ok(request.rendered());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Rendering(request.jobId()));
    }

    @Operation(summary = "A snapshot: its assembly (with project titles), status and revision chain.", operationId = "getSnapshot")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (also for another user's snapshot)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/snapshots/{id}")
    public MatchService.SnapshotView get(@PathVariable UUID id, @AuthenticationPrincipal AuthUser user) {
        return matches.snapshotView(id, user.id());
    }

    @Operation(summary = "A short-lived (5 minute) download link for the snapshot's PDF, issued only after the ownership check.",
            operationId = "getSnapshotPdf")
    @ApiResponse(responseCode = "409", description = "SNAPSHOT_NOT_RENDERED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/snapshots/{id}/pdf")
    public LinkResponse pdf(@PathVariable UUID id, @AuthenticationPrincipal AuthUser user) {
        FileStorage.PresignedLink link = matches.pdfLink(id, user.id(), storage.presignTtl());
        return new LinkResponse(link.url(), link.expiresAt());
    }
}
