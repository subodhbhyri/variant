package com.tailor.web.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailor.engine.blocks.LibraryProject;
import com.tailor.engine.generate.FitLoop;
import com.tailor.web.api.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The library of generated material (PHASE6_SPEC.md sections 4.3 and 6). Every library version is
 * immutable; removing a variant writes the next version without it. The view joins the stored
 * variants with what the generation report said about each section: its status, what was
 * dropped and why, and a project's coverage.
 */
@Service
public class LibraryService {

    public record VariantView(String id, int length, String text) {
    }

    public record CandidateView(String id, List<VariantView> variants) {
    }

    public record DroppedView(String candidate, String reason, Integer attempts) {
    }

    public record SectionView(String id, String kind, String status, JsonNode header, String homeSection,
            List<CandidateView> candidates, List<DroppedView> dropped, JsonNode coverage) {
    }

    public record LibraryView(UUID libraryId, int version, Instant createdAt, List<SectionView> sections) {
    }

    private final LibraryRepository libraries;
    private final TransactionTemplate tx;

    public LibraryService(LibraryRepository libraries, TransactionTemplate tx) {
        this.libraries = libraries;
        this.tx = tx;
    }

    public LibraryView latest(UUID resumeId, UUID userId) {
        LibraryRepository.Library library = libraries.latest(resumeId, userId).orElseThrow(ApiException::notFound);
        return view(library, userId);
    }

    public LibraryView view(LibraryRepository.Library library, UUID userId) {
        List<LibraryRepository.Item> items = libraries.items(library.id(), userId);
        Map<String, JsonNode> reports = latestReports(library.resumeId(), userId, library.version());

        Map<String, List<LibraryRepository.Item>> bySection = new LinkedHashMap<>();
        for (LibraryRepository.Item item : items) {
            bySection.computeIfAbsent(item.sectionId(), k -> new ArrayList<>()).add(item);
        }
        // Sections the report knows about but that have no variants left still show, with their status.
        for (String id : reports.keySet()) {
            bySection.computeIfAbsent(id, k -> new ArrayList<>());
        }
        List<SectionView> sections = new ArrayList<>();
        bySection.keySet().stream().sorted(Comparator.comparing((String s) -> s.startsWith("job-") ? 0 : 1)
                .thenComparing(s -> s.length()).thenComparing(s -> s)).forEach(id -> {
            List<LibraryRepository.Item> own = bySection.get(id);
            JsonNode report = reports.get(id);
            sections.add(section(id, own, report));
        });
        return new LibraryView(library.id(), library.version(), library.createdAt(), sections);
    }

    private SectionView section(String id, List<LibraryRepository.Item> items, JsonNode report) {
        boolean project = !id.startsWith("job-"); // any other section id is a project (project-N, project-new-N)
        Map<String, TreeMap<Integer, String>> byCandidate = new LinkedHashMap<>();
        for (LibraryRepository.Item i : items) {
            byCandidate.computeIfAbsent(i.candidateId(), k -> new TreeMap<>()).put(i.length(), i.text());
        }
        List<CandidateView> candidates = new ArrayList<>();
        byCandidate.forEach((candidate, byLength) -> {
            List<VariantView> variants = new ArrayList<>();
            byLength.forEach((length, text) -> variants.add(new VariantView(variantId(id, candidate, length), length, text)));
            candidates.add(new CandidateView(candidate, variants));
        });
        List<DroppedView> dropped = new ArrayList<>();
        JsonNode outcomes = report == null ? null : report.path("candidates");
        if (outcomes != null && outcomes.isObject()) {
            outcomes.fields().forEachRemaining(e -> {
                if ("DROPPED".equals(e.getValue().path("status").asText())) {
                    dropped.add(new DroppedView(e.getKey(), e.getValue().path("reason").asText(null),
                            e.getValue().hasNonNull("attempts") ? e.getValue().path("attempts").asInt() : null));
                }
            });
        }
        JsonNode header = items.isEmpty() ? null : items.get(0).projectHeader();
        String home = items.isEmpty() ? null : items.get(0).homeSection();
        JsonNode coverage = report == null || !report.hasNonNull("coverage") ? null : report.get("coverage");
        String status = report == null ? "CARRIED_OVER" : report.path("status").asText("GENERATED");
        return new SectionView(id, project ? "project" : "job", status, header, home, candidates, dropped, coverage);
    }

    /** For each section, the report entry of the newest run (at or before this version) that generated it. */
    private Map<String, JsonNode> latestReports(UUID resumeId, UUID userId, int upToVersion) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (LibraryRepository.Run run : libraries.runs(resumeId, userId)) {
            if (run.report() == null || run.libraryId() == null) {
                continue;
            }
            int version = libraries.find(run.libraryId(), userId).map(LibraryRepository.Library::version).orElse(Integer.MAX_VALUE);
            if (version > upToVersion) {
                continue;
            }
            run.report().fields().forEachRemaining(e -> out.putIfAbsent(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** Project id to title, for turning ids in labels and reasons into names the user knows. */
    public Map<String, String> projectTitles(UUID libraryId, UUID userId) {
        Map<String, String> titles = new LinkedHashMap<>();
        for (LibraryRepository.Item item : libraries.items(libraryId, userId)) {
            if (!item.sectionId().startsWith("job-") && item.projectHeader() != null && item.projectHeader().hasNonNull("title")) {
                titles.putIfAbsent(item.sectionId(), item.projectHeader().get("title").asText());
            }
        }
        return titles;
    }

    public static String variantId(String sectionId, String candidateId, int length) {
        return sectionId + ":" + candidateId + ":" + length;
    }

    /**
     * The user removes a variant they don't stand behind. Writes the next library version without it;
     * the version they saw stays exactly as it was. Only the newest version can be changed, so a
     * stale page can't resurrect something removed since.
     */
    public LibraryView removeVariant(UUID libraryId, String variantId, UUID userId) {
        LibraryRepository.Library target = libraries.find(libraryId, userId).orElseThrow(ApiException::notFound);
        LibraryRepository.Library newest = libraries.latest(target.resumeId(), userId).orElseThrow(ApiException::notFound);
        if (newest.version() != target.version()) {
            throw new ApiException(HttpStatus.CONFLICT, "LIBRARY_STALE",
                    "The library has changed since you last looked at it. Reload it and try again.",
                    Map.of("latest_library_id", newest.id().toString()));
        }
        List<LibraryRepository.Item> items = libraries.items(target.id(), userId);
        List<LibraryRepository.Item> kept = items.stream()
                .filter(i -> !variantId(i.sectionId(), i.candidateId(), i.length()).equals(variantId)).toList();
        if (kept.size() == items.size()) {
            throw ApiException.notFound();
        }
        LibraryRepository.Library created = tx.execute(status -> libraries.create(target.resumeId(), userId, kept));
        return view(created, userId);
    }

    /** The library's two halves in the engine's file formats, for the match step. */
    public EngineLibrary engineLibrary(UUID libraryId, UUID userId) {
        List<LibraryRepository.Item> items = libraries.items(libraryId, userId);
        LibraryRepository.Library library = libraries.find(libraryId, userId).orElseThrow(ApiException::notFound);
        Map<String, Map<String, FitLoop.CandidateOutcome>> jobs = new LinkedHashMap<>();
        Map<String, Map<String, TreeMap<Integer, String>>> projectBullets = new LinkedHashMap<>();
        Map<String, LibraryRepository.Item> projectFirst = new LinkedHashMap<>();
        for (LibraryRepository.Item i : items) {
            if (i.sectionId().startsWith("job-")) {
                Map<String, FitLoop.CandidateOutcome> candidates = jobs.computeIfAbsent(i.sectionId(), k -> new LinkedHashMap<>());
                FitLoop.CandidateOutcome existing = candidates.get(i.candidateId());
                Map<String, String> variants = existing == null ? new LinkedHashMap<>() : existing.variants();
                variants.put(String.valueOf(i.length()), i.text());
                candidates.put(i.candidateId(), new FitLoop.CandidateOutcome("OK", variants, null, null, null));
            } else {
                projectBullets.computeIfAbsent(i.sectionId(), k -> new TreeMap<>(Comparator.comparingInt(LibraryService::bulletNumber)
                        .thenComparing(s -> s))).computeIfAbsent(i.candidateId(), k -> new TreeMap<>()).put(i.length(), i.text());
                projectFirst.putIfAbsent(i.sectionId(), i);
            }
        }
        // A job section that was generated but kept nothing is still listed (with no candidates), as the
        // CLI's variants.json lists it; a section that was skipped or cut off by the cost limit is not.
        latestReports(library.resumeId(), userId, library.version()).forEach((id, report) -> {
            if (id.startsWith("job-") && report.has("candidates") && !report.path("candidates").isNull()) {
                jobs.computeIfAbsent(id, k -> new LinkedHashMap<>());
            }
        });
        List<LibraryProject> projects = new ArrayList<>();
        projectBullets.forEach((id, bullets) -> {
            LibraryRepository.Item first = projectFirst.get(id);
            JsonNode h = first.projectHeader();
            List<LibraryProject.Link> links = new ArrayList<>();
            if (h != null && h.path("links").isArray()) {
                h.path("links").forEach(l -> links.add(new LibraryProject.Link(l.path("label").asText(null), l.path("url").asText(null))));
            }
            List<Map<String, String>> bulletList = new ArrayList<>();
            bullets.values().forEach(byLength -> {
                Map<String, String> m = new LinkedHashMap<>();
                byLength.forEach((len, text) -> m.put(String.valueOf(len), text));
                bulletList.add(m);
            });
            projects.add(new LibraryProject(id, text(h, "title"), text(h, "detail"), links, text(h, "date"), bulletList,
                    first.homeSection()));
        });
        return new EngineLibrary(jobs, new LibraryProject.Library(projects));
    }

    private static String text(JsonNode h, String field) {
        return h == null || !h.hasNonNull(field) ? null : h.get(field).asText();
    }

    private static int bulletNumber(String candidateId) {
        try {
            return Integer.parseInt(candidateId.substring(1));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** {@code variants.json}'s job half and the project library, as the match step reads them. */
    public record EngineLibrary(Map<String, Map<String, FitLoop.CandidateOutcome>> jobs, LibraryProject.Library projects) {
    }
}
