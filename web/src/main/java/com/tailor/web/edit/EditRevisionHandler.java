package com.tailor.web.edit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tailor.engine.calibrate.BatchValidator;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.docx.XmlSerialize;
import com.tailor.engine.edit.Blanker;
import com.tailor.engine.edit.Padder;
import com.tailor.engine.edit.Substituter;
import com.tailor.engine.edit.UnsupportedBulletException;
import com.tailor.engine.measure.StoredBaseline;
import com.tailor.engine.numbering.NumberingResolver;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.slots.BulletDetector;
import com.tailor.engine.slots.BulletText;
import com.tailor.engine.slots.Slot;
import com.tailor.engine.verify.SlotEdit;
import com.tailor.engine.verify.Verifier;
import com.tailor.web.generation.LibraryRepository;
import com.tailor.web.jobs.ConditionalOnJobHandlers;
import com.tailor.web.jobs.Job;
import com.tailor.web.jobs.JobHandler;
import com.tailor.web.jobs.JobRejection;
import com.tailor.web.jobs.JobType;
import com.tailor.web.match.MatchContextCache;
import com.tailor.web.match.MatchRepository;
import com.tailor.web.match.MatchRows.Match;
import com.tailor.web.match.MatchRows.Snapshot;
import com.tailor.web.resumes.Resume;
import com.tailor.web.resumes.ResumeRepository;
import com.tailor.web.storage.FileStorage;
import com.tailor.web.storage.StorageKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * The {@code edit_revision} job (PHASE6_SPEC.md sections 4.5 and 6). Two modes:
 * <ul>
 *   <li>{@code check}: one slot with new text on the parent's document, one render, the engine's verdict
 *       ({@code FITS}, {@code FITS_WITH_PADDING} or {@code TOO_LONG}). Nothing is stored.</li>
 *   <li>{@code revision}: all the edits applied to the parent's document, every one re-checked, then the whole
 *       document verified against the resume's stored onboarding baseline with the engine's {@link Verifier}. Only if all
 *       of that passes is a NEW snapshot created, with its files, linked to the parent. The parent is never
 *       touched. If anything fails nothing is stored: no row and no file.</li>
 * </ul>
 * Edits never reach the library or any other snapshot. Logs carry ids, slot numbers and codes, never text.
 */
@Component
@ConditionalOnJobHandlers
public class EditRevisionHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(EditRevisionHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final Pattern REGION = Pattern.compile("region (\\d+)");

    private final MatchRepository matches;
    private final ResumeRepository resumes;
    private final LibraryRepository libraries;
    private final MatchContextCache contexts;
    private final FileStorage storage;
    private final TransactionTemplate tx;
    private final Clock clock;

    public EditRevisionHandler(MatchRepository matches, ResumeRepository resumes, LibraryRepository libraries,
            MatchContextCache contexts, FileStorage storage, TransactionTemplate tx, Clock clock) {
        this.matches = matches;
        this.resumes = resumes;
        this.libraries = libraries;
        this.contexts = contexts;
        this.storage = storage;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public JobType type() {
        return JobType.EDIT_REVISION;
    }

    @Override
    public Map<String, Object> run(Job job, Context context) throws Exception {
        UUID userId = job.userId();
        UUID snapshotId = UUID.fromString(job.payload().path("snapshotId").asText());
        Snapshot parent = matches.findSnapshot(snapshotId, userId).orElse(null);
        if (parent == null) {
            return Map.of("skipped", "the snapshot no longer exists");
        }
        if (!Snapshot.RENDERED.equals(parent.status())) {
            throw new JobRejection("SNAPSHOT_NOT_RENDERED");
        }
        boolean check = "check".equals(job.payload().path("mode").asText());
        UUID revisionId = check ? null : UUID.fromString(job.payload().path("revisionId").asText());
        if (revisionId != null && matches.findSnapshot(revisionId, userId).isPresent()) { // delivered twice
            return Map.of("snapshotId", revisionId.toString(), "alreadyDone", true);
        }
        Match match = matches.findMatch(parent.matchId(), userId).orElseThrow();
        LibraryRepository.Library library = libraries.find(match.libraryId(), userId).orElseThrow();
        Resume resume = resumes.findForUser(library.resumeId(), userId).orElseThrow();

        Path work = Files.createTempDirectory("edit-" + snapshotId);
        try {
            return contexts.with(resume, library, (ctx, dir) -> {
                OnboardReport report = ctx.report();
                Path parentDocx = work.resolve("parent.docx");
                Files.write(parentDocx, storage.get(parent.docxKey()));
                if (check) {
                    context.stage("checking");
                    return check(job, ctx, report, parentDocx, work);
                }
                context.stage("applying");
                return revise(job, context, ctx, report, parent, revisionId, parentDocx, work, dir);
            });
        } finally {
            deleteTree(work);
        }
    }

    // ---- check ---------------------------------------------------------------------------------

    private Map<String, Object> check(Job job, com.tailor.engine.match.MatchRunner.Context ctx, OnboardReport report,
            Path parentDocx, Path work) throws Exception {
        int slot = job.payload().path("slot").asInt(-1);
        String text = job.payload().path("text").asText("");
        List<SnapshotSlots.SlotInfo> slots = SnapshotSlots.describe(parentDocx, report);
        SnapshotSlots.SlotInfo info = slot >= 0 && slot < slots.size() ? slots.get(slot) : null;
        if (info == null || !info.editable()) {
            throw new JobRejection("SLOT_LOCKED", Map.of("slot", slot));
        }
        if (text.isEmpty()) { // a blank keeps the slot's height by construction
            return result("FITS", info.lines(), info.lines(), "blank");
        }
        List<BatchValidator.CandidateResult> results = BatchValidator.validate(parentDocx, ctx.renderer(),
                lineCounts(report), hints(report), Map.of(slot, List.of(new BulletText(text, List.of()))), work);
        BatchValidator.CandidateResult r = results.get(0);
        return switch (r.outcome()) {
            case FITS -> result("FITS", r.measuredLines(), info.lines(), null);
            case FITS_WITH_PADDING -> result("FITS_WITH_PADDING", r.measuredLines(), info.lines(), null);
            case TOO_LONG, FAR_TOO_LONG, UNBREAKABLE_TOKEN ->
                    result("TOO_LONG", r.measuredLines(), info.lines(), r.outcome().name());
            case ANCHOR_FAILED -> throw new IllegalStateException("the slot could not be measured");
        };
    }

    private static Map<String, Object> result(String verdict, Integer measured, Integer target, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verdict", verdict);
        if (measured != null) {
            out.put("measured_lines", measured);
        }
        if (target != null) {
            out.put("target_lines", target);
        }
        if (reason != null) {
            out.put("reason", reason);
        }
        return out;
    }

    // ---- revision ------------------------------------------------------------------------------

    private record Edit(int slot, String text) {
    }

    private Map<String, Object> revise(Job job, Context context, com.tailor.engine.match.MatchRunner.Context ctx,
            OnboardReport report, Snapshot parent, UUID revisionId, Path parentDocx, Path work, Path contextDir)
            throws Exception {
        UUID userId = parent.userId();
        List<Edit> edits = new ArrayList<>();
        for (JsonNode e : job.payload().path("edits")) {
            edits.add(new Edit(e.path("slot").asInt(), e.path("text").asText("")));
        }
        List<SnapshotSlots.SlotInfo> info = SnapshotSlots.describe(parentDocx, report);
        for (Edit e : edits) {
            if (e.slot() < 0 || e.slot() >= info.size() || !info.get(e.slot()).editable()) {
                throw new JobRejection("SLOT_LOCKED", Map.of("slot", e.slot()));
            }
        }

        // 1. Re-check every edit now (a check made earlier may be stale): one render for all the non-blank ones.
        Map<Integer, List<BulletText>> candidates = new LinkedHashMap<>();
        for (Edit e : edits) {
            if (!e.text().isEmpty()) {
                candidates.put(e.slot(), List.of(new BulletText(e.text(), List.of())));
            }
        }
        Map<Integer, BatchValidator.CandidateResult> checked = new HashMap<>();
        if (!candidates.isEmpty()) {
            for (BatchValidator.CandidateResult r : BatchValidator.validate(parentDocx, ctx.renderer(), lineCounts(report),
                    hints(report), candidates, work)) {
                checked.put(r.slotIndex(), r);
            }
        }
        for (Edit e : edits) {
            BatchValidator.CandidateResult r = checked.get(e.slot());
            if (r == null) {
                continue; // a blank
            }
            if (r.outcome() == BatchValidator.Outcome.ANCHOR_FAILED) {
                throw new IllegalStateException("slot " + e.slot() + " could not be measured");
            }
            if (r.outcome() != BatchValidator.Outcome.FITS && r.outcome() != BatchValidator.Outcome.FITS_WITH_PADDING) {
                throw new JobRejection("TOO_LONG", Map.of("slot", e.slot(), "reason", r.outcome().name()));
            }
        }

        // 2. Apply the edits to the parent's document.
        context.stage("building");
        DocxPackage pkg = DocxPackage.open(parentDocx);
        Document doc = SafeXml.parse(pkg.readPart("word/document.xml"));
        List<Slot> slots = BulletDetector.detect(doc, resolver(pkg));
        List<Slot> pristine = null;
        Document pristineDoc = null;
        ArrayNode stored = JSON.createArrayNode();
        Map<Integer, SlotEdit> kinds = new LinkedHashMap<>();
        for (Edit e : edits) {
            Slot slot = slots.get(e.slot());
            int lines = info.get(e.slot()).lines();
            Element paragraph = slot.element();
            ObjectNode entry = stored.addObject().put("slot", e.slot()).put("text", e.text());
            try {
                if (e.text().isEmpty()) {
                    Blanker.blank(paragraph, lines);
                    kinds.put(e.slot(), SlotEdit.BLANKED);
                    entry.put("result", "BLANKED");
                    continue;
                }
                if (SnapshotSlots.isBlank(slot.text())) {
                    // The parent blanked this slot (its mark is painted white). Start again from the snapshot's
                    // pristine paragraph, which the root snapshot of this chain still has.
                    if (pristine == null) {
                        DocxPackage rootPkg = DocxPackage.open(rootDocx(parent, work));
                        pristineDoc = SafeXml.parse(rootPkg.readPart("word/document.xml"));
                        pristine = BulletDetector.detect(pristineDoc, resolver(rootPkg));
                    }
                    Element fresh = (Element) doc.importNode(pristine.get(e.slot()).element(), true);
                    paragraph.getParentNode().replaceChild(fresh, paragraph);
                    paragraph = fresh;
                }
                Substituter.substitute(paragraph, new BulletText(e.text(), List.of()));
                BatchValidator.CandidateResult r = checked.get(e.slot());
                if (r.outcome() == BatchValidator.Outcome.FITS_WITH_PADDING) {
                    Padder.pad(paragraph, lines - r.measuredLines());
                    kinds.put(e.slot(), SlotEdit.PADDED);
                    entry.put("result", "FITS_WITH_PADDING");
                } else {
                    kinds.put(e.slot(), SlotEdit.SUBSTITUTED);
                    entry.put("result", "FITS");
                }
            } catch (UnsupportedBulletException ex) {
                throw new JobRejection("SLOT_LOCKED", Map.of("slot", e.slot()));
            }
        }
        pkg.writePart("word/document.xml", XmlSerialize.toBytes(doc));
        Path revisedDocx = work.resolve("revised.docx");
        pkg.save(revisedDocx);

        // 3. Verify the whole document against the resume's stored ONBOARDING BASELINE (baseline.json, PHASE6_SPEC.md
        //    section 6), never against the parent or an earlier snapshot: same pages, every line the original had and
        //    this document did not change still where the original had it (0.5 pt), every changed bullet or header at
        //    its line count, fonts clean. Checking each revision against its parent would let drift add up; checking
        //    against the baseline keeps every revision within 0.5 pt of the original. Unverified output is never stored.
        context.stage("verifying");
        StoredBaseline baseline = StoredBaseline.readFrom(contextDir.resolve("baseline.json"));
        List<Verifier.Region> regions = BaselineRegions.build(contextDir.resolve("normalized.docx"), revisedDocx, report,
                baseline, ctx.renderer(), work);
        if (regions == null) {
            throw new JobRejection("VERIFY_FAILED", Map.of("reason", "the edit changed the structure of the resume"));
        }
        Verifier.Checked verified = Verifier.verifyRegionsAgainstBaseline(baseline, revisedDocx, ctx.renderer(),
                ctx.fontMap(), regions, work);
        if (!verified.report().ok()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", Verifier.describe(verified.report()));
            Matcher m = REGION.matcher(String.valueOf(details.get("reason")));
            if (m.find()) {
                details.put("slot", Integer.parseInt(m.group(1)));
            }
            throw new JobRejection("VERIFY_FAILED", details);
        }

        // 4. Only now is anything stored: the files, then the new snapshot row. The parent stays as it was.
        context.stage("storing");
        String docxKey = StorageKeys.snapshotDocx(userId, revisionId);
        String pdfKey = StorageKeys.snapshotPdf(userId, revisionId);
        storage.put(docxKey, Files.readAllBytes(revisedDocx), DOCX, false);
        storage.put(pdfKey, Files.readAllBytes(verified.assembledPdf()), "application/pdf", false);
        tx.executeWithoutResult(status -> matches.insertSnapshot(new Snapshot(revisionId, userId, parent.matchId(),
                parent.rank(), parent.label(), parent.assembly(), parent.id(), stored, docxKey, pdfKey, Snapshot.RENDERED,
                clock.instant())));
        log.info("revision {} of snapshot {} created with {} edit(s)", revisionId, parent.id(), edits.size());
        return Map.of("snapshotId", revisionId.toString());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private Path rootDocx(Snapshot snapshot, Path work) throws IOException {
        Snapshot root = snapshot;
        while (root.parentId() != null) {
            root = matches.findSnapshot(root.parentId(), root.userId()).orElseThrow();
        }
        Path file = work.resolve("root.docx");
        if (!Files.exists(file)) {
            Files.write(file, storage.get(root.docxKey()));
        }
        return file;
    }

    private static NumberingResolver resolver(DocxPackage pkg) throws IOException {
        Element numbering = pkg.hasPart("word/numbering.xml")
                ? SafeXml.parse(pkg.readPart("word/numbering.xml")).getDocumentElement() : null;
        Element styles = pkg.hasPart("word/styles.xml")
                ? SafeXml.parse(pkg.readPart("word/styles.xml")).getDocumentElement() : null;
        return new NumberingResolver(numbering, styles);
    }

    private static Map<Integer, Integer> lineCounts(OnboardReport report) {
        Map<Integer, Integer> out = new HashMap<>();
        report.slots().forEach(s -> {
            if (s.lines() != null) {
                out.put(s.index(), s.lines());
            }
        });
        return out;
    }

    private static Map<Integer, Integer> hints(OnboardReport report) {
        Map<Integer, Integer> out = new HashMap<>();
        report.slots().forEach(s -> {
            if (s.hintChars() != null) {
                out.put(s.index(), s.hintChars());
            }
        });
        return out;
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort
        }
    }
}
