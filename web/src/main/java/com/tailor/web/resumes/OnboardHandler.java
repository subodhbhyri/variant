package com.tailor.web.resumes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.blocks.BlocksAnalyzer;
import com.tailor.engine.blocks.BlocksReport;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.gate.GateReason;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.Renderer;
import com.tailor.web.jobs.ConditionalOnJobHandlers;
import com.tailor.web.jobs.Job;
import com.tailor.web.jobs.JobHandler;
import com.tailor.web.jobs.JobRejection;
import com.tailor.web.jobs.JobType;
import com.tailor.web.storage.FileStorage;
import com.tailor.web.storage.StorageKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The {@code onboard} job (PHASE6_SPEC.md sections 4.2 and 5): runs the Phase 2/3 pipeline on the
 * stored original and stores its outputs. The engine's own result is kept as-is, so the stored
 * {@code onboard_json} is the CLI's {@code onboard.json} and {@code blocks_json} is the output of
 * {@code tailor blocks}. A gate rejection, {@code NEEDS_USER}, {@code TOO_MANY_PAGES} or
 * {@code TOO_FEW_EDITABLE} is a result for the user, not a failure. Logs carry ids and codes only.
 *
 * <p>The engine reports no intermediate stages, so progress is the stages this handler can see:
 * {@code gate}, {@code processing} (normalize, detect and calibrate run as one engine call) and
 * {@code storing}.
 */
@Component
@ConditionalOnJobHandlers
public class OnboardHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(OnboardHandler.class);
    /** `tailor blocks` prints snake_case; the onboarding report is the engine's own camelCase. Both as the CLI writes them. */
    private static final ObjectMapper BLOCKS_JSON =
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private static final ObjectMapper REPORT_JSON = new ObjectMapper();

    private final ResumeRepository resumes;
    private final FileStorage storage;
    private final Renderer renderer;
    private final ObjectMapper json;

    public OnboardHandler(ResumeRepository resumes, FileStorage storage, Renderer renderer, ObjectMapper json) {
        this.resumes = resumes;
        this.storage = storage;
        this.renderer = renderer;
        this.json = json;
    }

    @Override
    public JobType type() {
        return JobType.ONBOARD;
    }

    @Override
    public Map<String, Object> run(Job job, Context context) throws Exception {
        UUID userId = job.userId();
        UUID resumeId = UUID.fromString(job.payload().path("resumeId").asText());
        Resume resume = resumes.findForUser(resumeId, userId).orElse(null);
        if (resume == null) {
            // The account was deleted while the job waited: nothing to do, and nothing to store.
            return Map.of("resumeId", resumeId.toString(), "skipped", "resume no longer exists");
        }
        try {
            return onboard(job, context, resume);
        } catch (JobRejection e) {
            throw e;
        } catch (Exception e) {
            if (job.attempts() >= job.type().maxAttempts()) {
                resumes.markFailed(resumeId, userId); // this was the last attempt
            }
            throw e;
        }
    }

    private Map<String, Object> onboard(Job job, Context context, Resume resume) throws Exception {
        UUID userId = resume.userId();
        UUID resumeId = resume.id();
        resumes.markOnboarding(resumeId, userId);

        context.stage("gate");
        byte[] original = storage.get(resume.originalKey());

        Path out = Files.createTempDirectory("onboard-" + resumeId);
        try {
            context.stage("processing");
            OnboardReport report = new OnboardPipeline(renderer, FontMap.loadDefault()).run(original, out);
            JsonNode reportJson = REPORT_JSON.valueToTree(report);

            if (!report.accepted()) {
                String status = GateReason.NEEDS_USER.equals(report.reason()) ? Resume.NEEDS_USER : Resume.REJECTED;
                resumes.markRejected(resumeId, userId, status, reportJson);
                log.info("resume {} not accepted: {}", resumeId, report.reason());
                Map<String, Object> details = new java.util.LinkedHashMap<>();
                details.put("message", report.message());
                if (GateReason.NEEDS_USER.equals(report.reason())) {
                    // The engine offers no alternatives to choose from, so there are none to list.
                    details.put("options", List.of());
                }
                throw new JobRejection(report.reason(), details);
            }

            context.stage("storing");
            String normalizedKey = StorageKeys.normalized(userId, resumeId);
            String previewKey = StorageKeys.preview(userId, resumeId);
            String baselineKey = StorageKeys.baseline(userId, resumeId);
            // Overwrite is allowed for the derived files so that the one retry of a job can store them again.
            storage.put(normalizedKey, Files.readAllBytes(out.resolve("normalized.docx")),
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", true);
            storage.put(previewKey, Files.readAllBytes(out.resolve("preview.pdf")), "application/pdf", true);
            storage.put(baselineKey, Files.readAllBytes(out.resolve("baseline.json")), "application/json", true);

            BlocksReport blocks = BlocksAnalyzer.analyze(out.resolve("normalized.docx"));
            JsonNode blocksJson = BLOCKS_JSON.valueToTree(blocks);
            List<ResumeRepository.SectionRole> sections = new ArrayList<>();
            for (int i = 0; i < blocks.sections().size(); i++) {
                sections.add(new ResumeRepository.SectionRole("s" + i, blocks.sections().get(i).role(), null));
            }
            resumes.completeOnboarding(resumeId, userId, normalizedKey, previewKey, baselineKey, reportJson, blocksJson,
                    report.rendererVersion(), sections);
            log.info("resume {} onboarded: {} pages, {} editable slots", resumeId, report.pages(), report.editableCount());
            return Map.of("resumeId", resumeId.toString(), "status", Resume.READY);
        } finally {
            deleteTree(out);
        }
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
