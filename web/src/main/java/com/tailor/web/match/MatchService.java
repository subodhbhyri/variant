package com.tailor.web.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.match.Embedder;
import com.tailor.engine.match.JdCache;
import com.tailor.engine.match.JdParser;
import com.tailor.engine.match.JobDescription;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.web.edit.SnapshotSlots;
import com.tailor.web.api.ApiException;
import com.tailor.web.generation.LibraryRepository;
import com.tailor.web.generation.LibraryService;
import com.tailor.web.jobs.Job;
import com.tailor.web.jobs.JobService;
import com.tailor.web.jobs.JobType;
import com.tailor.web.match.MatchRows.Match;
import com.tailor.web.match.MatchRows.Posting;
import com.tailor.web.match.MatchRows.Snapshot;
import com.tailor.web.ratelimit.RateLimiter;
import com.tailor.web.resumes.Resume;
import com.tailor.web.resumes.ResumeRepository;
import com.tailor.web.storage.FileStorage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PHASE6_SPEC.md sections 4.4 and 6: postings, the cache, the match and its snapshots, as the api sees them.
 *
 * <p>The cache key is (user, library version, fingerprint). The api answers the cheap hits itself: identical
 * skill sets, or weighted Jaccard of at least 0.9, using the engine's own {@link JdCache} with an embedder that
 * cannot vouch for similarity. The band between 0.8 and 0.9 needs the sentence model, so the worker decides it.
 * A match made against another library version is never offered (a new library version means new matches and
 * new snapshots; old snapshots stay exactly as they were).
 */
@Service
public class MatchService {

    public static final int POSTINGS_PER_DAY = 200;
    private static final ObjectMapper SNAKE = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    /** Says nothing is similar: the Jaccard-only cache decision, leaving the 0.8-0.9 band to the worker. */
    private static final Embedder NO_SIMILARITY = (a, b) -> 0.0;

    public record Queued(UUID postingId, UUID jobId) {
    }

    public record Created(MatchView hit, Queued queued) {
    }

    public record JobRef(UUID id, String status, String errorCode, boolean rejected) {
    }

    /** {@code score} is the engine's total for this resume. {@code label} has project ids replaced by titles. */
    public record SnapshotSummary(UUID id, int rank, String label, Double score, String status, UUID parentId,
            UUID latestId) {
    }

    public record InfeasibleView(String project, String position, String reason) {
    }

    public record MatchView(UUID postingId, String status, boolean cacheHit, JobRef job, JsonNode jd,
            SnapshotSummary resume, List<SnapshotSummary> alternatives, List<String> missingSkills,
            List<InfeasibleView> infeasible) {
    }

    public record PostingSummary(UUID id, String title, Instant createdAt, String status) {
    }

    private final MatchRepository matches;
    private final ResumeRepository resumes;
    private final LibraryRepository libraries;
    private final LibraryService libraryService;
    private final JobService jobs;
    private final RateLimiter limiter;
    private final FileStorage storage;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final SnapshotSlots slots;

    public MatchService(MatchRepository matches, ResumeRepository resumes, LibraryRepository libraries,
            LibraryService libraryService, JobService jobs, RateLimiter limiter, FileStorage storage,
            TransactionTemplate tx, Clock clock, SnapshotSlots slots) {
        this.slots = slots;
        this.matches = matches;
        this.resumes = resumes;
        this.libraries = libraries;
        this.libraryService = libraryService;
        this.jobs = jobs;
        this.limiter = limiter;
        this.storage = storage;
        this.tx = tx;
        this.clock = clock;
    }

    // ---- creating a posting --------------------------------------------------------------------

    public Created createPosting(UUID userId, String text, String idempotencyKey) {
        JdParser.ValidationResult verdict = JdParser.validate(text == null ? "" : text);
        if (!verdict.accepted()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, verdict.reason(), verdict.message());
        }
        var already = jobs.existing(userId, idempotencyKey);
        if (already.isPresent()) { // a retried request: the posting it already made
            Job job = already.get();
            return new Created(null, new Queued(UUID.fromString(job.payload().path("postingId").asText()), job.id()));
        }
        Resume resume = resumes.findActiveForUser(userId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "NO_ACTIVE_RESUME", "Upload and accept a resume first."));
        LibraryRepository.Library library = libraries.latest(resume.id(), userId).orElseThrow(() -> new ApiException(
                HttpStatus.CONFLICT, "NO_LIBRARY", "Generate your material first."));
        if (!limiter.tryAcquire("postings", userId.toString(), POSTINGS_PER_DAY, Duration.ofDays(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "POSTING_LIMIT",
                    "You've added " + POSTINGS_PER_DAY + " postings today. Try again tomorrow.");
        }

        JobDescription jd = JdParser.parse(text, skills());
        UUID postingId = UUID.randomUUID();
        Posting posting = new Posting(postingId, userId, text, SNAKE.valueToTree(jd), fingerprint(jd), library.id(), null,
                clock.instant());

        Match source = reusable(userId, library, jd);
        if (source != null) {
            return tx.execute(status -> {
                matches.insertPosting(posting);
                // A hit writes nothing else: no render, no ledger row, and the match points at the one it reuses.
                matches.insertMatch(new Match(UUID.randomUUID(), userId, postingId, library.id(),
                        SNAKE.createObjectNode(), source.id(), clock.instant()));
                return new Created(matchView(postingId, userId), null);
            });
        }
        return tx.execute(status -> {
            matches.insertPosting(posting);
            Job job = jobs.enqueue(userId, JobType.MATCH, Map.of("postingId", postingId.toString(),
                    "libraryId", library.id().toString()), idempotencyKey);
            matches.setPostingJob(postingId, userId, job.id());
            return new Created(null, new Queued(postingId, job.id()));
        });
    }

    /** A match for the same library version whose posting is the same skill set or has weighted Jaccard of 0.9 or more. */
    private Match reusable(UUID userId, LibraryRepository.Library library, JobDescription jd) {
        for (Match candidate : matches.reusableMatches(userId, library.id())) {
            Posting prior = matches.findPosting(candidate.postingId(), userId).orElse(null);
            if (prior == null) {
                continue;
            }
            JobDescription priorJd = JdParser.parse(prior.text(), skills());
            if ("HIT".equals(JdCache.decide(priorJd, jd, NO_SIMILARITY).decision())) {
                return candidate;
            }
        }
        return null;
    }

    private static volatile SkillsDictionary skillsDictionary;

    private static SkillsDictionary skills() {
        SkillsDictionary d = skillsDictionary;
        if (d == null) {
            try {
                d = SkillsDictionary.loadDefault();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            skillsDictionary = d;
        }
        return d;
    }

    /** Fingerprint = the parsed weighted skill set + the title (PHASE5_SPEC.md section 6). */
    public static String fingerprint(JobDescription jd) {
        StringBuilder canonical = new StringBuilder(jd.title() == null ? "" : jd.title().strip()).append('\n');
        new TreeMap<>(jd.skills()).forEach((skill, weight) -> canonical.append(skill).append('=').append(weight).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- reading -------------------------------------------------------------------------------

    public List<PostingSummary> list(UUID userId, int limit) {
        List<PostingSummary> out = new ArrayList<>();
        for (Posting p : matches.postings(userId, Math.max(1, Math.min(limit, 100)))) {
            out.add(new PostingSummary(p.id(), p.parsed() == null ? null : p.parsed().path("title").asText(null),
                    p.createdAt(), status(p, userId)));
        }
        return out;
    }

    private String status(Posting p, UUID userId) {
        if (matches.findMatchForPosting(p.id(), userId).isPresent()) {
            return "ready";
        }
        Job job = p.jobId() == null ? null : jobs.find(p.jobId(), userId);
        return job != null && job.status() == Job.Status.FAILED ? "failed" : "pending";
    }

    public MatchView matchView(UUID postingId, UUID userId) {
        Posting posting = matches.findPosting(postingId, userId).orElseThrow(ApiException::notFound);
        Match own = matches.findMatchForPosting(postingId, userId).orElse(null);
        Job job = posting.jobId() == null ? null : jobs.find(posting.jobId(), userId);
        JobRef jobRef = job == null ? null
                : new JobRef(job.id(), job.status().db(), job.errorCode(), job.rejected());
        if (own == null) {
            String status = job != null && job.status() == Job.Status.FAILED ? "failed" : "pending";
            return new MatchView(postingId, status, false, jobRef, null, null, List.of(), List.of(), List.of());
        }
        boolean hit = own.cacheOf() != null;
        Match source = hit ? matches.findMatch(own.cacheOf(), userId).orElseThrow() : own;
        Map<String, String> titles = libraryService.projectTitles(source.libraryId(), userId);
        JsonNode result = source.result();

        List<Snapshot> originals = matches.originals(source.id(), userId);
        List<SnapshotSummary> summaries = new ArrayList<>();
        for (Snapshot s : originals) {
            Double score = score(result, s.rank());
            summaries.add(new SnapshotSummary(s.id(), s.rank(), LabelResolver.resolve(s.label(), titles), score,
                    s.status(), s.parentId(), matches.latestInChain(s.id(), userId)));
        }
        List<String> missing = new ArrayList<>();
        result.path("missing").forEach(m -> missing.add(m.asText()));
        List<InfeasibleView> infeasible = new ArrayList<>();
        result.path("infeasible").forEach(i -> infeasible.add(new InfeasibleView(
                LabelResolver.resolve(i.path("project").asText(null), titles), i.path("position").asText(null),
                LabelResolver.resolve(i.path("reason").asText(null), titles))));
        return new MatchView(postingId, "ready", hit, jobRef, result.path("jd"),
                summaries.isEmpty() ? null : summaries.get(0),
                summaries.size() > 1 ? summaries.subList(1, summaries.size()) : List.of(), missing, infeasible);
    }

    /** The engine's total for the resume of this rank (1-based), from match.json's list. */
    private static Double score(JsonNode result, int rank) {
        JsonNode resume = result.path("resumes").path(rank - 1);
        return resume.hasNonNull("total") ? resume.path("total").asDouble() : null;
    }

    // ---- snapshots -----------------------------------------------------------------------------

    /** A bullet of the snapshot. {@code hintChars} (named as in the onboarding report) is an estimate only. */
    public record SlotView(int slot, String kind, String position, String text, Integer lines,
            @com.fasterxml.jackson.annotation.JsonProperty("hintChars") Integer hintChars, boolean editable,
            String lockReason) {
    }

    /**
     * {@code slots}: the job and project bullets. {@code edits}: what this revision changed (null for an original).
     * {@code revisions}: its direct revisions; {@code latestRevisionId}: the newest one in its whole tree, which is
     * the one to offer for download by default (this snapshot's own id when it has no revisions).
     */
    public record SnapshotView(UUID id, int rank, String label, String status, UUID parentId, UUID matchId,
            JsonNode assembly, List<ProjectView> projects, List<SlotView> slots, JsonNode edits, List<UUID> revisions,
            UUID latestRevisionId, Instant createdAt) {
    }

    public record ProjectView(String position, String projectId, String title, JsonNode bullets, Double score) {
    }

    public SnapshotView snapshotView(UUID snapshotId, UUID userId) {
        Snapshot s = matches.findSnapshot(snapshotId, userId).orElseThrow(ApiException::notFound);
        Match match = matches.findMatch(s.matchId(), userId).orElseThrow(ApiException::notFound);
        Map<String, String> titles = libraryService.projectTitles(match.libraryId(), userId);
        List<ProjectView> projects = new ArrayList<>();
        s.assembly().path("projects").forEach(p -> projects.add(new ProjectView(p.path("position").asText(null),
                p.path("project").asText(null), titles.get(p.path("project").asText()), p.path("bullets"),
                p.hasNonNull("score") ? p.path("score").asDouble() : null)));
        List<UUID> revisions = matches.revisionsOf(s.id(), userId).stream().map(Snapshot::id).toList();
        List<SlotView> slotViews = new ArrayList<>();
        if (Snapshot.RENDERED.equals(s.status())) {
            OnboardReport report = report(match, userId);
            for (SnapshotSlots.SlotInfo i : slots.of(s, report)) {
                if (!"other".equals(i.kind())) {
                    slotViews.add(new SlotView(i.slot(), i.kind(), i.position(), i.text(), i.lines(), i.hintChars(),
                            i.editable(), i.editable() ? null : i.lockReason()));
                }
            }
        }
        return new SnapshotView(s.id(), s.rank(), LabelResolver.resolve(s.label(), titles), s.status(), s.parentId(),
                s.matchId(), s.assembly(), projects, slotViews, s.edits(), revisions, matches.latestInChain(s.id(), userId),
                s.createdAt());
    }

    private OnboardReport report(Match match, UUID userId) {
        LibraryRepository.Library library = libraries.find(match.libraryId(), userId).orElseThrow(ApiException::notFound);
        return com.tailor.web.generation.IntakeService.reportOf(
                resumes.findForUser(library.resumeId(), userId).orElseThrow(ApiException::notFound));
    }

    public FileStorage.PresignedLink pdfLink(UUID snapshotId, UUID userId, Duration ttl) {
        Snapshot s = matches.findSnapshot(snapshotId, userId).orElseThrow(ApiException::notFound); // owner first
        if (!Snapshot.RENDERED.equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "SNAPSHOT_NOT_RENDERED", "This resume hasn't been rendered yet.",
                    Map.of("status", s.status()));
        }
        return storage.presignGet(s.pdfKey(), ttl, "resume.pdf");
    }

    /** Queues the render of an alternative; an already rendered one needs nothing. */
    public record RenderRequest(UUID jobId, SnapshotView rendered) {
    }

    public RenderRequest requestRender(UUID snapshotId, UUID userId) {
        Snapshot s = matches.findSnapshot(snapshotId, userId).orElseThrow(ApiException::notFound);
        if (Snapshot.RENDERED.equals(s.status())) {
            return new RenderRequest(null, snapshotView(snapshotId, userId));
        }
        // One job per snapshot at a time: a double click gets the job already queued. A failed render may be retried.
        String key = Snapshot.FAILED.equals(s.status()) ? null : "render-alternative:" + snapshotId;
        Job job = jobs.enqueue(userId, JobType.RENDER_ALTERNATIVE, Map.of("snapshotId", snapshotId.toString()), key);
        return new RenderRequest(job.id(), null);
    }
}
