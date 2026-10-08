package com.tailor.web.api;

import java.util.Map;

/**
 * PHASE6_SPEC.md section 4: the one error shape every endpoint shares.
 * {@code message} is draft user-facing copy; the frontend must handle every {@code code}.
 */
public record ApiError(String code, String message, Map<String, Object> details) {

    public ApiError(String code, String message) {
        this(code, message, Map.of());
    }
}
