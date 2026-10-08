package com.tailor.web.generation;

import com.tailor.web.api.ApiError;
import com.tailor.web.auth.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** PHASE6_SPEC.md section 4.3, intake. The resume must have been accepted. */
@RestController
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class IntakeController {

    private final IntakeService intake;

    public IntakeController(IntakeService intake) {
        this.intake = intake;
    }

    @Operation(summary = "The intake form: one section per job and per swappable project position, pre-filled from the "
            + "resume and merged with the user's saved answers. Only saved sections are generated.", operationId = "getIntake")
    @ApiResponse(responseCode = "409", description = "RESUME_NOT_ACCEPTED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/resumes/{id}/intake")
    public IntakeService.IntakeView get(@PathVariable UUID id, @AuthenticationPrincipal AuthUser user) {
        return intake.view(id, user.id());
    }

    @Operation(summary = "Saves one section: mode (DETAILED, EXISTING_ONLY, SKIPPED), header fields and notes. "
            + "Refused with INVALID_LINK or NOTES_TOO_LONG (1,500 words); nothing is cut or truncated.",
            operationId = "saveIntakeSection")
    @ApiResponse(responseCode = "422", description = "INVALID_LINK, NOTES_TOO_LONG or TOO_MANY_PROJECTS",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PutMapping("/resumes/{id}/intake/sections/{sid}")
    public IntakeService.SectionView save(@PathVariable UUID id, @PathVariable String sid,
            @RequestBody IntakeService.SaveRequest body, @AuthenticationPrincipal AuthUser user) {
        return intake.save(id, user.id(), sid, body);
    }

    @Operation(summary = "Adds a project (id project-new-N). At most 8: the ninth is TOO_MANY_PROJECTS.",
            operationId = "addIntakeProject")
    @ApiResponse(responseCode = "422", description = "INVALID_LINK, NOTES_TOO_LONG or TOO_MANY_PROJECTS",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/resumes/{id}/intake/projects")
    public ResponseEntity<IntakeService.SectionView> addProject(@PathVariable UUID id,
            @RequestBody IntakeService.SaveRequest body, @AuthenticationPrincipal AuthUser user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(intake.addProject(id, user.id(), body));
    }

    @Operation(summary = "Removes an added project. The resume's own positions can't be removed; skip them instead.",
            operationId = "removeIntakeProject")
    @DeleteMapping("/resumes/{id}/intake/projects/{sid}")
    public ResponseEntity<Void> removeProject(@PathVariable UUID id, @PathVariable String sid,
            @AuthenticationPrincipal AuthUser user) {
        intake.removeProject(id, user.id(), sid);
        return ResponseEntity.noContent().build();
    }
}
