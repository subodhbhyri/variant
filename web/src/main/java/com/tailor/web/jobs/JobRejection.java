package com.tailor.web.jobs;

import java.util.Map;

/**
 * The engine refused the input: a gate code, {@code NEEDS_USER}, {@code TOO_FEW_EDITABLE},
 * {@code COST_LIMIT}... PHASE6_SPEC.md section 5: "a result, not a failure: never retried".
 */
public final class JobRejection extends Exception {

    private final String code;
    private final Map<String, Object> details;

    public JobRejection(String code, Map<String, Object> details) {
        super(code);
        this.code = code;
        this.details = details;
    }

    public JobRejection(String code) {
        this(code, Map.of());
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
