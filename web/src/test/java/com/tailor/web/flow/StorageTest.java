package com.tailor.web.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailor.web.storage.FileStorage;
import com.tailor.web.storage.StorageConflictException;
import com.tailor.web.storage.StorageKeys;
import com.tailor.web.storage.StorageNotFoundException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The S3 layer against MinIO: private objects, never overwritten, short-lived links, prefix deletion. */
class StorageTest extends FlowTestBase {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String key(String name) {
        return "storage-test/" + UUID.randomUUID() + "/" + name;
    }

    @Test
    void putsAndGetsBytes() {
        String k = key("a.txt");
        storage.put(k, bytes("hello"), "text/plain", false);

        assertThat(storage.get(k)).isEqualTo(bytes("hello"));
        assertThat(storage.exists(k)).isTrue();
        assertThat(storage.exists(key("nope"))).isFalse();
        assertThatThrownBy(() -> storage.get(key("nope"))).isInstanceOf(StorageNotFoundException.class);
    }

    @Test
    void anExistingObjectIsNeverOverwrittenUnlessAskedTo() {
        String k = key("original.docx");
        storage.put(k, bytes("first"), "text/plain", false);

        assertThatThrownBy(() -> storage.put(k, bytes("second"), "text/plain", false))
                .isInstanceOf(StorageConflictException.class);
        assertThat(storage.get(k)).isEqualTo(bytes("first"));

        storage.put(k, bytes("third"), "text/plain", true); // derived files may be rewritten
        assertThat(storage.get(k)).isEqualTo(bytes("third"));
    }

    @Test
    void aPresignedLinkDownloadsTheObjectWithoutCredentials() throws Exception {
        String k = key("preview.pdf");
        storage.put(k, bytes("%PDF-fake"), "application/pdf", false);

        FileStorage.PresignedLink link = storage.presignGet(k, Duration.ofMinutes(5), "resume.pdf");

        assertThat(link.expiresAt()).isBetween(Instant.now().plusSeconds(240), Instant.now().plusSeconds(310));
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(link.url())).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(bytes("%PDF-fake"));
        assertThat(response.headers().firstValue("Content-Disposition")).hasValueSatisfying(v -> assertThat(v).contains("resume.pdf"));
    }

    @Test
    void anExpiredLinkStopsWorking() throws Exception {
        String k = key("short.txt");
        storage.put(k, bytes("x"), "text/plain", false);
        FileStorage.PresignedLink link = storage.presignGet(k, Duration.ofSeconds(1), null);
        Thread.sleep(2500);

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(link.url())).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isIn(400, 403);
    }

    @Test
    void theBucketIsNotReadableWithoutASignature() throws Exception {
        String k = key("private.txt");
        storage.put(k, bytes("secret"), "text/plain", false);
        String signed = storage.presignGet(k, Duration.ofMinutes(5), null).url();
        String unsigned = signed.substring(0, signed.indexOf('?'));

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(unsigned)).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isIn(401, 403);
        assertThat(response.body()).doesNotContain("secret");
    }

    @Test
    void deletingAPrefixRemovesOnlyThatPrefix() {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        storage.put(StorageKeys.original(user, UUID.randomUUID()), bytes("a"), "text/plain", false);
        storage.put(StorageKeys.original(user, UUID.randomUUID()), bytes("a2"), "text/plain", false);
        storage.put(StorageKeys.snapshotPdf(user, UUID.randomUUID()), bytes("b"), "text/plain", false);
        storage.put(StorageKeys.original(other, UUID.randomUUID()), bytes("c"), "text/plain", false);

        assertThat(storage.countPrefix(StorageKeys.userPrefix(user))).isEqualTo(3);
        assertThat(storage.deletePrefix(StorageKeys.userPrefix(user))).isEqualTo(3);

        assertThat(storage.countPrefix(StorageKeys.userPrefix(user))).isZero();
        assertThat(storage.countPrefix(StorageKeys.userPrefix(other))).isEqualTo(1);
    }
}
