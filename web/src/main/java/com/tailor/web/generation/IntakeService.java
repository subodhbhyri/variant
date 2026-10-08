package com.tailor.web.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tailor.engine.blocks.LinkValidator;
import com.tailor.engine.generate.Intake;
import com.tailor.engine.generate.IntakeFields;
import com.tailor.engine.generate.IntakeSection;
import com.tailor.engine.generate.IntakeValidator;
import com.tailor.engine.match.IntakeTemplateBuilder;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.web.api.ApiException;
import com.tailor.web.resumes.Resume;
import com.tailor.web.storage.FileStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * PHASE6_SPEC.md section 4.3, intake. The form is the engine's own template (one section per job
 * and per swappable project position, pre-filled from the resume) merged with what the user has
 * saved. Entry-time rules are the engine's {@link IntakeValidator} and {@link LinkValidator}; only
 * their error codes are renamed for the API ({@code RAW_TEXT_TOO_LONG} is {@code NOTES_TOO_LONG}).
 * Only sections the user has saved are generated.
 */
@Service
public class IntakeService {

    static final Set<String> MODES = Set.of("DETAILED", "EXISTING_ONLY", "SKIPPED");
    /** A hard ceiling beside the 1,500-word rule, so a few absurdly long "words" can't fill the database. */
    static final int MAX_NOTES_CHARS = 60_000;
    private static final Pattern ADDED_PROJECT = Pattern.compile("^project-new-(\\d+)$");
    private static final ObjectMapper ENGINE_JSON = new ObjectMapper();

    public record Link(String label, String url) {
    }

    public record Fields(String title, String detail, List<Link> links, String date) {
    }

    /** {@code saved}: whether the user has answered this section (only saved sections are generated). */
    public record SectionView(String id, String kind, String mode, Fields fields, String notes, boolean saved) {
    }

    public record Limits(int maxNotesWords, int maxAddedProjects) {
    }

    public record IntakeView(List<SectionView> sections, Limits limits) {
    }

    public record SaveRequest(String mode, Fields fields, String notes) {
    }

    private final com.tailor.web.resumes.ResumeRepository resumes;
    private final IntakeRepository intake;
    private final FileStorage storage;
    private final ObjectMapper json;
    private final Map<UUID, Intake> templates = new ConcurrentHashMap<>();

    public IntakeService(com.tailor.web.resumes.ResumeRepository resumes, IntakeRepository intake, FileStorage storage,
            ObjectMapper json) {
        this.resumes = resumes;
        this.intake = intake;
        this.storage = storage;
        this.json = json;
    }

    // ---- reads ---------------------------------------------------------------------------------

    public IntakeView view(UUID resumeId, UUID userId) {
        Resume resume = accepted(resumeId, userId);
        Intake template = template(resume);
        Map<String, IntakeRepository.Saved> saved = new LinkedHashMap<>();
        intake.list(resumeId, userId).forEach(s -> saved.put(s.sectionId(), s));

        List<SectionView> out = new ArrayList<>();
        for (IntakeSection t : template.sections()) {
            IntakeRepository.Saved s = saved.remove(t.id());
            out.add(s == null ? fromTemplate(t) : fromSaved(s));
        }
        saved.values().stream().filter(s -> ADDED_PROJECT.matcher(s.sectionId()).matches()).forEach(s -> out.add(fromSaved(s)));
        return new IntakeView(out, new Limits(IntakeValidator.MAX_RAW_WORDS, IntakeValidator.MAX_ADDED_PROJECTS));
    }

    /** What generation works from: the saved sections only, in form order, as the engine's own types. */
    public Intake savedIntake(UUID resumeId, UUID userId) {
        Resume resume = accepted(resumeId, userId);
        Intake template = template(resume);
        Map<String, IntakeRepository.Saved> saved = new LinkedHashMap<>();
        intake.list(resumeId, userId).forEach(s -> saved.put(s.sectionId(), s));
        List<IntakeSection> out = new ArrayList<>();
        for (IntakeSection t : template.sections()) {
            IntakeRepository.Saved s = saved.remove(t.id());
            if (s != null) {
                out.add(toEngine(s));
            }
        }
        saved.values().stream().filter(s -> ADDED_PROJECT.matcher(s.sectionId()).matches()).forEach(s -> out.add(toEngine(s)));
        return new Intake(out);
    }

    // ---- writes --------------------------------------------------------------------------------

    public SectionView save(UUID resumeId, UUID userId, String sectionId, SaveRequest request) {
        Resume resume = accepted(resumeId, userId);
        Intake template = template(resume);
        IntakeSection templateSection = template.sections().stream().filter(s -> s.id().equals(sectionId)).findFirst().orElse(null);
        IntakeRepository.Saved existing = intake.find(resumeId, userId, sectionId).orElse(null);
        boolean isAdded = ADDED_PROJECT.matcher(sectionId).matches();
        if (templateSection == null && !(isAdded && existing != null)) {
            throw ApiException.notFound();
        }
        String kind = templateSection != null ? templateSection.kind() : "project";
        IntakeRepository.Saved toSave = validated(kind, sectionId, templateSection, request, isAdded, addedCount(resumeId, userId, sectionId));
        intake.upsert(resumeId, userId, toSave);
        return fromSaved(toSave);
    }

    public SectionView addProject(UUID resumeId, UUID userId, SaveRequest request) {
        accepted(resumeId, userId);
        List<IntakeRepository.Saved> all = intake.list(resumeId, userId);
        int added = (int) all.stream().filter(s -> ADDED_PROJECT.matcher(s.sectionId()).matches()).count();
        int next = all.stream().map(s -> ADDED_PROJECT.matcher(s.sectionId())).filter(Matcher::matches)
                .mapToInt(m -> Integer.parseInt(m.group(1))).max().orElse(-1) + 1;
        String id = "project-new-" + next;
        IntakeRepository.Saved toSave = validated("project", id, null, request, true, added);
        intake.upsert(resumeId, userId, toSave);
        return fromSaved(toSave);
    }

    public void removeProject(UUID resumeId, UUID userId, String sectionId) {
        accepted(resumeId, userId);
        // Only added projects can be removed; the resume's own positions can be skipped instead.
        if (!ADDED_PROJECT.matcher(sectionId).matches() || !intake.delete(resumeId, userId, sectionId)) {
            throw ApiException.notFound();
        }
    }

    // ---- validation ----------------------------------------------------------------------------

    private IntakeRepository.Saved validated(String kind, String id, IntakeSection templateSection, SaveRequest request,
            boolean isAdded, int addedAlready) {
        if (request == null || request.mode() == null || !MODES.contains(request.mode())) {
            throw invalid("mode", "The mode must be DETAILED, EXISTING_ONLY or SKIPPED.");
        }
        if (isAdded && "EXISTING_ONLY".equals(request.mode())) {
            throw invalid("mode", "An added project has no current bullets; use DETAILED or SKIPPED.");
        }
        String notes = request.notes() == null ? "" : request.notes();
        if (notes.length() > MAX_NOTES_CHARS) {
            throw tooLong();
        }
        Fields fields = request.fields();
        if (templateSection != null && fields != null) {
            // Only the fields the resume's header template has are accepted.
            IntakeFields t = templateSection.fields();
            rejectIfAbsent(fields.title(), t == null ? null : t.title(), "title");
            rejectIfAbsent(fields.detail(), t == null ? null : t.detail(), "detail");
            rejectIfAbsent(fields.date(), t == null ? null : t.date(), "date");
            if (fields.links() != null && !fields.links().isEmpty() && (t == null || t.links() == null || t.links().isEmpty())) {
                throw invalid("fields.links", "This section has no links in its header.");
            }
        }
        IntakeSection engine = new IntakeSection(id, kind, request.mode(), toEngineFields(fields), notes);
        IntakeValidator.Result verdict = IntakeValidator.validate(engine, addedAlready);
        if (!verdict.accepted()) {
            throw switch (verdict.reason()) {
                case "RAW_TEXT_TOO_LONG" -> tooLong();
                case "TOO_MANY_PROJECTS" -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TOO_MANY_PROJECTS",
                        "You can add at most " + IntakeValidator.MAX_ADDED_PROJECTS + " projects.");
                case "INVALID_LINK" -> invalidLink(fields);
                default -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, verdict.reason(), verdict.message());
            };
        }
        return new IntakeRepository.Saved(id, kind, request.mode(), fieldsJson(fields), notes);
    }

    private static void rejectIfAbsent(String given, String template, String name) {
        if (given != null && template == null) {
            throw invalid("fields." + name, "This section's header has no " + name + ".");
        }
    }

    private int addedCount(UUID resumeId, UUID userId, String exceptId) {
        return (int) intake.list(resumeId, userId).stream()
                .filter(s -> ADDED_PROJECT.matcher(s.sectionId()).matches() && !s.sectionId().equals(exceptId)).count();
    }

    private static ApiException tooLong() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOTES_TOO_LONG",
                "Your notes are over " + IntakeValidator.MAX_RAW_WORDS + " words. Shorten them and try again.",
                Map.of("field", "notes"));
    }

    private static ApiException invalidLink(Fields fields) {
        int index = 0;
        if (fields != null && fields.links() != null) {
            for (int i = 0; i < fields.links().size(); i++) {
                if (!LinkValidator.isValid(fields.links().get(i).url())) {
                    index = i;
                    break;
                }
            }
        }
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_LINK",
                "That link isn't a valid web address.", Map.of("field", "fields.links[" + index + "].url"));
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, Map.of("field", field));
    }

    // ---- conversions ---------------------------------------------------------------------------

    private SectionView fromTemplate(IntakeSection t) {
        return new SectionView(t.id(), t.kind(), t.mode(), fromEngineFields(t.fields()), t.rawText() == null ? "" : t.rawText(), false);
    }

    private SectionView fromSaved(IntakeRepository.Saved s) {
        return new SectionView(s.sectionId(), s.kind(), s.mode(), fromEngineFields(engineFields(s.fields())), s.notes() == null ? "" : s.notes(), true);
    }

    private IntakeSection toEngine(IntakeRepository.Saved s) {
        return new IntakeSection(s.sectionId(), s.kind(), s.mode(), engineFields(s.fields()), s.notes());
    }

    private IntakeFields engineFields(JsonNode node) {
        if (node == null || node.isEmpty()) {
            return null;
        }
        try {
            return ENGINE_JSON.treeToValue(node, IntakeFields.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static IntakeFields toEngineFields(Fields f) {
        if (f == null) {
            return null;
        }
        List<IntakeFields.Link> links = f.links() == null ? null
                : f.links().stream().map(l -> new IntakeFields.Link(l.label(), l.url())).toList();
        return new IntakeFields(f.title(), f.detail(), links, f.date());
    }

    private static Fields fromEngineFields(IntakeFields f) {
        if (f == null) {
            return null;
        }
        List<Link> links = f.links() == null ? null : f.links().stream().map(l -> new Link(l.label(), l.url())).toList();
        return new Fields(f.title(), f.detail(), links, f.date());
    }

    private JsonNode fieldsJson(Fields f) {
        ObjectNode out = json.createObjectNode();
        if (f == null) {
            return out;
        }
        if (f.title() != null) {
            out.put("title", f.title());
        }
        if (f.detail() != null) {
            out.put("detail", f.detail());
        }
        if (f.date() != null) {
            out.put("date", f.date());
        }
        if (f.links() != null) {
            ArrayNode links = out.putArray("links");
            f.links().forEach(l -> links.addObject().put("label", l.label()).put("url", l.url()));
        }
        return out;
    }

    // ---- the engine's template -----------------------------------------------------------------

    private Resume accepted(UUID resumeId, UUID userId) {
        Resume resume = resumes.findForUser(resumeId, userId).orElseThrow(ApiException::notFound);
        if (!Resume.ACCEPTED.equals(resume.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "RESUME_NOT_ACCEPTED",
                    "Accept the resume's preview first.", Map.of("status", resume.status()));
        }
        return resume;
    }

    /** The pre-filled form for this resume: built once from its stored normalized document (which never changes). */
    private Intake template(Resume resume) {
        return templates.computeIfAbsent(resume.id(), id -> {
            try {
                Path dir = Files.createTempDirectory("intake-template");
                try {
                    Path docx = dir.resolve("normalized.docx");
                    Files.write(docx, storage.get(resume.normalizedKey()));
                    OnboardReport report = ENGINE_JSON.treeToValue(resume.onboardJson(), OnboardReport.class);
                    return IntakeTemplateBuilder.build(docx, report);
                } finally {
                    try (var walk = Files.walk(dir)) {
                        walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("could not build the intake form", e);
            }
        });
    }

    /** The report the engine's generation works from, read back from what onboarding stored. */
    public static OnboardReport reportOf(Resume resume) {
        try {
            return ENGINE_JSON.treeToValue(resume.onboardJson(), OnboardReport.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Ids of the sections in the form (template and added), for validating {@code sections} of a generate request. */
    public Set<String> savedIds(UUID resumeId, UUID userId) {
        return intake.list(resumeId, userId).stream().map(IntakeRepository.Saved::sectionId)
                .collect(java.util.stream.Collectors.toSet());
    }
}
