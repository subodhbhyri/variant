package com.tailor.web.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.match.AssembledResume;
import com.tailor.engine.match.ResumeRenderer;
import com.tailor.web.generation.LibraryRepository;
import com.tailor.web.jobs.ConditionalOnJobHandlers;
import com.tailor.web.jobs.Job;
import com.tailor.web.jobs.JobHandler;
import com.tailor.web.jobs.JobRejection;
import com.tailor.web.jobs.JobType;
import com.tailor.web.ledger.UsageLedger;
import com.tailor.web.match.MatchRows.Match;
import com.tailor.web.match.MatchRows.Snapshot;
import com.tailor.web.resumes.Resume;
import com.tailor.web.resumes.ResumeRepository;
import com.tailor.web.storage.FileStorage;
import com.tailor.web.storage.StorageKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code render_alternative} job (PHASE6_SPEC.md sections 4.4 and 6): renders a stored alternative
 * (rank 2 or 3) exactly as assembled, verifies it, stores its files and only then marks it rendered,
 * which freezes it. One ledger row per snapshot. A render that cannot be verified is a result for the
 * user (nothing downloadable is stored), and may be asked for again.
 */
@Component
@ConditionalOnJobHandlers
public class RenderAlternativeHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(RenderAlternativeHandler.class);
    private static final ObjectMapper SNAKE = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final MatchRepository matches;
    private final ResumeRepository resumes;
    private final LibraryRepository libraries;
    private final MatchContextCache contexts;
    private final FileStorage storage;
    private final UsageLedger ledger;
    private final TransactionTemplate tx;

    public RenderAlternativeHandler(MatchRepository matches, ResumeRepository resumes, LibraryRepository libraries,
            MatchContextCache contexts, FileStorage storage, UsageLedger ledger, TransactionTemplate tx) {
        this.matches = matches;
        this.resumes = resumes;
        this.libraries = libraries;
        this.contexts = contexts;
        this.storage = storage;
        this.ledger = ledger;
        this.tx = tx;
    }

    @Override
    public JobType type() {
        return JobType.RENDER_ALTERNATIVE;
    }

    @Override
    public Map<String, Object> run(Job job, Context context) throws Exception {
        UUID userId = job.userId();
        UUID snapshotId = UUID.fromString(job.payload().path("snapshotId").asText());
        Snapshot snapshot = matches.findSnapshot(snapshotId, userId).orElse(null);
        if (snapshot == null) {
            return Map.of("skipped", "the snapshot no longer exists");
        }
        if (Snapshot.RENDERED.equals(snapshot.status())) { // delivered twice, or rendered meanwhile
            return Map.of("snapshotId", snapshotId.toString(), "alreadyDone", true);
        }
        Match match = matches.findMatch(snapshot.matchId(), userId).orElseThrow();
        LibraryRepository.Library library = libraries.find(match.libraryId(), userId).orElseThrow();
        Resume resume = resumes.findForUser(library.resumeId(), userId).orElseThrow();

        matches.startRendering(snapshotId, userId);
        context.stage("rendering");
        AssembledResume assembled = SNAKE.treeToValue(snapshot.assembly(), AssembledResume.class);
        Path work = Files.createTempDirectory("alternative-" + snapshotId);
        try {
            Path docx = work.resolve("resume.docx");
            Rendered rendered = contexts.with(resume, library, (ctx, dir) -> {
                ResumeRenderer.RenderResult result = ResumeRenderer.render(ctx.onboardedDocx(), ctx.report(), assembled,
                        ctx.jobCandidatesById(), ctx.library(), ctx.renderer(), ctx.fontMap(), work, docx);
                if (!result.ok()) {
                    return new Rendered(false, result.detail(), null);
                }
                return new Rendered(true, null, ctx.renderer().render(result.outputDocx(), work));
            });
            if (!rendered.ok()) {
                matches.markFailed(snapshotId, userId);
                throw new JobRejection("RENDER_FAILED", Map.of("reason", String.valueOf(rendered.detail())));
            }

            context.stage("storing");
            String docxKey = StorageKeys.snapshotDocx(userId, snapshotId);
            String pdfKey = StorageKeys.snapshotPdf(userId, snapshotId);
            // The snapshot's own ids make these keys unique; a retry of this job may write them again.
            storage.put(docxKey, Files.readAllBytes(docx), DOCX, true);
            storage.put(pdfKey, Files.readAllBytes(rendered.pdf()), "application/pdf", true);
            tx.executeWithoutResult(status -> {
                matches.markRendered(snapshotId, userId, docxKey, pdfKey);
                ledger.record(userId, UsageLedger.ALTERNATIVE, snapshotId, "alternative:" + snapshotId, 0.0);
            });
            log.info("alternative {} rendered", snapshotId);
            return Map.of("snapshotId", snapshotId.toString());
        } finally {
            deleteTree(work);
        }
    }

    private record Rendered(boolean ok, String detail, Path pdf) {
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort
        }
    }
}
