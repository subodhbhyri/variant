package com.tailor.engine.generate;

import com.tailor.engine.calibrate.BatchValidator;
import com.tailor.engine.slots.BulletText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHASE4_SPEC.md section 6 (step 4.5), revision 7: one model call per section, every variant
 * guarded (budget checked before render) and render-checked in batch. A variant that renders too
 * long, fails the guard or exceeds its budget is dropped for that length and never sent back; the
 * only retry is the empty-output one ("Return at least one candidate."). A candidate with no
 * surviving length is dropped; a section whose model returns nothing twice is {@code NO_OUTPUT}.
 */
public final class FitLoop {

    private static final double DUPLICATE_JACCARD = 0.8;
    private static final String RETURN_AT_LEAST_ONE = "Return at least one candidate.";
    private static final Pattern BULLET_ID = Pattern.compile("^b(\\d+)$");
    private static final Pattern LEADING_INT = Pattern.compile("(\\d+)");

    public record CandidateOutcome(
            String status, Map<String, String> variants, Integer acceptedInRound, String reason, Integer attempts) {
        static CandidateOutcome ok(Map<Integer, String> variantsByLength, int acceptedInRound) {
            Map<String, String> byLengthString = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> e : variantsByLength.entrySet()) {
                byLengthString.put(String.valueOf(e.getKey()), e.getValue());
            }
            return new CandidateOutcome("OK", byLengthString, acceptedInRound, null, null);
        }

        static CandidateOutcome dropped(String reason, Integer attempts) {
            return new CandidateOutcome("DROPPED", null, null, reason, attempts);
        }
    }

    /**
     * One (candidate, length) submission: the real text submitted, and its outcome — {@code FITS},
     * {@code TOO_SHORT}, {@code TOO_LONG}, {@code OVER_BUDGET} or a guard reason. {@code
     * renderedLines} is set only for the three render outcomes. ({@code REWRITTEN}, the retired
     * retry-comparison reason from revision 6, is no longer produced by any path.)
     */
    public record Attempt(String candidateId, int length, int round, String text, String outcome,
            Integer renderedLines) {
    }

    /** {@code status}: {@code GENERATED} (normal) or {@code NO_OUTPUT} (the model returned no
     * candidates on the initial call and on the one empty-output retry; the section stays locked). */
    public record FitLoopResult(
            String status,
            List<Integer> slotLineCounts,
            int rounds,
            Map<String, CandidateOutcome> finalResults,
            List<ModelResponse.Usage> callUsages,
            List<Attempt> attempts) {
    }

    private record RenderItem(String candidateId, int length, String text) {
    }

    /** One candidate's state. {@code reasonByLength} records why a length was dropped (the last
     * reason wins); {@code variants} holds what survives. */
    private static final class CandidateState {
        final Map<Integer, String> variants = new LinkedHashMap<>();
        final Map<Integer, String> reasonByLength = new LinkedHashMap<>();
    }

    private FitLoop() {
    }

    public static FitLoopResult run(SectionContext ctx, ModelClient client, SkillsDictionary skills)
            throws Exception {
        Set<Integer> neededLengths = ctx.slotIndicesByLineCount().keySet();
        List<Integer> requestedLengths = ctx.lineCounts().stream().filter(neededLengths::contains).toList();

        Map<String, CandidateState> candidates = new LinkedHashMap<>();
        List<ModelResponse.Usage> usages = new ArrayList<>();
        List<Attempt> attempts = new ArrayList<>();

        String initialUserMessage = PromptBuilder.userMessage(ctx.kind(), ctx.mode(), ctx.fields(),
                ctx.currentBullets(), ctx.rawText(),
                PromptBuilder.lengthSpecs(requestedLengths, ctx.budgetCharsByLineCount()), ctx.candidateCount(),
                ctx.slotLineCounts());
        ModelResponse response = client.call(PromptBuilder.SYSTEM_PROMPT, initialUserMessage);
        usages.add(response.usage());

        if (response.bullets().isEmpty()) {
            ModelResponse retryResponse = client.call(PromptBuilder.SYSTEM_PROMPT, RETURN_AT_LEAST_ONE);
            usages.add(retryResponse.usage());
            if (retryResponse.bullets().isEmpty()) {
                return new FitLoopResult(
                        "NO_OUTPUT", ctx.slotLineCounts(), usages.size(), Map.of(), usages, List.of());
            }
            response = retryResponse;
        }

        ingestAndCheck(ctx, skills, candidates, response, neededLengths, attempts);

        Map<String, CandidateOutcome> finalResults = new LinkedHashMap<>();
        for (Map.Entry<String, CandidateState> e : candidates.entrySet()) {
            CandidateState state = e.getValue();
            if (state.variants.isEmpty()) {
                finalResults.put(e.getKey(), CandidateOutcome.dropped(pickReason(state), 1));
            } else {
                finalResults.put(e.getKey(), CandidateOutcome.ok(state.variants, 1));
            }
        }

        dropDuplicates(finalResults);

        return new FitLoopResult("GENERATED", ctx.slotLineCounts(), 1, finalResults, usages, attempts);
    }

    /** Ingests the response's variants for the lengths some position needs, then guards and
     * render-checks each one exactly once. */
    private static void ingestAndCheck(SectionContext ctx, SkillsDictionary skills,
            Map<String, CandidateState> candidates, ModelResponse response, Set<Integer> neededLengths,
            List<Attempt> attempts) throws Exception {
        Map<String, Map<Integer, String>> submitted = new LinkedHashMap<>();
        for (ModelResponse.BulletCandidate bc : response.bullets()) {
            CandidateState state = candidates.computeIfAbsent(bc.id(), id -> new CandidateState());
            for (Map.Entry<String, String> e : bc.variants().entrySet()) {
                Integer length = parseLength(e.getKey());
                if (length == null || !neededLengths.contains(length)) {
                    continue; // no position needs this length: never kept, never checked
                }
                // EXISTING_ONLY (section 2 revision 3): a bullet may only be asked for lengths up to
                // its own line count — enforced server-side too, in case the model ignores the prompt.
                Integer ownMaxLength = perBulletMaxLength(ctx, bc.id());
                if (ownMaxLength != null && length > ownMaxLength) {
                    continue;
                }
                state.variants.put(length, e.getValue());
                submitted.computeIfAbsent(bc.id(), k -> new LinkedHashMap<>()).put(length, e.getValue());
            }
        }

        List<RenderItem> toRender = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, String>> cand : submitted.entrySet()) {
            String id = cand.getKey();
            CandidateState state = candidates.get(id);
            for (Map.Entry<Integer, String> v : cand.getValue().entrySet()) {
                int length = v.getKey();
                String text = v.getValue();
                Integer budget = ctx.budgetCharsByLineCount().get(length);
                List<String> guardReasons = TruthfulnessGuard.guard(text, ctx.sourceTexts(), budget, skills);
                if (!guardReasons.isEmpty()) {
                    drop(state, attempts, id, length, text, guardReasons.get(0), null);
                } else {
                    toRender.add(new RenderItem(id, length, text));
                }
            }
        }

        Map<Integer, List<RenderItem>> bySlot = distributeAcrossSlots(toRender, ctx.slotIndicesByLineCount());
        if (bySlot.isEmpty()) {
            return;
        }
        Map<Integer, Integer> lineCounts = new HashMap<>();
        Map<Integer, List<BulletText>> candidatesPerSlot = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<RenderItem>> se : bySlot.entrySet()) {
            int slotIndex = se.getKey();
            List<BulletText> texts = new ArrayList<>();
            for (RenderItem item : se.getValue()) {
                texts.add(new BulletText(item.text(), List.of()));
                lineCounts.put(slotIndex, item.length());
            }
            candidatesPerSlot.put(slotIndex, texts);
        }
        // What each candidate had submitted going into the render check, so that "does it already
        // have a k-line version" doesn't depend on the order BatchValidator returns results in.
        Map<String, Set<Integer>> requestedLengthsSnapshot = new HashMap<>();
        for (RenderItem item : toRender) {
            requestedLengthsSnapshot.computeIfAbsent(item.candidateId(), k -> new HashSet<>()).add(item.length());
        }

        List<BatchValidator.CandidateResult> results = BatchValidator.validate(
                ctx.baselineDocx(), ctx.renderer(), lineCounts, Map.of(), candidatesPerSlot,
                ctx.baselineDocx().getParent());

        for (BatchValidator.CandidateResult r : results) {
            RenderItem item = bySlot.get(r.slotIndex()).get(r.candidateIndex());
            CandidateState state = candidates.get(item.candidateId());
            switch (r.outcome()) {
                case FITS -> attempts.add(new Attempt(item.candidateId(), item.length(), 1, item.text(), "FITS",
                        r.measuredLines()));
                case FITS_WITH_PADDING -> {
                    // k < L: never retried ("never ask the model to lengthen anything" - section 4).
                    // Kept as its own real (shorter) length if the candidate has none there yet;
                    // otherwise this attempt is redundant and dropped.
                    int measured = r.measuredLines();
                    attempts.add(new Attempt(item.candidateId(), item.length(), 1, item.text(), "TOO_SHORT",
                            measured));
                    state.reasonByLength.put(item.length(), "TOO_SHORT");
                    Set<Integer> requested = requestedLengthsSnapshot.getOrDefault(item.candidateId(), Set.of());
                    String text = state.variants.remove(item.length());
                    if (!requested.contains(measured)) {
                        state.variants.put(measured, text);
                    }
                }
                default -> drop(state, attempts, item.candidateId(), item.length(), item.text(), "TOO_LONG",
                        r.measuredLines());
            }
        }

        checkSiblingConsistency(candidates);
    }

    /** A length that failed (guard, budget or render): removed from this candidate, recorded in
     * the attempts log with its real text. Never sent back for a retry. */
    private static void drop(CandidateState state, List<Attempt> attempts, String candidateId, int length,
            String text, String reason, Integer renderedLines) {
        state.variants.remove(length);
        state.reasonByLength.put(length, reason);
        attempts.add(new Attempt(candidateId, length, 1, text, reason, renderedLines));
    }

    /** PHASE4_SPEC.md section 5.1: for every pair of a candidate's kept lengths, the shorter
     * version must be grounded in the longer one; otherwise the longer version is dropped
     * ({@code INCONSISTENT_VARIANTS}). */
    private static void checkSiblingConsistency(Map<String, CandidateState> candidates) {
        for (CandidateState state : candidates.values()) {
            List<Integer> kept = new ArrayList<>(state.variants.keySet());
            Collections.sort(kept);
            for (int i = 0; i < kept.size(); i++) {
                for (int j = i + 1; j < kept.size(); j++) {
                    int shorterLen = kept.get(i);
                    int longerLen = kept.get(j);
                    String longerText = state.variants.get(longerLen);
                    if (longerText == null) {
                        continue; // already dropped by an earlier pair in this same pass
                    }
                    if (!TruthfulnessGuard.consistent(state.variants.get(shorterLen), longerText)) {
                        state.variants.remove(longerLen);
                        state.reasonByLength.put(longerLen, "INCONSISTENT_VARIANTS");
                    }
                }
            }
        }
    }

    /** EXISTING_ONLY only: candidate {@code b<i>}'s own line count (the prompt asks the model to
     * id EXISTING_ONLY candidates this way, matching {@code currentBullets}/{@code slotLineCounts}
     * order) — null if not EXISTING_ONLY, or the id doesn't follow that convention. */
    static Integer perBulletMaxLength(SectionContext ctx, String candidateId) {
        if (!"EXISTING_ONLY".equals(ctx.mode())) {
            return null;
        }
        Matcher m = BULLET_ID.matcher(candidateId);
        if (!m.matches()) {
            return null;
        }
        int idx = Integer.parseInt(m.group(1));
        List<Integer> slotLineCounts = ctx.slotLineCounts();
        return idx >= 0 && idx < slotLineCounts.size() ? slotLineCounts.get(idx) : null;
    }

    /** null if the key has no digits at all. The tool schema names bare digit keys; a leading
     * digit run is accepted so a cosmetic key mismatch doesn't fail the whole call. */
    private static Integer parseLength(String key) {
        Matcher m = LEADING_INT.matcher(key);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** The reason to report for a candidate with no surviving length: its lowest dropped length's
     * reason, or NO_VALID_LENGTH if nothing was ever recorded. */
    private static String pickReason(CandidateState state) {
        if (!state.reasonByLength.isEmpty()) {
            return state.reasonByLength.get(Collections.min(state.reasonByLength.keySet()));
        }
        return "NO_VALID_LENGTH";
    }

    private static Map<Integer, List<RenderItem>> distributeAcrossSlots(
            List<RenderItem> items, Map<Integer, List<Integer>> slotIndicesByLineCount) {
        Map<Integer, List<RenderItem>> bySlot = new LinkedHashMap<>();
        Map<Integer, Integer> cursorByLength = new HashMap<>();
        for (RenderItem item : items) {
            List<Integer> slots = slotIndicesByLineCount.get(item.length());
            if (slots == null || slots.isEmpty()) {
                continue;
            }
            int cursor = cursorByLength.getOrDefault(item.length(), 0);
            int slotIndex = slots.get(cursor % slots.size());
            cursorByLength.put(item.length(), cursor + 1);
            bySlot.computeIfAbsent(slotIndex, k -> new ArrayList<>()).add(item);
        }
        return bySlot;
    }

    /** PHASE4_SPEC.md section 6 point 4: a later OK candidate whose variant at any shared length
     * has word-level Jaccard >= 0.8 with an earlier OK candidate's variant at that length is
     * dropped as a duplicate. */
    private static void dropDuplicates(Map<String, CandidateOutcome> finalResults) {
        List<String> keptOrder = new ArrayList<>();
        for (Map.Entry<String, CandidateOutcome> e : finalResults.entrySet()) {
            if (!"OK".equals(e.getValue().status())) {
                continue;
            }
            String id = e.getKey();
            CandidateOutcome candidate = e.getValue();
            boolean duplicate = false;
            outer:
            for (String earlierId : keptOrder) {
                CandidateOutcome earlier = finalResults.get(earlierId);
                for (Map.Entry<String, String> v : candidate.variants().entrySet()) {
                    String earlierText = earlier.variants().get(v.getKey());
                    if (earlierText != null && jaccard(v.getValue(), earlierText) >= DUPLICATE_JACCARD) {
                        duplicate = true;
                        break outer;
                    }
                }
            }
            if (duplicate) {
                finalResults.put(id, CandidateOutcome.dropped("DUPLICATE", null));
            } else {
                keptOrder.add(id);
            }
        }
    }

    private static double jaccard(String a, String b) {
        Set<String> wa = new HashSet<>(Arrays.asList(a.toLowerCase().split("\\s+")));
        Set<String> wb = new HashSet<>(Arrays.asList(b.toLowerCase().split("\\s+")));
        Set<String> union = new HashSet<>(wa);
        union.addAll(wb);
        if (union.isEmpty()) {
            return 0.0;
        }
        Set<String> inter = new HashSet<>(wa);
        inter.retainAll(wb);
        return (double) inter.size() / union.size();
    }
}
