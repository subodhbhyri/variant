package com.tailor.web.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailor.web.config.AppProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PHASE6_SPEC.md section 3.1, filesystem mode: atomic, write-once, no way out of the directory, opaque expiring ids. */
class FilesystemFileStorageTest {

    private static final class MovableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-10-08T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TempDir
    Path dir;
    MovableClock clock;
    FilesystemFileStorage storage;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static FilesystemFileStorage make(Path dir, Clock clock, String secret) {
        var props = new StorageProperties("filesystem", dir.toString(), secret, "tailor-files", null, null, "us-east-1",
                false, "", false, Duration.ofMinutes(5));
        var app = new AppProperties("api", "dev", "https://host.example/api", "https://host.example", null, null, null);
        return new FilesystemFileStorage(props, app, clock);
    }

    @BeforeEach
    void setUp() {
        clock = new MovableClock();
        storage = make(dir.resolve("data"), clock, "a-test-secret");
    }

    @Test
    void putsAndGetsBytesAndCreatesFoldersOnDemand() {
        String key = StorageKeys.original(UUID.randomUUID(), UUID.randomUUID());
        storage.put(key, bytes("hello"), "application/octet-stream", false);

        assertThat(storage.get(key)).isEqualTo(bytes("hello"));
        assertThat(storage.exists(key)).isTrue();
        assertThat(storage.exists("users/none/x.docx")).isFalse();
        assertThatThrownBy(() -> storage.get("users/none/x.docx")).isInstanceOf(StorageNotFoundException.class);
    }

    @Test
    void anExistingObjectIsNeverOverwrittenUnlessAskedTo() {
        String key = "users/u/resumes/r/original.docx";
        storage.put(key, bytes("first"), "x", false);

        assertThatThrownBy(() -> storage.put(key, bytes("second"), "x", false)).isInstanceOf(StorageConflictException.class);
        assertThat(storage.get(key)).isEqualTo(bytes("first"));

        storage.put(key, bytes("third"), "x", true);
        assertThat(storage.get(key)).isEqualTo(bytes("third"));
    }

    @Test
    void whenManyWritersRaceForTheSameWriteOnceKeyExactlyOneWins() throws Exception {
        String key = "users/u/snapshots/s/resume.pdf";
        int writers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();
        List<Future<?>> done = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            byte[] payload = bytes("writer-" + i);
            done.add(pool.submit(() -> {
                go.await();
                try {
                    storage.put(key, payload, "x", false);
                    won.incrementAndGet();
                } catch (StorageConflictException e) {
                    lost.incrementAndGet();
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : done) {
            f.get();
        }
        pool.shutdown();

        assertThat(won.get()).isEqualTo(1);
        assertThat(lost.get()).isEqualTo(writers - 1);
        assertThat(new String(storage.get(key), StandardCharsets.UTF_8)).startsWith("writer-");
    }

    @Test
    void aWriteLeavesNoTemporaryFilesBehind() throws IOException {
        storage.put("users/u/a.txt", bytes("a"), "x", false);
        storage.put("users/u/a.txt", bytes("b"), "x", true);
        try {
            storage.put("users/u/a.txt", bytes("c"), "x", false);
        } catch (StorageConflictException expected) {
            // fine
        }
        try (Stream<Path> files = Files.walk(dir.resolve("data"))) {
            assertThat(files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).toList())
                    .containsExactly("a.txt");
        }
    }

    @Test
    void keysCannotLeaveTheStorageDirectory() {
        for (String bad : new String[] {"../escape", "users/../../escape", "/etc/passwd", "users\\..\\x", "", "a\0b"}) {
            assertThatThrownBy(() -> storage.put(bad, bytes("x"), "x", false)).as(bad).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.get(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Files.exists(dir.resolve("escape"))).isFalse();
    }

    @Test
    void deletingAPrefixRemovesEveryFileUnderItAndNothingElse() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        storage.put(StorageKeys.original(alice, UUID.randomUUID()), bytes("1"), "x", false);
        storage.put(StorageKeys.preview(alice, UUID.randomUUID()), bytes("2"), "x", false);
        storage.put(StorageKeys.snapshotPdf(alice, UUID.randomUUID()), bytes("3"), "x", false);
        storage.put(StorageKeys.original(bob, UUID.randomUUID()), bytes("4"), "x", false);

        assertThat(storage.countPrefix(StorageKeys.userPrefix(alice))).isEqualTo(3);
        assertThat(storage.deletePrefix(StorageKeys.userPrefix(alice))).isEqualTo(3);
        assertThat(storage.countPrefix(StorageKeys.userPrefix(alice))).isZero();
        assertThat(storage.countPrefix(StorageKeys.userPrefix(bob))).isEqualTo(1);
        assertThat(storage.deletePrefix(StorageKeys.userPrefix(alice))).as("harmless twice").isZero();
        assertThat(Files.exists(dir.resolve("data/users/" + alice))).as("no empty folders left").isFalse();
    }

    @Test
    void aPrefixIsMatchedByNameNotByFolder() {
        storage.put("users/u/resumes/r1/original.docx", bytes("1"), "x", false);
        storage.put("users/u/resumes/r10/original.docx", bytes("2"), "x", false);
        assertThat(storage.countPrefix("users/u/resumes/r1")).isEqualTo(2);
        assertThat(storage.countPrefix("users/u/resumes/r1/")).isEqualTo(1);
    }

    @Test
    void aDownloadIdIsOpaqueAndOpensToTheKeyUntilItExpires() {
        String key = StorageKeys.snapshotPdf(UUID.randomUUID(), UUID.randomUUID());
        storage.put(key, bytes("%PDF"), "application/pdf", false);

        FileStorage.PresignedLink link = storage.presignGet(key, Duration.ofMinutes(5), "resume.pdf");
        assertThat(link.url()).startsWith("https://host.example/api/files/");
        String id = link.url().substring(link.url().lastIndexOf('/') + 1);
        assertThat(link.url()).doesNotContain(key).doesNotContain("users").doesNotContain("resume.pdf");

        var open = storage.open(id).orElseThrow();
        assertThat(open.key()).isEqualTo(key);
        assertThat(open.downloadName()).isEqualTo("resume.pdf");
        assertThat(link.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertThat(storage.open(id)).as("expired").isEmpty();
    }

    @Test
    void aTamperedOrForeignDownloadIdOpensNothing() {
        String key = "users/u/preview.pdf";
        String id = storage.presignGet(key, Duration.ofMinutes(5), null).url().replaceAll(".*/files/", "");

        char flipped = id.charAt(20) == 'A' ? 'B' : 'A';
        assertThat(storage.open(id.substring(0, 20) + flipped + id.substring(21))).isEmpty();
        assertThat(storage.open(id.substring(0, id.length() - 2))).isEmpty();
        assertThat(storage.open("")).isEmpty();
        assertThat(storage.open("not-base64!!")).isEmpty();
        // An id made by a server with another secret is worthless here.
        FilesystemFileStorage other = make(dir.resolve("other"), clock, "another-secret");
        assertThat(storage.open(other.presignGet(key, Duration.ofMinutes(5), null).url().replaceAll(".*/files/", ""))).isEmpty();
        // The same secret (a restart of the same deployment) still opens it.
        FilesystemFileStorage restarted = make(dir.resolve("data"), clock, "a-test-secret");
        assertThat(restarted.open(id)).isPresent();
    }

    @Test
    void theStorageDirectoryIsPrivateToTheApp() throws IOException {
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(java.nio.file.attribute.PosixFilePermissions
                    .toString(Files.getPosixFilePermissions(dir.resolve("data")))).isEqualTo("rwx------");
        }
    }
}
