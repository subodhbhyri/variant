package com.tailor.web.resumes;

import com.tailor.web.api.ApiError;
import com.tailor.web.api.ApiException;
import com.tailor.web.auth.AuthUser;
import com.tailor.web.storage.FileStorage;
import com.tailor.web.storage.StorageProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** PHASE6_SPEC.md section 4.2. Every lookup is by id and owner; someone else's resume is a 404. */
@RestController
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class ResumeController {

    public record UploadResponse(UUID resumeId, UUID jobId) {
    }

    public record RoleRequest(String role) {
    }

    public record AcceptRequest(String fontChoice) {
    }

    public record LinkResponse(String url, java.time.Instant expiresAt) {
    }

    private final ResumeService resumes;
    private final StorageProperties storage;

    public ResumeController(ResumeService resumes, StorageProperties storage) {
        this.resumes = resumes;
        this.storage = storage;
    }

    @Operation(summary = "Uploads a resume (.docx, at most 2 MB, form field 'file'). The Phase 2 upload gate runs at once: "
            + "a rejection is a 422 with the gate's code and nothing is stored. Otherwise the resume is stored and "
            + "an onboarding job is queued.", operationId = "uploadResume")
    @ApiResponse(responseCode = "202", description = "Stored; onboarding queued.")
    @ApiResponse(responseCode = "422", description = "A gate code: FILE_TOO_LARGE, ENCRYPTED_OR_LEGACY, NOT_A_DOCX, "
            + "TOO_MANY_ENTRIES, DUPLICATE_ENTRY, UNSAFE_PATH, ZIP_BOMB, MACROS, EMBEDDED_OBJECT, EXTERNAL_RESOURCE, "
            + "UNSAFE_XML, TRACKED_CHANGES", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping(value = "/resumes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthUser user) throws IOException {
        ResumeService.Upload upload = resumes.upload(user.id(), file.getBytes(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new UploadResponse(upload.resumeId(), upload.jobId()));
    }

    @Operation(summary = "Status, onboarding report (without bullet text), section roles, positions (blocks) and font "
            + "substitutions.", operationId = "getResume")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (also for another user's resume)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/resumes/{id}")
    public ResumeService.ResumeView get(@PathVariable UUID id, @AuthenticationPrincipal AuthUser user) {
        return resumes.view(id, user.id());
    }

    @Operation(summary = "A short-lived (5 minute) link to the normalized preview PDF.", operationId = "getResumePreview")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "RESUME_NOT_READY: onboarding hasn't produced a preview",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/resumes/{id}/preview")
    public LinkResponse preview(@PathVariable UUID id, @AuthenticationPrincipal AuthUser user) {
        FileStorage.PresignedLink link = resumes.previewLink(id, user.id(), storage.presignTtl());
        return new LinkResponse(link.url(), link.expiresAt());
    }

    @Operation(summary = "Confirms a section's role. Only the suggested role can be confirmed for now: any other role "
            + "is refused with ROLE_CHANGE_UNSUPPORTED, because the engine decides roles itself.",
            operationId = "confirmSectionRole")
    @ApiResponse(responseCode = "409", description = "ROLE_CHANGE_UNSUPPORTED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PutMapping("/resumes/{id}/sections/{key}/role")
    public List<ResumeService.SectionView> role(@PathVariable UUID id, @PathVariable String key,
            @RequestBody RoleRequest body, @AuthenticationPrincipal AuthUser user) {
        if (body == null || body.role() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "A role is required.",
                    java.util.Map.of("field", "role"));
        }
        return resumes.confirmRole(id, user.id(), key, body.role());
    }

    @Operation(summary = "The user approves the normalized preview: the resume becomes the active one (the previous "
            + "active resume is archived) and any unconfirmed section role is confirmed as suggested. font_choice is "
            + "reserved for NEEDS_USER and is refused for now.", operationId = "acceptResume")
    @ApiResponse(responseCode = "409", description = "RESUME_NOT_READY or FONT_CHOICE_UNSUPPORTED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/resumes/{id}/accept")
    public ResumeService.ResumeView accept(@PathVariable UUID id, @RequestBody(required = false) AcceptRequest body,
            @AuthenticationPrincipal AuthUser user) {
        return resumes.accept(id, user.id(), body == null ? null : body.fontChoice());
    }
}
