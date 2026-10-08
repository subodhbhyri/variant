package com.tailor.web.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tailor.engine.blocks.LibraryProject;
import com.tailor.engine.generate.Intake;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.Renderer;
import com.tailor.web.jobs.ConditionalOnJobHandlers;
import com.tailor.web.jobs.Job;
import com.tailor.web.jobs.JobHandler;
import com.tailor.web.jobs.JobType;
import com.tailor.web.ledger.UsageLedger;
import com.tailor.web.resumes.Resume;
import com.tailor.web.resumes.ResumeRepository;
import com.tailor.web.storage.FileStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code generate} job (PHASE6_SPEC.md sections 5, 7 and 8). Runs the engine's generation for
 * the user's saved intake, stores the result as a NEW immutable library version, keeps the run's
 * report and cost, and writes the ledger row (once, keyed by the run id). Sections not regenerated
 * carry over unchanged. A run that already succeeded (a second delivery of the same job) does
 * nothing. Only this worker calls the Anthropic API; logs carry ids, counts and cost only.
 */
@Component
@ConditionalOnJobHandlers
public class GenerateHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(GenerateHandler.class);
    /** The generation report is written exactly as the CLI writes generation-report.json. */
    private static final ObjectMapper REPORT_JSON = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private final ResumeRepository resumes;
    private final LibraryRepository libraries;
    private final IntakeService intake;
    private final FileStorage storage;
    private final Renderer renderer;
    private final ModelClientFactory clients;
    private final UsageLedger ledger;
    private final TransactionTemplate tx;
    private final Clock clock;

    public GenerateHandler(ResumeRepository resumes, LibraryRepository libraries, IntakeService intake, FileStorage storage,
            Renderer renderer, ModelClientFactory clients, UsageLedger ledger, TransactionTemplate tx, Clock clock) {
        this.resumes = resumes;
        this.libraries = libraries;
        this.intake = intake;
        this.storage = storage;
        this.renderer = renderer;
        this.clients = clients;
        this.ledger = ledger;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public JobType type() {
        return JobType.GENERATE;
    }

    @Override
    public Map<String, Object> run(Job job, Context context) throws Exception {
        UUID userId = job.userId();
        UUID resumeId = UUID.fromString(job.payload().path("resumeId").asText());
        UUID runId = UUID.fromString(job.payload().path("runId").asText());
        LibraryRepository.Run run = libraries.findRun(runId, userId).orElse(null);
        Resume resume = resumes.findForUser(resumeId, userId).orElse(null);
        if (run == null || resume == null) {
            return Map.of("skipped", "the resume no longer exists");
        }
        if ("succeeded".equals(run.status())) {
            // Delivered twice: the library and the ledger row already exist.
            return Map.of("runId", runId.toString(), "libraryId", String.valueOf(run.libraryId()), "alreadyDone", true);
        }
        try {
            return generate(job, context, resume, runId);
        } catch (Exception e) {
            // A generate job is never retried automatically, so the first failure is final.
            libraries.failRun(runId, userId, clock.instant());
            throw e;
        }
    }

    private Map<String, Object> generate(Job job, Context context, Resume resume, UUID runId) throws Exception {
        UUID userId = resume.userId();
        libraries.markRunning(runId, userId);
        context.stage("preparing");
        Set<String> only = null;
        if (job.payload().path("sections").isArray()) {
            only = new HashSet<>();
            for (JsonNode s : job.payload().path("sections")) {
                only.add(s.asText());
            }
        }
        Intake saved = intake.savedIntake(resume.id(), userId);
        OnboardReport report = IntakeService.reportOf(resume);

        Path dir = Files.createTempDirectory("generate-" + runId);
        try {
            Path docx = dir.resolve("normalized.docx");
            Files.write(docx, storage.get(resume.normalizedKey()));
            GenerationRunner.Result result = new GenerationRunner(renderer, clients).run(docx, report, saved, only,
                    new GenerationRunner.Progress() {
                        @Override
                        public void step(String stage, int step, int of) {
                            context.step(stage, step, of);
                        }

                        @Override
                        public boolean cancelled() {
                            return context.cancelled();
                        }
                    });
            context.stage("storing");
            return store(resume, runId, only, result);
        } finally {
            deleteTree(dir);
        }
    }

    private Map<String, Object> store(Resume resume, UUID runId, Set<String> only, GenerationRunner.Result result) {
        UUID userId = resume.userId();
        return tx.execute(status -> {
            List<LibraryRepository.Item> items = new ArrayList<>();
            // Sections this run did not regenerate carry over exactly as they were.
            if (only != null) {
                libraries.latest(resume.id(), userId).ifPresent(previous -> libraries.items(previous.id(), userId).stream()
                        .filter(i -> !only.contains(i.sectionId())).forEach(items::add));
            }
            items.addAll(itemsOf(result));
            LibraryRepository.Library library = libraries.create(resume.id(), userId, items);
            JsonNode reportJson = REPORT_JSON.valueToTree(result.report());
            libraries.completeRun(runId, userId, library.id(), reportJson, result.totalUsd(), clock.instant());
            // Once per run, whatever happens to the job: the key is the run id.
            ledger.record(userId, UsageLedger.GENERATION, runId, runId.toString(), result.totalUsd());
            log.info("generation {} stored library version {}: {} variants, {} calls, ${}", runId, library.version(),
                    items.size(), result.calls(), String.format("%.4f", result.totalUsd()));
            return Map.of("runId", runId.toString(), "libraryId", library.id().toString(), "version", library.version(),
                    "costUsd", result.totalUsd());
        });
    }

    /** One library item per variant text: jobs by their candidate id, projects by bullet order (b0, b1, ...). */
    static List<LibraryRepository.Item> itemsOf(GenerationRunner.Result result) {
        List<LibraryRepository.Item> items = new ArrayList<>();
        result.jobVariants().forEach((sectionId, candidates) -> candidates.forEach((candidateId, outcome) -> {
            if (outcome.variants() != null) {
                outcome.variants().forEach((length, text) ->
                        items.add(new LibraryRepository.Item(sectionId, candidateId, Integer.parseInt(length), text, null, null)));
            }
        }));
        ObjectMapper plain = new ObjectMapper();
        for (LibraryProject project : result.projects()) {
            ObjectNode header = plain.createObjectNode();
            if (project.title() != null) {
                header.put("title", project.title());
            }
            if (project.detail() != null) {
                header.put("detail", project.detail());
            }
            if (project.date() != null) {
                header.put("date", project.date());
            }
            var links = header.putArray("links");
            if (project.links() != null) {
                project.links().forEach(l -> links.addObject().put("label", l.label()).put("url", l.url()));
            }
            for (int b = 0; b < project.bullets().size(); b++) {
                final String candidateId = "b" + b;
                project.bullets().get(b).forEach((length, text) -> items.add(new LibraryRepository.Item(
                        project.id(), candidateId, Integer.parseInt(length), text, project.homeSection(), header)));
            }
        }
        return items;
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort
        }
    }
}
