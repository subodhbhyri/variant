package com.tailor.web.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailor.web.auth.AbstractApiTest;
import com.tailor.web.auth.TestBrowser;
import com.tailor.web.jobs.JobProcessor;
import com.tailor.web.storage.FileStorage;
import com.tailor.web.storage.StorageCleaner;
import com.tailor.web.storage.StorageKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * PHASE6_SPEC.md section 3.1 and P6-T14, filesystem mode on the real application: an upload goes through the real
 * onboarding into the storage directory (no MinIO), the api streams the preview to its owner only, and
 * {@code DELETE /me} removes every file of the user.
 */
@Tag("corpus")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({FlowTestBase.LazyRendererConfig.class, RecordedModels.class})
class FilesystemFlowTest extends AbstractApiTest {

    private static final int PORT = freePort();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DIR = tempDirectory();

    private static Path tempDirectory() {
        try {
            return Files.createTempDirectory("tailor-files-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        commonProperties(registry, PORT);
        registry.add("app.storage.mode", () -> "filesystem");
        registry.add("app.storage.dir", DIR::toString);
        registry.add("app.jobs.run-handlers", () -> "true");
        registry.add("app.match.embedder", FlowTestBase::embedderKind);
    }

    @Autowired
    FileStorage storage;
    @Autowired
    JobProcessor processor;
    @Autowired
    StorageCleaner cleaner;

    @Override
    protected String baseUrl() {
        return "http://localhost:" + PORT;
    }

    @BeforeEach
    void cleanJobs() {
        jdbc.update("DELETE FROM jobs");
    }

    private void runJobs() {
        while (processor.runNext()) {
            // a worker would do this
        }
    }

    private UUID upload(TestBrowser b) throws Exception {
        TestBrowser.Response up = b.postFile("/resumes", "file", "jane.docx", Fixtures.phase2("ok_synthetic.docx"));
        assertThat(up.status()).isEqualTo(202);
        UUID resumeId = UUID.fromString(JSON.readTree(up.body()).path("resume_id").asText());
        runJobs();
        return resumeId;
    }

    @Test
    void theApiStreamsAFileToItsOwnerAndNoOneElse() throws Exception {
        assertThat(storage).isInstanceOf(com.tailor.web.storage.FilesystemFileStorage.class);
        String email = uniqueEmail();
        TestBrowser owner = signedInBrowser(email);
        UUID resumeId = upload(owner);
        UUID userId = userId(email);
        byte[] stored = storage.get(StorageKeys.preview(userId, resumeId));
        assertThat(stored).startsWith("%PDF".getBytes());

        TestBrowser.Response linkResponse = owner.get("/resumes/" + resumeId + "/preview");
        assertThat(linkResponse.status()).isEqualTo(200);
        String url = JSON.readTree(linkResponse.body()).path("url").asText();
        assertThat(url).startsWith(baseUrl() + "/files/");
        assertThat(url).doesNotContain(userId.toString()).doesNotContain(resumeId.toString()).doesNotContain("preview");

        // The owner, signed in, gets the bytes.
        var download = java.net.http.HttpClient.newBuilder().build().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                        .header("Cookie", "SESSION=" + owner.cookie("SESSION")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(stored);
        assertThat(download.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/pdf"));
        assertThat(download.headers().firstValue("Content-Length")).hasValue(String.valueOf(stored.length));
        assertThat(download.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(owner.get(url).status()).isEqualTo(200);

        // Not signed in: the link alone is worth nothing.
        assertThat(browser().get(url).status()).isEqualTo(401);
        // Another user gets exactly what an id that never existed gets.
        TestBrowser other = signedInBrowser(uniqueEmail());
        TestBrowser.Response theirs = other.get(url);
        TestBrowser.Response nothing = other.get(baseUrl() + "/files/" + UUID.randomUUID());
        assertThat(theirs.status()).isEqualTo(404);
        assertThat(theirs.body()).isEqualTo(nothing.body());
        // A tampered id and an expired link are the same 404.
        assertThat(owner.get(url.substring(0, url.length() - 3) + "AAA").status()).isEqualTo(404);
        clock.advance(Duration.ofMinutes(6));
        assertThat(owner.get(url).status()).as("expired").isEqualTo(404);
    }

    @Test
    void theOriginalIsKeptAsUploadedInsideTheStorageDirectory() throws Exception {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        UUID resumeId = upload(b);
        UUID userId = userId(email);

        byte[] original = Fixtures.phase2("ok_synthetic.docx");
        assertThat(storage.get(StorageKeys.original(userId, resumeId))).isEqualTo(original);
        assertThat(Files.exists(DIR.resolve(StorageKeys.original(userId, resumeId)))).isTrue();
        assertThat(storage.exists(StorageKeys.normalized(userId, resumeId))).isTrue();
        assertThat(storage.exists(StorageKeys.baseline(userId, resumeId))).isTrue();
    }

    @Test
    void deletingTheAccountRemovesEveryFileOfTheUserAndOnlyTheirs() throws Exception {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        UUID resumeId = upload(b);
        UUID userId = userId(email);
        String other = uniqueEmail();
        TestBrowser keeper = signedInBrowser(other);
        upload(keeper);
        UUID keeperId = userId(other);

        int mine = storage.countPrefix(StorageKeys.userPrefix(userId));
        int theirs = storage.countPrefix(StorageKeys.userPrefix(keeperId));
        assertThat(mine).isGreaterThanOrEqualTo(4);
        assertThat(resumeId).isNotNull();

        assertThat(b.delete("/me").status()).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM users WHERE id = ?", userId)).isZero();
        cleaner.runOnce();

        assertThat(storage.countPrefix(StorageKeys.userPrefix(userId))).isZero();
        assertThat(Files.exists(DIR.resolve("users/" + userId))).isFalse();
        assertThat(storage.countPrefix(StorageKeys.userPrefix(keeperId))).isEqualTo(theirs);
    }
}
