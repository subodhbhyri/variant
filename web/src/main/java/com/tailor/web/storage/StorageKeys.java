package com.tailor.web.storage;

import java.util.UUID;

/** PHASE6_SPEC.md section 3: {@code users/{user_id}/resumes/{resume_id}/...} and {@code users/{user_id}/snapshots/{snapshot_id}/...}. */
public final class StorageKeys {

    private StorageKeys() {
    }

    public static String userPrefix(UUID userId) {
        return "users/" + userId + "/";
    }

    public static String resumePrefix(UUID userId, UUID resumeId) {
        return userPrefix(userId) + "resumes/" + resumeId + "/";
    }

    public static String original(UUID userId, UUID resumeId) {
        return resumePrefix(userId, resumeId) + "original.docx";
    }

    public static String normalized(UUID userId, UUID resumeId) {
        return resumePrefix(userId, resumeId) + "normalized.docx";
    }

    public static String preview(UUID userId, UUID resumeId) {
        return resumePrefix(userId, resumeId) + "preview.pdf";
    }

    public static String baseline(UUID userId, UUID resumeId) {
        return resumePrefix(userId, resumeId) + "baseline.json";
    }

    public static String snapshotPrefix(UUID userId, UUID snapshotId) {
        return userPrefix(userId) + "snapshots/" + snapshotId + "/";
    }

    public static String snapshotDocx(UUID userId, UUID snapshotId) {
        return snapshotPrefix(userId, snapshotId) + "resume.docx";
    }

    public static String snapshotPdf(UUID userId, UUID snapshotId) {
        return snapshotPrefix(userId, snapshotId) + "resume.pdf";
    }
}
