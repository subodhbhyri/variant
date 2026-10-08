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
        @DefaultValue("tailor-files") String bucket,
        String endpoint,
        @DefaultValue("us-east-1") String region,
        @DefaultValue("false") boolean pathStyle,
        @DefaultValue("") String sse,
        @DefaultValue("false") boolean createBucket,
        @DefaultValue("5m") Duration presignTtl) {
}
