package com.tailor.web.resumes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tailor.engine.gate.GateMessages;
import com.tailor.engine.gate.GateResult;
import com.tailor.engine.gate.UploadGate;
import com.tailor.web.api.ApiException;
import com.tailor.web.jobs.Job;
import com.tailor.web.jobs.JobRepository;
import com.tailor.web.jobs.JobService;
import com.tailor.web.jobs.JobType;
import com.tailor.web.storage.FileStorage;
import com.tailor.web.storage.StorageKeys;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** PHASE6_SPEC.md section 4.2: upload, status, section roles, preview, accept. */
@Service
public class ResumeService {

    public static final Set<String> ROLES = Set.of("projects", "experience", "other");

    /** What the engine's gate and onboarding said, plus where the resume is in its life. */
    public record Upload(UUID resumeId, UUID jobId) {
    }

    public record SectionView(String key, String heading, String suggestedRole, String confirmedRole) {
    }

    public record JobRef(UUID id, String status) {
    }

    public record ResumeView(
            UUID id,
            String status,
            boolean active,
            Instant createdAt,
            JobRef job,
            JsonNode onboarding,
            List<SectionView> sections,
            JsonNode blocks,
            JsonNode fontSubstitutions) {
    }

    private final ResumeRepository resumes;
    private final JobService jobs;
    private final JobRepository jobRepository;
    private final FileStorage storage;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public ResumeService(ResumeRepository resumes, JobService jobs, JobRepository jobRepository, FileStorage storage,
            ObjectMapper json, TransactionTemplate tx) {
        this.resumes = resumes;
        this.jobs = jobs;
        this.jobRepository = jobRepository;
        this.storage = storage;
        this.json = json;
        this.tx = tx;
    }

    /**
     * Runs the Phase 2 upload gate at once (no rendering). A rejection stores nothing and says why;
     * otherwise the original is stored (never modified again) and an onboarding job is queued.
     */
    public Upload upload(UUID userId, byte[] file, String idempotencyKey) {
        // A retried request (same Idempotency-Key within 24 hours) gets the job it already made:
        // nothing new is stored.
        var already = jobs.existing(userId, idempotencyKey);
        if (already.isPresent()) {
            return new Upload(UUID.fromString(already.get().payload().path("resumeId").asText()), already.get().id());
        }
        GateResult gate;
        try {
            gate = UploadGate.check(file);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong. Please try again.");
        }
        if (!gate.accepted()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, gate.reason(), GateMessages.forReason(gate.reason()));
        }
        UUID resumeId = UUID.randomUUID();
        String key = StorageKeys.original(userId, resumeId);
        storage.put(key, file, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", false);
        return tx.execute(status -> {
            resumes.insertUploaded(resumeId, userId, key);
            Job job = jobs.enqueue(userId, JobType.ONBOARD, Map.of("resumeId", resumeId.toString()), idempotencyKey);
            resumes.setOnboardJob(resumeId, userId, job.id());
            return new Upload(resumeId, job.id());
        });
    }

    public Resume get(UUID resumeId, UUID userId) {
        return resumes.findForUser(resumeId, userId).orElseThrow(ApiException::notFound);
    }

    /**
     * The resume as the user sees it. A resume whose onboarding job died without the worker saying so
     * (lost worker, time limit) is reported as {@code failed}, not stuck "onboarding" forever.
     */
    public ResumeView view(UUID resumeId, UUID userId) {
        Resume r = get(resumeId, userId);
        Job job = r.onboardJobId() == null ? null : jobRepository.findForUser(r.onboardJobId(), userId).orElse(null);
        String status = r.status();
        if ((Resume.UPLOADED.equals(status) || Resume.ONBOARDING.equals(status))
                && job != null && job.status() == Job.Status.FAILED) {
            status = Resume.FAILED;
        }
        return new ResumeView(r.id(), status, r.active(), r.createdAt(),
                job == null ? null : new JobRef(job.id(), job.status().db()),
                onboardingSummary(r.onboardJson()), sectionViews(r), r.blocksJson(),
                r.onboardJson() == null ? null : r.onboardJson().path("fonts"));
    }

    /** The onboarding report without the bullet text: counts, pages, lock reasons and line counts only. */
    private JsonNode onboardingSummary(JsonNode report) {
        if (report == null) {
            return null;
        }
        ObjectNode out = json.createObjectNode();
        for (String field : List.of("accepted", "reason", "message", "pages", "shrinkPt", "squeezeRemoved",
                "positionRemoved", "trailingEmptyRemoved", "editableCount", "rendererVersion")) {
            if (report.hasNonNull(field)) {
                out.set(field, report.get(field));
            }
        }
        ArrayNode slots = out.putArray("slots");
        for (JsonNode slot : report.path("slots")) {
            ObjectNode s = slots.addObject();
            s.put("index", slot.path("index").asInt());
            s.put("editable", slot.path("editable").asBoolean());
            if (slot.hasNonNull("lockReason")) {
                s.set("lockReason", slot.get("lockReason"));
            }
        }
        return out;
    }

    private List<SectionView> sectionViews(Resume r) {
        Map<String, String> headings = new java.util.HashMap<>();
        if (r.blocksJson() != null) {
            int i = 0;
            for (JsonNode s : r.blocksJson().path("sections")) {
                headings.put("s" + i++, s.path("heading").asText());
            }
        }
        return resumes.sections(r.id(), r.userId()).stream()
                .map(s -> new SectionView(s.key(), headings.getOrDefault(s.key(), ""), s.suggestedRole(), s.confirmedRole()))
                .toList();
    }

    /**
     * Confirms a section's role. The engine decides roles itself from the heading text everywhere it
     * uses them (analysis, swapping, generation) and has no way to take a different role from the
     * user, so only the suggested role can be confirmed; anything else is refused instead of being
     * recorded and then silently ignored downstream.
     */
    public List<SectionView> confirmRole(UUID resumeId, UUID userId, String key, String role) {
        Resume r = get(resumeId, userId);
        if (!ROLES.contains(role)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The role must be projects, experience or other.",
                    Map.of("field", "role"));
        }
        ResumeRepository.SectionRole section = resumes.sections(resumeId, userId).stream()
                .filter(s -> s.key().equals(key)).findFirst().orElseThrow(ApiException::notFound);
        if (!role.equals(section.suggestedRole())) {
            throw new ApiException(HttpStatus.CONFLICT, "ROLE_CHANGE_UNSUPPORTED",
                    "This section was recognised as '" + section.suggestedRole() + "'. Changing it isn't supported yet.",
                    Map.of("suggested", section.suggestedRole(), "requested", role));
        }
        resumes.confirmRole(resumeId, userId, key, role);
        return sectionViews(get(resumeId, userId));
    }

    /** The user approves the normalized preview: the resume becomes the active one; any other is archived. */
    public ResumeView accept(UUID resumeId, UUID userId, String fontChoice) {
        Resume r = get(resumeId, userId);
        if (fontChoice != null) {
            // The NEEDS_USER choice (PHASE6_SPEC.md 4.2) needs the engine to re-onboard with a chosen font,
            // which it cannot do yet.
            throw new ApiException(HttpStatus.CONFLICT, "FONT_CHOICE_UNSUPPORTED",
                    "Choosing a font isn't supported yet.");
        }
        if (Resume.ACCEPTED.equals(r.status()) && r.active()) {
            return view(resumeId, userId); // accepting twice is harmless
        }
        if (!Resume.READY.equals(r.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "RESUME_NOT_READY",
                    "This resume can't be accepted yet.", Map.of("status", r.status()));
        }
        tx.executeWithoutResult(status -> resumes.activate(resumeId, userId));
        return view(resumeId, userId);
    }

    public FileStorage.PresignedLink previewLink(UUID resumeId, UUID userId, java.time.Duration ttl) {
        Resume r = get(resumeId, userId); // ownership first, then the link
        if (r.previewKey() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "RESUME_NOT_READY", "The preview isn't ready yet.",
                    Map.of("status", r.status()));
        }
        return storage.presignGet(r.previewKey(), ttl, null);
    }
}
