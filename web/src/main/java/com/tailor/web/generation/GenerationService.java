package com.tailor.web.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tailor.web.api.ApiException;
import com.tailor.web.jobs.Job;
import com.tailor.web.jobs.JobService;
import com.tailor.web.jobs.JobType;
import com.tailor.web.resumes.Resume;
import com.tailor.web.resumes.ResumeRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code POST /resumes/{id}/generate} (PHASE6_SPEC.md sections 4.3 and 7): queues a generation run.
 * It charges nothing in Phase 6 (the cost is recorded in the ledger when the run finishes), and
 * runs cost money, so there is a soft limit of {@value #RUNS_PER_RESUME_PER_DAY} runs per resume
 * per day (provisional) and a retried request with the same Idempotency-Key never starts a second.
 */
@Service
public class GenerationService {

    public static final int RUNS_PER_RESUME_PER_DAY = 5;

    public record Started(UUID jobId, UUID runId) {
    }

    private final ResumeRepository resumes;
    private final IntakeService intake;
    private final LibraryRepository libraries;
    private final JobService jobs;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Clock clock;

    public GenerationService(ResumeRepository resumes, IntakeService intake, LibraryRepository libraries, JobService jobs,
            ObjectMapper json, TransactionTemplate tx, Clock clock) {
        this.resumes = resumes;
        this.intake = intake;
        this.libraries = libraries;
        this.jobs = jobs;
        this.json = json;
        this.tx = tx;
        this.clock = clock;
    }

    /** @param sections ids to regenerate (after editing those sections' notes), or null for every saved section */
    public Started start(UUID resumeId, UUID userId, List<String> sections, String idempotencyKey) {
        Resume resume = resumes.findForUser(resumeId, userId).orElseThrow(ApiException::notFound);
        if (!Resume.ACCEPTED.equals(resume.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "RESUME_NOT_ACCEPTED", "Accept the resume's preview first.",
                    Map.of("status", resume.status()));
        }
        // A retried request gets the run it already started, before any limit is counted.
        var already = jobs.existing(userId, idempotencyKey);
        if (already.isPresent()) {
            Job job = already.get();
            return new Started(job.id(), UUID.fromString(job.payload().path("runId").asText()));
        }

        Set<String> saved = intake.savedIds(resumeId, userId);
        Set<String> only = null;
        if (sections != null && !sections.isEmpty()) {
            only = new HashSet<>(sections);
            for (String id : only) {
                if (!saved.contains(id)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                            "Save that section's answers before generating it.", Map.of("field", "sections"));
                }
            }
            if (libraries.latest(resumeId, userId).isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "NO_LIBRARY",
                        "Generate every section once before regenerating only some.");
            }
        } else if (saved.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_INTAKE", "Answer at least one section before generating.");
        }
        if (libraries.runsSince(resumeId, userId, clock.instant().minus(Duration.ofHours(24))) >= RUNS_PER_RESUME_PER_DAY) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "GENERATION_LIMIT",
                    "You've generated this resume " + RUNS_PER_RESUME_PER_DAY + " times today. Try again tomorrow.");
        }

        UUID runId = UUID.randomUUID();
        Set<String> chosen = only;
        return tx.execute(status -> {
            ObjectNode payload = json.createObjectNode().put("resumeId", resumeId.toString()).put("runId", runId.toString());
            if (chosen != null) {
                var array = payload.putArray("sections");
                chosen.stream().sorted().forEach(array::add);
            }
            Job job = jobs.enqueue(userId, JobType.GENERATE, payload, idempotencyKey);
            libraries.createRun(runId, resumeId, userId, job.id());
            return new Started(job.id(), runId);
        });
    }
}
