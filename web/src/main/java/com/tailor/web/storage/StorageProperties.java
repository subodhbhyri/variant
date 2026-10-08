package com.tailor.web.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code app.storage.*}. {@code endpoint} is set only for MinIO (with {@code pathStyle}); in AWS
 * the bucket is created by Terraform with encryption and public access blocked, and {@code sse}
 * asks S3 to encrypt each object too. {@code createBucket} is for development and tests.
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        /** {@code s3} (S3 in AWS, MinIO in development) or {@code filesystem} (the single-server deployment, PHASE6_SPEC.md section 3.1). */
        @DefaultValue("s3") String mode,
        /** Filesystem mode: the directory (a Docker volume) holding every file. */
        String dir,
        /** Filesystem mode: the secret that seals download ids. Unset, a random one is made at start-up (links last minutes). */
        String linkSecret,
        @DefaultValue("tailor-files") String bucket,
        String endpoint,
        /** Only for MinIO: the address a browser reaches it at, when that differs from {@code endpoint} (links are signed for it). */
        String publicEndpoint,
        @DefaultValue("us-east-1") String region,
        @DefaultValue("false") boolean pathStyle,
        @DefaultValue("") String sse,
        @DefaultValue("false") boolean createBucket,
        @DefaultValue("5m") Duration presignTtl) {
}
