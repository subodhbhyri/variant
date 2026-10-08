package com.tailor.web.storage;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * S3 (or MinIO). The client is created on first use, so the api and worker start without a
 * reachable bucket and tests that never touch files cost nothing.
 */
@Component
public class S3FileStorage implements FileStorage {

    private final StorageProperties props;
    private volatile S3Client client;
    private volatile S3Presigner presigner;

    public S3FileStorage(StorageProperties props) {
        this.props = props;
    }

    private S3Client client() {
        S3Client c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    var builder = S3Client.builder()
                            .region(Region.of(props.region()))
                            .credentialsProvider(DefaultCredentialsProvider.create())
                            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle()).build());
                    if (props.endpoint() != null && !props.endpoint().isBlank()) {
                        builder.endpointOverride(URI.create(props.endpoint()));
                    }
                    client = builder.build();
                    if (props.createBucket()) {
                        ensureBucket(client);
                    }
                }
                c = client;
            }
        }
        return c;
    }

    private S3Presigner presigner() {
        S3Presigner p = presigner;
        if (p == null) {
            synchronized (this) {
                if (presigner == null) {
                    var builder = S3Presigner.builder()
                            .region(Region.of(props.region()))
                            .credentialsProvider(DefaultCredentialsProvider.create())
                            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle()).build());
                    // Links go to the user's browser, so they are signed for the address the browser can reach.
                    String linkEndpoint = props.publicEndpoint() != null && !props.publicEndpoint().isBlank()
                            ? props.publicEndpoint() : props.endpoint();
                    if (linkEndpoint != null && !linkEndpoint.isBlank()) {
                        builder.endpointOverride(URI.create(linkEndpoint));
                    }
                    presigner = builder.build();
                }
                p = presigner;
            }
        }
        return p;
    }

    private void ensureBucket(S3Client c) {
        try {
            c.headBucket(HeadBucketRequest.builder().bucket(props.bucket()).build());
        } catch (NoSuchBucketException e) {
            c.createBucket(CreateBucketRequest.builder().bucket(props.bucket()).build());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                c.createBucket(CreateBucketRequest.builder().bucket(props.bucket()).build());
            } else {
                throw e;
            }
        }
    }

    @Override
    public void put(String key, byte[] data, String contentType, boolean overwrite) {
        PutObjectRequest.Builder request = PutObjectRequest.builder().bucket(props.bucket()).key(key).contentType(contentType);
        if (props.sse() != null && !props.sse().isBlank()) {
            request.serverSideEncryption(props.sse());
        }
        if (!overwrite) {
            request.ifNoneMatch("*"); // atomic: S3 refuses with 412 if the key exists
        }
        try {
            client().putObject(request.build(), RequestBody.fromBytes(data));
        } catch (S3Exception e) {
            if (e.statusCode() == 412 || "PreconditionFailed".equals(e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode())) {
                throw new StorageConflictException(key);
            }
            throw e;
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            ResponseBytes<GetObjectResponse> bytes = client().getObjectAsBytes(
                    GetObjectRequest.builder().bucket(props.bucket()).key(key).build());
            return bytes.asByteArray();
        } catch (NoSuchKeyException e) {
            throw new StorageNotFoundException(key);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            client().headObject(HeadObjectRequest.builder().bucket(props.bucket()).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public PresignedLink presignGet(String key, Duration ttl, String downloadName) {
        GetObjectRequest.Builder get = GetObjectRequest.builder().bucket(props.bucket()).key(key);
        if (downloadName != null) {
            get.responseContentDisposition("attachment; filename=\"" + downloadName.replace("\"", "") + "\"");
        }
        var presigned = presigner().presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl).getObjectRequest(get.build()).build());
        return new PresignedLink(presigned.url().toString(), Instant.now().plus(ttl));
    }

    /**
     * Removes the objects under {@code prefix} for good: every version and every delete marker, not just the newest
     * (a bucket with versioning on would otherwise keep a deleted user's files as old versions). Works the same on
     * a bucket without versioning, where each object has a single "null" version.
     */
    @Override
    public int deletePrefix(String prefix) {
        int removed = 0;
        String keyMarker = null;
        String versionMarker = null;
        do {
            ListObjectVersionsResponse page = client().listObjectVersions(ListObjectVersionsRequest.builder()
                    .bucket(props.bucket()).prefix(prefix).keyMarker(keyMarker).versionIdMarker(versionMarker).build());
            List<ObjectIdentifier> ids = new ArrayList<>();
            page.versions().forEach(v -> ids.add(ObjectIdentifier.builder().key(v.key()).versionId(v.versionId()).build()));
            page.deleteMarkers().forEach(m -> ids.add(ObjectIdentifier.builder().key(m.key()).versionId(m.versionId()).build()));
            if (!ids.isEmpty()) {
                client().deleteObjects(DeleteObjectsRequest.builder().bucket(props.bucket())
                        .delete(Delete.builder().objects(ids).quiet(true).build()).build());
                removed += ids.size();
            }
            keyMarker = Boolean.TRUE.equals(page.isTruncated()) ? page.nextKeyMarker() : null;
            versionMarker = Boolean.TRUE.equals(page.isTruncated()) ? page.nextVersionIdMarker() : null;
        } while (keyMarker != null);
        return removed;
    }

    /** Number of versions (including delete markers) under {@code prefix}: what is really still stored. */
    public int countVersions(String prefix) {
        int count = 0;
        String keyMarker = null;
        String versionMarker = null;
        do {
            ListObjectVersionsResponse page = client().listObjectVersions(ListObjectVersionsRequest.builder()
                    .bucket(props.bucket()).prefix(prefix).keyMarker(keyMarker).versionIdMarker(versionMarker).build());
            count += page.versions().size() + page.deleteMarkers().size();
            keyMarker = Boolean.TRUE.equals(page.isTruncated()) ? page.nextKeyMarker() : null;
            versionMarker = Boolean.TRUE.equals(page.isTruncated()) ? page.nextVersionIdMarker() : null;
        } while (keyMarker != null);
        return count;
    }

    /** Turns versioning on for the bucket (tests; Terraform does this in AWS). */
    public void enableVersioning() {
        client().putBucketVersioning(software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest.builder()
                .bucket(props.bucket()).versioningConfiguration(software.amazon.awssdk.services.s3.model.VersioningConfiguration.builder()
                        .status(software.amazon.awssdk.services.s3.model.BucketVersioningStatus.ENABLED).build()).build());
    }

    @Override
    public int countPrefix(String prefix) {
        int count = 0;
        String token = null;
        do {
            ListObjectsV2Response page = client().listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(props.bucket()).prefix(prefix).continuationToken(token).build());
            count += page.contents().size();
            token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
        } while (token != null);
        return count;
    }
}
