package com.tailor.web;

import java.util.Locale;

/**
 * PHASE6_SPEC.md section 2: one image, and the environment variable {@code APP_ROLE} picks what it
 * runs. {@link #API} serves HTTP only; {@link #WORKER} claims jobs and has no web server.
 */
public enum AppRole {
    API,
    WORKER;

    public static final String ENV = "APP_ROLE";

    /** The role named by {@code value}; {@code null} or blank means {@link #API}. */
    public static AppRole parse(String value) {
        if (value == null || value.isBlank()) {
            return API;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(ENV + " must be 'api' or 'worker', got '" + value + "'");
        }
    }

    public String property() {
        return name().toLowerCase(Locale.ROOT);
    }
}
