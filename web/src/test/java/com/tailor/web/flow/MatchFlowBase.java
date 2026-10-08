package com.tailor.web.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tailor.web.auth.TestBrowser;
import com.tailor.web.generation.LibraryRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Flow tests for matching. The account is Phase 3's synthetic projects resume (really onboarded and
 * accepted) with Phase 5's fixture material as its library, so the engine's own golden outputs
 * ({@code fixtures/phase5/match_run/golden}) are the expected answers. The sentence model is the
 * engine's fixture embedder, as for {@code tailor match --embedder fake}.
 */
public abstract class MatchFlowBase extends AcceptedResumeBase {

    @Autowired
    protected LibraryRepository libraryRepository;

    protected record Seeded(Account account, UUID libraryId) {
    }

    protected static Path phase3(String name) {
        String env = System.getenv("TAILOR_PHASE3_FIXTURES_DIR");
        return Path.of(env == null || env.isBlank() ? "/app/fixtures/phase3" : env).resolve(name);
    }

    protected static String jd(String name) throws Exception {
        return Files.readString(Fixtures.phase5().resolve("jds").resolve(name + ".txt"));
    }

    /** An accepted resume with the fixture library as its first (and so far only) library version. */
    protected Seeded seededAccount() throws Exception {
        String email = uniqueEmail();
        TestBrowser b = signedInBrowser(email);
        TestBrowser.Response up = b.postFile("/resumes", "file", "projects.docx", Files.readAllBytes(phase3("projects_synthetic.docx")));
        assertThat(up.status()).isEqualTo(202);
        UUID resumeId = UUID.fromString(JSON.readTree(up.body()).path("resume_id").asText());
        runJobs();
        assertThat(b.postJson("/resumes/" + resumeId + "/accept", "{}").status()).isEqualTo(200);
        Account account = new Account(b, userId(email), resumeId);
        return new Seeded(account, seedLibrary(account));
    }

    /** Stores variants.json's job candidates and library.json's projects as a new library version. */
    protected UUID seedLibrary(Account a) throws Exception {
        List<LibraryRepository.Item> items = new ArrayList<>();
        JsonNode variants = JSON.readTree(Fixtures.phase5().resolve("match_run").resolve("variants.json").toFile());
        variants.path("jobs").fields().forEachRemaining(job -> job.getValue().fields().forEachRemaining(candidate ->
                candidate.getValue().path("variants").fields().forEachRemaining(v -> items.add(new LibraryRepository.Item(
                        job.getKey(), candidate.getKey(), Integer.parseInt(v.getKey()), v.getValue().asText(), null, null)))));
        JsonNode library = JSON.readTree(phase3("library.json").toFile());
        for (JsonNode project : library.path("projects")) {
            ObjectNode header = JSON.createObjectNode();
            for (String f : List.of("title", "detail", "date")) {
                if (project.hasNonNull(f)) {
                    header.put(f, project.get(f).asText());
                }
            }
            header.set("links", project.has("links") ? project.get("links") : JSON.createArrayNode());
            int b = 0;
            for (JsonNode bullet : project.path("bullets")) {
                final int bulletIndex = b++;
                bullet.fields().forEachRemaining(v -> items.add(new LibraryRepository.Item(project.path("id").asText(),
                        "b" + bulletIndex, Integer.parseInt(v.getKey()), v.getValue().asText(),
                        project.hasNonNull("home_section") ? project.get("home_section").asText() : null, header)));
            }
        }
        return libraryRepository.create(a.resumeId(), a.userId(), items).id();
    }

    protected TestBrowser.Response post(Account a, String text) {
        return a.browser().postJson("/postings", "{\"text\":" + quote(text) + "}");
    }
}
