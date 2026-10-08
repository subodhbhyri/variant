package com.tailor.web.resumes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tailor.engine.blocks.BlocksAnalyzer;
import com.tailor.engine.blocks.BlocksReport;
import com.tailor.engine.blocks.SectionRoles;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
     * Confirms or changes a section's role (PHASE6_SPEC.md section 4.2, revision 3). Confirming the role the section
     * already has changes nothing. A different role is handed to the engine's one role resolver
     * ({@link SectionRoles}) and the position analysis is run again on the stored normalized document, so the
     * sections, positions and every later step (intake, generation, matching) use it. No new onboarding is needed.
     * Intake answers and the library are built on the positions, so once either exists the role is fixed
     * ({@code ROLE_CHANGE_TOO_LATE}).
     */
    public List<SectionView> confirmRole(UUID resumeId, UUID userId, String key, String role) {
        Resume r = get(resumeId, userId);
        if (!ROLES.contains(role)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The role must be projects, experience or other.",
                    Map.of("field", "role"));
        }
        List<ResumeRepository.SectionRole> sections = resumes.sections(resumeId, userId);
        ResumeRepository.SectionRole section = sections.stream()
                .filter(s -> s.key().equals(key)).findFirst().orElseThrow(ApiException::notFound);
        String current = section.confirmedRole() != null ? section.confirmedRole() : section.suggestedRole();
        if (role.equals(current)) {
            resumes.confirmRole(resumeId, userId, key, role);
            return sectionViews(get(resumeId, userId));
        }
        if (!Resume.READY.equals(r.status()) && !Resume.ACCEPTED.equals(r.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "RESUME_NOT_READY", "This resume's sections can't be changed yet.",
                    Map.of("status", r.status()));
        }
        if (resumes.hasIntakeOrLibrary(resumeId, userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "ROLE_CHANGE_TOO_LATE",
                    "Roles can't be changed once you have started the intake or generated material: upload the resume again to start over.");
        }

        // The role chosen for every heading whose role is not the vocabulary's suggestion, with this change.
        Map<String, String> headings = headingsByKey(r);
        Map<String, String> chosen = new LinkedHashMap<>();
        for (ResumeRepository.SectionRole s : sections) {
            String effective = s.key().equals(key) ? role : (s.confirmedRole() != null ? s.confirmedRole() : s.suggestedRole());
            String heading = headings.get(s.key());
            if (heading != null && !effective.equals(s.suggestedRole())) {
                chosen.put(heading, effective);
            }
        }

        BlocksReport blocks;
        Path work = null;
        try {
            work = Files.createTempFile("roles-", ".docx");
            Files.write(work, storage.get(r.normalizedKey()));
            blocks = BlocksAnalyzer.analyze(work, SectionRoles.of(chosen));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong. Please try again.");
        } finally {
            if (work != null) {
                try {
                    Files.deleteIfExists(work);
                } catch (IOException ignored) {
                    // a temp file
                }
            }
        }
        if (blocks.sections().size() != sections.size()) {
            throw new ApiException(HttpStatus.CONFLICT, "ROLE_CHANGE_UNSUPPORTED",
                    "That role would change which parts of the resume are sections.",
                    Map.of("requested", role));
        }

        ObjectNode report = r.onboardJson().deepCopy();
        if (chosen.isEmpty()) {
            report.remove("sectionRoles");
        } else {
            ObjectNode roles = report.putObject("sectionRoles");
            chosen.forEach(roles::put);
        }
        // Sections that share a heading follow the same choice: keep their confirmed roles consistent with the engine.
        Map<String, String> confirmed = new LinkedHashMap<>();
        for (int i = 0; i < sections.size(); i++) {
            ResumeRepository.SectionRole s = sections.get(i);
            String effective = blocks.sections().get(i).role();
            String was = s.confirmedRole() != null ? s.confirmedRole() : s.suggestedRole();
            if (s.key().equals(key) || !effective.equals(was)) {
                confirmed.put(s.key(), effective);
            }
        }
        JsonNode blocksJson = OnboardHandler.BLOCKS_JSON.valueToTree(blocks);
        tx.executeWithoutResult(status -> resumes.applyRoleChange(resumeId, userId, report, blocksJson, confirmed));
        return sectionViews(get(resumeId, userId));
    }

    private static Map<String, String> headingsByKey(Resume r) {
        Map<String, String> headings = new java.util.HashMap<>();
        if (r.blocksJson() != null) {
            int i = 0;
            for (JsonNode s : r.blocksJson().path("sections")) {
                headings.put("s" + i++, s.path("heading").asText());
            }
        }
        return headings;
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
