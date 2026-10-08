package com.tailor.web.api;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** An error that maps to an {@link ApiError} body and an HTTP status. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    /**
     * Another user's resource, or one that does not exist. Both answer 404 (not 403) so ids can't
     * be probed (PHASE6_SPEC.md section 9.2).
     */
    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");
    }

    public HttpStatus status() {
        return status;
    }

    public ApiError body() {
        return new ApiError(code, getMessage(), details);
    }
}
