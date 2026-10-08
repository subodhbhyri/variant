package com.tailor.web.edit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tailor.engine.generate.BulletEnding;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.web.api.ApiException;
import com.tailor.web.generation.IntakeService;
import com.tailor.web.generation.LibraryRepository;
import com.tailor.web.jobs.Job;
import com.tailor.web.jobs.JobService;
import com.tailor.web.jobs.JobType;
import com.tailor.web.match.MatchRepository;
import com.tailor.web.match.MatchRows.Match;
import com.tailor.web.match.MatchRows.Snapshot;
import com.tailor.web.ratelimit.RateLimiter;
import com.tailor.web.resumes.Resume;
import com.tailor.web.resumes.ResumeRepository;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * PHASE6_SPEC.md sections 4.5 and 6: checking and saving edits. The api checks what needs no render (the
 * snapshot, the slot, the text) and hands the rendering to the worker, which is the only thing that reaches
 * the renderer. A slot check is "synchronous" for the user: the request waits for the worker's answer.
 * Saving creates a NEW snapshot; nothing here ever changes the snapshot being edited, the library, or any
 * other snapshot.
 */
@Service
public class EditService {

    public static final int CHECKS_PER_MINUTE = 30;
    /** How long a slot check waits for the worker before giving up. */
    static final Duration CHECK_WAIT = Duration.ofSeconds(25);

    public record CheckResult(String verdict, Integer measuredLines, Integer targetLines, String reason) {
    }

    public record EditRequest(Integer slot, String text) {
    }

    public record Accepted(UUID snapshotId, UUID jobId) {
    }

    private final MatchRepository matches;
    private final ResumeRepository resumes;
    private final LibraryRepository libraries;
    private final SnapshotSlots slots;
    private final JobService jobs;
    private final RateLimiter limiter;
    private final ObjectMapper json;

    public EditService(MatchRepository matches, ResumeRepository resumes, LibraryRepository libraries, SnapshotSlots slots,
            JobService jobs, RateLimiter limiter, ObjectMapper json) {
        this.matches = matches;
        this.resumes = resumes;
        this.libraries = libraries;
        this.slots = slots;
        this.jobs = jobs;
        this.limiter = limiter;
        this.json = json;
    }

    /** Everything about the snapshot a check or an edit needs. */
    private record Target(Snapshot snapshot, OnboardReport report, List<SnapshotSlots.SlotInfo> slots, String ending) {
    }

    private Target target(UUID snapshotId, UUID userId) {
        Snapshot snapshot = matches.findSnapshot(snapshotId, userId).orElseThrow(ApiException::notFound);
        if (!Snapshot.RENDERED.equals(snapshot.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "SNAPSHOT_NOT_RENDERED", "Render this resume before editing it.",
                    Map.of("status", snapshot.status()));
        }
        Match match = matches.findMatch(snapshot.matchId(), userId).orElseThrow(ApiException::notFound);
        LibraryRepository.Library library = libraries.find(match.libraryId(), userId).orElseThrow(ApiException::notFound);
        Resume resume = resumes.findForUser(library.resumeId(), userId).orElseThrow(ApiException::notFound);
        OnboardReport report = IntakeService.reportOf(resume);
        List<SnapshotSlots.SlotInfo> info = slots.of(snapshot, report);
        // The resume's own convention for how a bullet ends (Phase 4 section 6.1), from its original bullets.
        String ending = BulletEnding.convention(report.slots().stream().map(OnboardReport.SlotReport::text).toList());
        return new Target(snapshot, report, info, ending);
    }

    private static SnapshotSlots.SlotInfo editable(Target t, Integer slot) {
        if (slot == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Each edit needs a slot.", Map.of("field", "slot"));
        }
        if (slot < 0 || slot >= t.slots().size() || "other".equals(t.slots().get(slot).kind())) {
            throw ApiException.notFound();
        }
        SnapshotSlots.SlotInfo info = t.slots().get(slot);
        if (!info.editable()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_LOCKED", "This bullet can't be edited.",
                    Map.of("slot", slot, "reason", String.valueOf(info.lockReason())));
        }
        return info;
    }

    private static String normalized(Target t, Integer slot, String raw) {
        EditText.Normalized n = EditText.normalize(raw, t.ending());
        if (n.tooLong()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EDIT_TOO_LONG",
                    "A bullet can be at most " + EditText.MAX_CHARS + " characters.", Map.of("slot", slot));
        }
        return n.text();
    }

    // ---- check ---------------------------------------------------------------------------------

    public CheckResult check(UUID snapshotId, UUID userId, int slot, String text) {
        Target t = target(snapshotId, userId);
        SnapshotSlots.SlotInfo info = editable(t, slot);
        String clean = normalized(t, slot, text);
        if (!limiter.tryAcquire("slot-check", userId.toString(), CHECKS_PER_MINUTE, Duration.ofMinutes(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many checks. Wait a moment and try again.");
        }
        if (clean.isEmpty()) { // a blank keeps the slot's height by construction: nothing to render
            return new CheckResult("FITS", info.lines(), info.lines(), "blank");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "check");
        payload.put("snapshotId", snapshotId.toString());
        payload.put("slot", slot);
        payload.put("text", clean);
        Job job = jobs.enqueue(userId, JobType.EDIT_REVISION, payload, null);

        long deadline = System.nanoTime() + CHECK_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            Job current = jobs.find(job.id(), userId);
            if (current != null && current.status().terminal()) {
                return resultOf(current);
            }
            try {
                Thread.sleep(75);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "CHECK_TIMEOUT", "The check took too long. Try again.");
    }

    private static CheckResult resultOf(Job job) {
        if (job.status() == Job.Status.SUCCEEDED) {
            var r = job.result();
            return new CheckResult(r.path("verdict").asText(),
                    r.hasNonNull("measured_lines") ? r.path("measured_lines").asInt() : null,
                    r.hasNonNull("target_lines") ? r.path("target_lines").asInt() : null,
                    r.hasNonNull("reason") ? r.path("reason").asText() : null);
        }
        if (job.rejected()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, job.errorCode(), "This bullet can't be checked.");
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CHECK_FAILED", "The check could not be completed. Try again.");
    }

    // ---- revisions -----------------------------------------------------------------------------

    public Accepted createRevision(UUID snapshotId, UUID userId, List<EditRequest> edits, String idempotencyKey) {
        Target t = target(snapshotId, userId);
        // A retried request (same Idempotency-Key within 24 hours) gets the revision it already started.
        var already = jobs.existing(userId, idempotencyKey);
        if (already.isPresent()) {
            Job job = already.get();
            return new Accepted(UUID.fromString(job.payload().path("revisionId").asText()), job.id());
        }
        if (edits == null || edits.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Send at least one edit.", Map.of("field", "edits"));
        }
        Set<Integer> seen = new HashSet<>();
        ArrayNode normalized = json.createArrayNode();
        for (EditRequest e : edits) {
            SnapshotSlots.SlotInfo info = editable(t, e == null ? null : e.slot());
            if (!seen.add(info.slot())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "A bullet can be edited once per revision.",
                        Map.of("field", "edits", "slot", info.slot()));
            }
            ObjectNode n = normalized.addObject();
            n.put("slot", info.slot());
            n.put("text", normalized(t, info.slot(), e.text()));
        }
        UUID revisionId = UUID.randomUUID();
        ObjectNode payload = json.createObjectNode();
        payload.put("mode", "revision");
        payload.put("snapshotId", snapshotId.toString());
        payload.put("revisionId", revisionId.toString());
        payload.set("edits", normalized);
        Job job = jobs.enqueue(userId, JobType.EDIT_REVISION, payload, idempotencyKey);
        return new Accepted(revisionId, job.id());
    }
}
