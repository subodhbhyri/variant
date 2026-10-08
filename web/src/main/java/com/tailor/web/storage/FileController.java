package com.tailor.web.storage;

import com.tailor.web.api.ApiException;
import com.tailor.web.auth.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * PHASE6_SPEC.md section 3.1: in filesystem mode the api itself serves downloads. The id comes from a link the api
 * issued after its own ownership check (valid minutes); here the signed-in user must own the file too, and then the
 * bytes are streamed, never loaded whole. Anything else, including another user's id, answers 404.
 */
@RestController
@ConditionalOnExpression("'${app.role:api}' == 'api' and '${app.storage.mode:s3}' == 'filesystem'")
public class FileController {

    private final FilesystemFileStorage storage;

    public FileController(FilesystemFileStorage storage) {
        this.storage = storage;
    }

    @Operation(summary = "Downloads a file from a link the api issued (filesystem storage mode): the signed-in user must "
            + "own the file; the bytes are streamed. Unknown, expired and other users' ids are all 404.",
            operationId = "downloadFile")
    @ApiResponse(responseCode = "200", description = "The file.")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND")
    @GetMapping("/files/{id}")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String id, @AuthenticationPrincipal AuthUser user) {
        var download = storage.open(id)
                .filter(d -> d.key().startsWith(StorageKeys.userPrefix(user.id())))
                .orElseThrow(FileController::notFound);
        Path file = storage.file(download.key()).orElseThrow(FileController::notFound);
        long length;
        try {
            length = Files.size(file);
        } catch (IOException e) {
            throw notFound();
        }
        StreamingResponseBody body = out -> {
            try (InputStream in = Files.newInputStream(file)) {
                in.transferTo(out);
            }
        };
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(contentType(download.key()))
                .contentLength(length)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff");
        if (download.downloadName() != null) {
            response.header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(download.downloadName()).build().toString());
        }
        return response.body(body);
    }

    private static MediaType contentType(String key) {
        if (key.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        }
        if (key.endsWith(".docx")) {
            return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        }
        if (key.endsWith(".json")) {
            return MediaType.APPLICATION_JSON;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");
    }
}
