package com.tailor.web.generation;

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
import org.springframework.web.bind.annotation.RestController;

/** PHASE6_SPEC.md section 4.3, generation and the library. */
@RestController
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class GenerationController {

    public record GenerateRequest(List<String> sections) {
    }

    public record GenerateResponse(UUID jobId, UUID runId) {
    }

    private final GenerationService generation;
    private final LibraryService libraries;
    private final LibraryRepository libraryRepository;

    public GenerationController(GenerationService generation, LibraryService libraries, LibraryRepository libraryRepository) {
        this.generation = generation;
        this.libraries = libraries;
        this.libraryRepository = libraryRepository;
    }

    @Operation(summary = "Queues a generation run for the saved intake. Charges nothing in Phase 6; the cost is recorded. "
            + "With 'sections', only those sections are regenerated and the rest carry over unchanged. Each run is a "
            + "new library version. At most 5 runs per resume per day.", operationId = "generate")
    @ApiResponse(responseCode = "202", description = "Queued; follow the job.")
    @ApiResponse(responseCode = "409", description = "RESUME_NOT_ACCEPTED, NO_INTAKE or NO_LIBRARY",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "429", description = "GENERATION_LIMIT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/resumes/{id}/generate")
    public ResponseEntity<GenerateResponse> generate(@PathVariable UUID id,
            @RequestBody(required = false) GenerateRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthUser user) {
        GenerationService.Started started = generation.start(id, user.id(), body == null ? null : body.sections(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new GenerateResponse(started.jobId(), started.runId()));
    }

    @Operation(summary = "The newest library version: variants per section, projects, coverage and what was dropped and why.",
            operationId = "getLibrary")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (also when nothing has been generated yet)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/resumes/{id}/library")
    public LibraryService.LibraryView library(@PathVariable UUID id, @AuthenticationPrincipal AuthUser user) {
        return libraries.latest(id, user.id());
    }

    @Operation(summary = "Removes a generated variant the user doesn't stand behind. Writes a new library version; the "
            + "version they were looking at stays unchanged. Only the newest version can be changed.",
            operationId = "removeVariant")
    @ApiResponse(responseCode = "409", description = "LIBRARY_STALE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/libraries/{id}/variants/{vid}/remove")
    public LibraryService.LibraryView removeVariant(@PathVariable UUID id, @PathVariable String vid,
            @AuthenticationPrincipal AuthUser user) {
        return libraries.removeVariant(id, vid, user.id());
    }
}
