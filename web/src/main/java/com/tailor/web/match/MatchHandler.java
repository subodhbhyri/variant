package com.tailor.web.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.match.AssembledResume;
import com.tailor.engine.match.Embedder;
import com.tailor.engine.match.JdCache;
import com.tailor.engine.match.JdParser;
import com.tailor.engine.match.JobDescription;
import com.tailor.engine.match.MatchBatchSummary;
import com.tailor.engine.match.MatchRunner;
import com.tailor.engine.render.Renderer;
import com.tailor.web.generation.LibraryRepository;
import com.tailor.web.jobs.ConditionalOnJobHandlers;
import com.tailor.web.jobs.Job;
import com.tailor.web.jobs.JobHandler;
import com.tailor.web.jobs.JobRejection;
import com.tailor.web.jobs.JobType;
import com.tailor.web.ledger.UsageLedger;
import com.tailor.web.match.MatchRows.Match;
import com.tailor.web.match.MatchRows.Posting;
import com.tailor.web.match.MatchRows.Snapshot;
import com.tailor.web.resumes.Resume;
import com.tailor.web.resumes.ResumeRepository;
import com.tailor.web.storage.FileStorage;
import com.tailor.engine.progress.ProgressListener;
import com.tailor.web.storage.StorageKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code match} job (PHASE6_SPEC.md sections 4.4, 5, 6 and 8): reuse an earlier match for the same
 * library version when the posting is similar enough, otherwise run the engine's match on the cached
 * per-library context, store resume #1 rendered and verified (rank 1) and the alternatives as assemblies
 * (ranks 2 and 3, rendered on request). The stored {@code match.json} is the CLI's, field for field.
 * Logs carry ids, counts and codes only.
 */
@Component
@ConditionalOnJobHandlers
public class MatchHandler implements JobHandler {

    /** `tailor match` writes this record as match.json (snake_case). */
    public record MatchOutput(JobDescription jd, List<AssembledResume> resumes, List<String> missing,
            List<MatchBatchSummary.Infeasible> infeasible) {
    }

    private static final Logger log = LoggerFactory.getLogger(MatchHandler.class);
    private static final ObjectMapper SNAKE = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final MatchRepository matches;
    private final ResumeRepository resumes;
    private final LibraryRepository libraries;
    private final MatchContextCache contexts;
    private final FileStorage storage;
    private final Renderer renderer;
    private final Embedder embedder;
    private final UsageLedger ledger;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MatchHandler(MatchRepository matches, ResumeRepository resumes, LibraryRepository libraries,
            MatchContextCache contexts, FileStorage storage, Renderer renderer, Embedder embedder, UsageLedger ledger,
            TransactionTemplate tx, Clock clock) {
        this.matches = matches;
        this.resumes = resumes;
        this.libraries = libraries;
        this.contexts = contexts;
        this.storage = storage;
        this.renderer = renderer;
        this.embedder = embedder;
        this.ledger = ledger;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public JobType type() {
        return JobType.MATCH;
    }

    @Override
    public Map<String, Object> run(Job job, Context context) throws Exception {
        UUID userId = job.userId();
        UUID postingId = UUID.fromString(job.payload().path("postingId").asText());
        UUID libraryId = UUID.fromString(job.payload().path("libraryId").asText());
        Posting posting = matches.findPosting(postingId, userId).orElse(null);
        LibraryRepository.Library library = libraries.find(libraryId, userId).orElse(null);
        if (posting == null || library == null) {
            return Map.of("skipped", "the posting or its library no longer exists");
        }
        Match done = matches.findMatchForPosting(postingId, userId).orElse(null);
        if (done != null) { // delivered twice
            return Map.of("matchId", done.id().toString(), "alreadyDone", true);
        }
        Resume resume = resumes.findForUser(library.resumeId(), userId).orElseThrow();
        // Onboardings from another LibreOffice version are never silently reused (PHASE6_SPEC.md section 2.1).
        if (!renderer.version().equals(resume.rendererVersion())) {
            throw new JobRejection("REONBOARD_REQUIRED", Map.of("resume_id", resume.id().toString()));
        }

        ProgressListener events = context.events();
        events.started("read_posting");
        SkillsDictionary skills = SkillsDictionary.loadDefault();
        JobDescription jd = JdParser.parse(posting.text(), skills);
        events.finished("read_posting", Map.of("skills", jd.skills().size()));

        events.started("check_earlier_postings");
        Match source = similarEarlierMatch(userId, library, jd, skills);
        events.finished("check_earlier_postings", Map.of("reused", source != null));
        if (source != null) {
            events.started("deliver");
            UUID matchId = UUID.randomUUID();
            matches.insertMatch(new Match(matchId, userId, postingId, library.id(), SNAKE.createObjectNode(), source.id(),
                    clock.instant()));
            events.finished("deliver", Map.of("reused", true));
            log.info("posting {} reuses match {}", postingId, source.id());
            return Map.of("matchId", matchId.toString(), "cacheHit", true);
        }

        Path work = Files.createTempDirectory("match-" + postingId);
        try {
            Path resume1Docx = work.resolve("resume-1.docx");
            Computed computed = contexts.with(resume, library, (ctx, dir) -> {
                // The posting was read above (read_posting is already reported); the engine reports the rest as it runs.
                MatchRunner.Result result = MatchRunner.runOne(ctx, posting.text(), work, resume1Docx,
                        withoutRead(events));
                if (result.renderFailureReason() != null) {
                    return new Computed(result, null, null);
                }
                Path pdf = result.resume1Pdf() != null ? result.resume1Pdf() : ctx.renderer().render(result.resume1Docx(), work);
                return new Computed(result, MatchRunner.infeasibleFor(ctx, result), pdf);
            });
            if (computed.result().renderFailureReason() != null) {
                // No resume #1 could be built and verified for this posting: a result for the user, never retried.
                throw new JobRejection("NO_FEASIBLE_RESUME", Map.of("reason", computed.result().renderFailureReason()));
            }

            events.started("deliver");
            Map<String, Object> stored = store(userId, posting, library, jd, computed, work.resolve("resume-1.docx"));
            events.finished("deliver", Map.of("resumes", computed.result().resumes().size()));
            return stored;
        } finally {
            deleteTree(work);
        }
    }

    /** The engine reads the posting again inside its run; the handler has already reported that stage, so drop the repeat. */
    private static ProgressListener withoutRead(ProgressListener target) {
        return new ProgressListener() {
            @Override
            public void started(String stage, Map<String, Object> detail) {
                if (!"read_posting".equals(stage)) {
                    target.started(stage, detail);
                }
            }

            @Override
            public void progress(String stage, Map<String, Object> detail) {
                target.progress(stage, detail);
            }

            @Override
            public void finished(String stage, Map<String, Object> detail) {
                if (!"read_posting".equals(stage)) {
                    target.finished(stage, detail);
                }
            }
        };
    }

    private record Computed(MatchRunner.Result result, List<MatchBatchSummary.Infeasible> infeasible, Path pdf) {
    }

    /** The newest earlier match for this library version whose posting {@link JdCache} says is a HIT (with the real model). */
    private Match similarEarlierMatch(UUID userId, LibraryRepository.Library library, JobDescription jd, SkillsDictionary skills) {
        for (Match candidate : matches.reusableMatches(userId, library.id())) {
            Posting prior = matches.findPosting(candidate.postingId(), userId).orElse(null);
            if (prior != null && "HIT".equals(JdCache.decide(JdParser.parse(prior.text(), skills), jd, embedder).decision())) {
                return candidate;
            }
        }
        return null;
    }

    private Map<String, Object> store(UUID userId, Posting posting, LibraryRepository.Library library, JobDescription jd,
            Computed computed, Path docx) throws IOException {
        MatchRunner.Result result = computed.result();
        MatchOutput output = new MatchOutput(result.jd(), result.resumes(), result.missing(), computed.infeasible());
        JsonNode matchJson = SNAKE.valueToTree(output);

        UUID matchId = UUID.randomUUID();
        List<Snapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < result.resumes().size(); i++) {
            AssembledResume resume = result.resumes().get(i);
            UUID id = UUID.randomUUID();
            boolean first = i == 0;
            String docxKey = null;
            String pdfKey = null;
            if (first) { // resume #1 is rendered at once and stored with its files; the others wait to be asked for
                docxKey = StorageKeys.snapshotDocx(userId, id);
                pdfKey = StorageKeys.snapshotPdf(userId, id);
                storage.put(docxKey, Files.readAllBytes(docx), DOCX, false);
                storage.put(pdfKey, Files.readAllBytes(computed.pdf()), "application/pdf", false);
            }
            snapshots.add(new Snapshot(id, userId, matchId, i + 1, resume.label() == null ? "best match" : resume.label(),
                    SNAKE.valueToTree(resume), null, null, docxKey, pdfKey,
                    first ? Snapshot.RENDERED : Snapshot.STORED, clock.instant()));
        }
        tx.executeWithoutResult(status -> {
            matches.insertMatch(new Match(matchId, userId, posting.id(), library.id(), matchJson, null, clock.instant()));
            snapshots.forEach(matches::insertSnapshot);
            // Resume #1 delivered for the first time: one row per (user, posting fingerprint, library version).
            ledger.record(userId, UsageLedger.TAILORING, snapshots.get(0).id(),
                    "tailoring:" + userId + ":" + posting.fingerprint() + ":" + library.version(), 0.0);
        });
        log.info("posting {} matched: {} resumes, {} missing skills", posting.id(), snapshots.size(), result.missing().size());
        return Map.of("matchId", matchId.toString(), "snapshotId", snapshots.get(0).id().toString());
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort
        }
    }
}
