package com.tailor.web.edit;

import com.tailor.engine.blocks.Position;
import com.tailor.engine.generate.SectionPositions;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.slots.DocxBulletDetection;
import com.tailor.engine.slots.Slot;
import com.tailor.web.match.MatchRows.Snapshot;
import com.tailor.web.storage.FileStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The bullet slots of a snapshot's document (PHASE6_SPEC.md sections 4.4 and 6.1). Slot numbers are the
 * engine's bullet-detection indices, which a swapped document shares with the onboarded one: a swap keeps
 * every position's bullet slots, so slot {@code i} here is slot {@code i} of the onboarding report, with the
 * same line count, hint and lock reason. A slot is editable when the onboarding left it unlocked and it is a
 * job bullet or a project bullet (headers, stacks, links and other sections' bullets are not editable in v1).
 *
 * <p>A rendered snapshot never changes, so what is read from its document is cached.
 */
@Component
public class SnapshotSlots {

    /** {@code text} is what the slot holds in this snapshot; {@code lines} and {@code hintChars} come from onboarding. */
    public record SlotInfo(int slot, String kind, String position, String text, Integer lines, Integer hintChars,
            boolean editable, String lockReason) {
    }

    private static final int MAX_CACHED = 256;

    private final FileStorage storage;
    private final Map<UUID, List<SlotInfo>> cache = new ConcurrentHashMap<>();

    public SnapshotSlots(FileStorage storage) {
        this.storage = storage;
    }

    public List<SlotInfo> of(Snapshot snapshot, OnboardReport report) {
        if (snapshot.docxKey() == null) {
            return List.of();
        }
        List<SlotInfo> cached = cache.get(snapshot.id());
        if (cached != null) {
            return cached;
        }
        List<SlotInfo> computed = compute(snapshot, report);
        if (cache.size() >= MAX_CACHED) {
            cache.clear();
        }
        cache.put(snapshot.id(), computed);
        return computed;
    }

    private List<SlotInfo> compute(Snapshot snapshot, OnboardReport report) {
        try {
            Path dir = Files.createTempDirectory("slots-");
            try {
                Path docx = dir.resolve("snapshot.docx");
                Files.write(docx, storage.get(snapshot.docxKey()));
                return describe(docx, report);
            } finally {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not read the snapshot's slots", e);
        }
    }

    /** Slot facts for the document at {@code docx}, using {@code report} (the onboarding of the resume it came from). */
    public static List<SlotInfo> describe(Path docx, OnboardReport report) throws Exception {
        List<Slot> slots = DocxBulletDetection.detect(docx);
        SectionPositions positions = SectionPositions.detect(docx, report);
        Map<Integer, String[]> where = new HashMap<>();
        List<Position> jobs = positions.jobPositions();
        for (int i = 0; i < jobs.size(); i++) {
            for (int slotIndex : positions.bulletSlotIndices(jobs.get(i))) {
                where.put(slotIndex, new String[] {"job", "job-" + i});
            }
        }
        List<Position> projects = positions.projectPositions();
        for (int i = 0; i < projects.size(); i++) {
            for (int slotIndex : positions.bulletSlotIndices(projects.get(i))) {
                where.put(slotIndex, new String[] {"project", "P" + i});
            }
        }
        Map<Integer, OnboardReport.SlotReport> reported = new HashMap<>();
        report.slots().forEach(s -> reported.put(s.index(), s));

        List<SlotInfo> out = new ArrayList<>();
        for (Slot s : slots) {
            OnboardReport.SlotReport r = reported.get(s.index());
            String[] w = where.get(s.index());
            boolean unlocked = r != null && r.editable() && r.lines() != null;
            boolean inPosition = w != null;
            String lock = r == null ? "unknown" : r.lockReason();
            if (r != null && r.editable() && !inPosition) {
                lock = "not_job_or_project"; // an unlocked bullet elsewhere (education, awards...): not editable in v1
            }
            out.add(new SlotInfo(s.index(), w == null ? "other" : w[0], w == null ? null : w[1], s.text(),
                    r == null ? null : r.lines(), r == null ? null : r.hintChars(), unlocked && inPosition, lock));
        }
        return List.copyOf(out);
    }

    /** True for text that is only spaces and non-breaking spaces: a blanked slot reads this way. */
    public static boolean isBlank(String text) {
        return text == null || text.chars().allMatch(c -> Character.isWhitespace(c) || c == ' ');
    }
}
