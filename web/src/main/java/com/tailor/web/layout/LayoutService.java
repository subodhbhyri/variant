package com.tailor.web.layout;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailor.engine.blocks.HeaderParser;
import com.tailor.engine.blocks.Position;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.generate.SectionPositions;
import com.tailor.engine.layout.PageLayout;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.web.api.ApiException;
import com.tailor.web.edit.SnapshotSlots;
import com.tailor.web.generation.IntakeService;
import com.tailor.web.generation.LibraryRepository;
import com.tailor.web.generation.LibraryService;
import com.tailor.web.match.MatchRepository;
import com.tailor.web.match.MatchRows.Match;
import com.tailor.web.match.MatchRows.Snapshot;
import com.tailor.web.resumes.Resume;
import com.tailor.web.resumes.ResumeRepository;
import com.tailor.web.storage.FileStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * PHASE6_SPEC.md revision 3, the page layout for the UI: pages (size in PDF points), every bullet slot and every job and
 * project position with the page and box of each of its lines, whether it can be edited and why not; and, for a snapshot,
 * what changed from the onboarded resume. The boxes are the engine's own measurement of the stored PDF
 * ({@link PageLayout}); nothing is estimated here. A stored snapshot never changes, so what is computed for one is kept.
 */
@Service
public class LayoutService {

    private static final int MAX_CACHED = 128;

    /** One bullet: where it is, and the web's view of whether it can be edited. {@code kind} is job, project or other. */
    public record SlotLayout(int slot, String kind, String position, boolean editable, String lockReason, Integer lineCount,
            List<PageLayout.Box> lines) {
    }

    /**
     * What changed from the onboarded resume. {@code type} is {@code bullet_rewritten} (slot, old, new, lines, wordsAdded,
     * and the position it is in), {@code project_placed} (position, project, previous: the title that position had) or
     * {@code stack_trimmed} (position, kept and dropped stack items, in the order the match ranked them).
     */
    public record Change(String type, Integer slot, String position, String project, String previous, String oldText,
            String newText, Integer lines, Integer wordsAdded, List<String> kept, List<String> dropped) {
    }

    public record LayoutView(List<PdfLines.PageSize> pages, List<SlotLayout> slots, List<PageLayout.PositionLayout> positions,
            List<Change> changes) {
    }

    private final MatchRepository matches;
    private final LibraryRepository libraries;
    private final LibraryService libraryService;
    private final ResumeRepository resumes;
    private final FileStorage storage;
    private final Map<UUID, LayoutView> cache = new ConcurrentHashMap<>();

    public LayoutService(MatchRepository matches, LibraryRepository libraries, LibraryService libraryService,
            ResumeRepository resumes, FileStorage storage) {
        this.matches = matches;
        this.libraries = libraries;
        this.libraryService = libraryService;
        this.resumes = resumes;
        this.storage = storage;
    }

    /** A rendered snapshot's layout and changes. Another user's snapshot is a 404; a stored one that is not rendered, a 409. */
    public LayoutView forSnapshot(UUID snapshotId, UUID userId) {
        Snapshot s = matches.findSnapshot(snapshotId, userId).orElseThrow(ApiException::notFound); // owner first
        if (!Snapshot.RENDERED.equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "SNAPSHOT_NOT_RENDERED", "This resume hasn't been rendered yet.",
                    Map.of("status", s.status()));
        }
        LayoutView cached = cache.get(s.id());
        if (cached != null) {
            return cached;
        }
        Match match = matches.findMatch(s.matchId(), userId).orElseThrow(ApiException::notFound);
        LibraryRepository.Library library = libraries.find(match.libraryId(), userId).orElseThrow(ApiException::notFound);
        Resume resume = resumes.findForUser(library.resumeId(), userId).orElseThrow(ApiException::notFound);
        OnboardReport report = IntakeService.reportOf(resume);
        Map<String, String> titles = libraryService.projectTitles(match.libraryId(), userId);
        LayoutView view = compute(s.docxKey(), s.pdfKey(), report, resume, s.assembly(), titles, s, true);
        remember(s.id(), view);
        return view;
    }

    /** An onboarded resume's layout (its normalized document and preview), without changes. */
    public LayoutView forResume(UUID resumeId, UUID userId) {
        Resume resume = resumes.findForUser(resumeId, userId).orElseThrow(ApiException::notFound);
        if (resume.normalizedKey() == null || resume.previewKey() == null || resume.onboardJson() == null
                || !resume.onboardJson().path("accepted").asBoolean(false)) {
            throw new ApiException(HttpStatus.CONFLICT, "RESUME_NOT_READY", "The preview isn't ready yet.",
                    Map.of("status", resume.status()));
        }
        // Not kept: a resume's roles can still change, and with them its positions.
        return compute(resume.normalizedKey(), resume.previewKey(), IntakeService.reportOf(resume), resume, null,
                Map.of(), null, false);
    }

    private void remember(UUID id, LayoutView view) {
        if (cache.size() >= MAX_CACHED) {
            cache.clear();
        }
        cache.put(id, view);
    }

    private LayoutView compute(String docxKey, String pdfKey, OnboardReport report, Resume resume, JsonNode assembly,
            Map<String, String> titles, Snapshot snapshot, boolean withChanges) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("layout-");
            Path docx = dir.resolve("document.docx");
            Path pdf = dir.resolve("document.pdf");
            Files.write(docx, storage.get(docxKey));
            Files.write(pdf, storage.get(pdfKey));
            PageLayout.Layout measured = PageLayout.build(docx, pdf, report);
            List<SnapshotSlots.SlotInfo> infos = SnapshotSlots.describe(docx, report);

            List<SlotLayout> slots = new ArrayList<>();
            for (PageLayout.SlotLayout geometry : measured.slots()) {
                SnapshotSlots.SlotInfo info = infos.get(geometry.slot());
                slots.add(new SlotLayout(geometry.slot(), info.kind(), info.position(), info.editable(),
                        info.editable() ? null : info.lockReason(), geometry.lineCount(), geometry.lines()));
            }
            List<Change> changes = null;
            if (withChanges) {
                changes = changes(docx, report, resume, assembly, titles, infos, measured);
            }
            return new LayoutView(measured.pages(), List.copyOf(slots), measured.positions(), changes);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("could not measure the layout", e);
        } finally {
            if (dir != null) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (IOException ignored) {
                    // a temp directory
                }
            }
        }
    }

    // ---- what changed ------------------------------------------------------------------------

    private List<Change> changes(Path snapshotDocx, OnboardReport report, Resume resume, JsonNode assembly,
            Map<String, String> titles, List<SnapshotSlots.SlotInfo> infos, PageLayout.Layout measured) throws Exception {
        List<Change> out = new ArrayList<>();

        // Bullets whose text is not the onboarded resume's.
        Map<Integer, OnboardReport.SlotReport> original = new HashMap<>();
        report.slots().forEach(r -> original.put(r.index(), r));
        for (SnapshotSlots.SlotInfo info : infos) {
            OnboardReport.SlotReport before = original.get(info.slot());
            if (before == null || "other".equals(info.kind())) {
                continue;
            }
            String now = SnapshotSlots.isBlank(info.text()) ? "" : info.text();
            if (!now.equals(before.text())) {
                out.add(new Change("bullet_rewritten", info.slot(), info.position(), null, null, before.text(), now,
                        measured.slots().get(info.slot()).lineCount(), wordsAdded(before.text(), now), null, null));
            }
        }

        if (assembly == null || !assembly.path("projects").isArray() || assembly.path("projects").isEmpty()) {
            return out;
        }
        Path normalized = Files.createTempFile("layout-original-", ".docx");
        try {
            Files.write(normalized, storage.get(resume.normalizedKey()));
            List<Position> before = SectionPositions.detect(normalized, report).projectPositions();
            List<Position> after = SectionPositions.detect(snapshotDocx, report).projectPositions();
            for (JsonNode p : assembly.path("projects")) {
                String position = p.path("position").asText(null);
                Integer index = positionIndex(position);
                if (index == null || index >= before.size() || index >= after.size()) {
                    continue;
                }
                String projectId = p.path("project").asText(null);
                String title = titles.getOrDefault(projectId, projectId);
                out.add(new Change("project_placed", null, position, title, headerTitle(before.get(index)), null, null, null,
                        null, null, null));

                List<String> ranked = new ArrayList<>();
                p.path("stack").forEach(item -> ranked.add(item.asText()));
                String detail = headerDetail(after.get(index));
                if (!ranked.isEmpty() && detail != null) {
                    List<String> kept = new ArrayList<>();
                    List<String> dropped = new ArrayList<>();
                    for (String item : ranked) {
                        (detailHas(detail, item) ? kept : dropped).add(item);
                    }
                    if (!dropped.isEmpty()) {
                        out.add(new Change("stack_trimmed", null, position, title, null, null, null, null, null, kept,
                                dropped));
                    }
                }
            }
        } finally {
            Files.deleteIfExists(normalized);
        }
        return out;
    }

    private static Integer positionIndex(String position) {
        if (position == null || !position.matches("P\\d+")) {
            return null;
        }
        return Integer.parseInt(position.substring(1));
    }

    /** The title in a project position's header, as the engine parses it (an inline position's header segment). */
    private static String headerTitle(Position p) {
        if (p.block().headerParas.isEmpty()) {
            String segment = p.block().inlineHeaderSegment;
            return segment == null || segment.isBlank() ? null : segment.strip();
        }
        HeaderParser.Result parsed = HeaderParser.parse(p.block().headerParas.get(0));
        return parsed.parsed() == null ? DomUtil.allText(p.block().headerParas.get(0)).strip() : parsed.parsed().title();
    }

    private static String headerDetail(Position p) {
        if (p.block().headerParas.isEmpty()) {
            return null;
        }
        HeaderParser.Result parsed = HeaderParser.parse(p.block().headerParas.get(0));
        return parsed.parsed() == null ? null : parsed.parsed().detail();
    }

    private static boolean detailHas(String detail, String item) {
        for (String part : detail.split(",\\s*")) {
            if (part.strip().equalsIgnoreCase(item.strip())) {
                return true;
            }
        }
        return false;
    }

    /** How many words of {@code now} are not in {@code before} (a word is counted once for each time it was added). */
    static int wordsAdded(String before, String now) {
        Map<String, Integer> available = new HashMap<>();
        for (String w : words(before)) {
            available.merge(w, 1, Integer::sum);
        }
        int added = 0;
        for (String w : words(now)) {
            Integer left = available.get(w);
            if (left != null && left > 0) {
                available.put(w, left - 1);
            } else {
                added++;
            }
        }
        return added;
    }

    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("\\s+")) {
            String stripped = w.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
            if (!stripped.isEmpty()) {
                out.add(stripped);
            }
        }
        return out;
    }
}
