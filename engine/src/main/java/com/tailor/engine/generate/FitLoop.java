package com.tailor.engine.generate;

import com.tailor.engine.calibrate.BatchValidator;
import com.tailor.engine.slots.BulletText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHASE4_SPEC.md section 6 (step 4.5): calls the model, guards every variant, render-checks the
 * guard-passing ones in batch (Phase 1 {@link BatchValidator}), retries only failing candidates
 * (at most 2 retries, 3 rounds total), drops any length still failing after round 3 from that
 * candidate (the candidate itself is dropped only if none of its lengths ever pass), retries once
 * on empty output before giving up with {@code NO_OUTPUT}, then drops near-duplicates
 * (word-level Jaccard >= 0.8 at a shared length).
 */
public final class FitLoop {

    private static final int MAX_ROUNDS = 3;
    private static final double DUPLICATE_JACCARD = 0.8;
    private static final String RETURN_AT_LEAST_ONE = "Return at least one candidate.";

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

    public record RoundOneFeedback(String reason, Integer targetLines, Integer measuredLines) {
    }

    /** {@code status}: {@code GENERATED} (normal) or {@code NO_OUTPUT} (section 6 point 3: the
     * model returned no candidates twice in a row; the section stays locked). */
    public record FitLoopResult(
            String status,
            List<Integer> slotLineCounts,
            int rounds,
            Map<String, CandidateOutcome> finalResults,
            Map<String, RoundOneFeedback> round1Feedback,
            List<ModelResponse.Usage> callUsages) {
    }

    private record RenderItem(String candidateId, int length, String text) {
    }

    /** One candidate's running state across rounds. */
    private static final class CandidateState {
        final Map<Integer, String> variants = new LinkedHashMap<>();
        final Set<Integer> failingLengths = new LinkedHashSet<>();
        String lastReason;
        Integer lastTargetLines;
        Integer lastMeasuredLines;
        int lastRoundTouched;
    }

    private FitLoop() {
    }

    public static FitLoopResult run(SectionContext ctx, ModelClient client, SkillsDictionary skills)
            throws Exception {
        Map<String, CandidateState> candidates = new LinkedHashMap<>();
        Map<String, RoundOneFeedback> round1Feedback = new LinkedHashMap<>();
        List<ModelResponse.Usage> usages = new ArrayList<>();

        String initialUserMessage = PromptBuilder.userMessage(ctx.kind(), ctx.mode(), ctx.fields(),
                ctx.currentBullets(), ctx.rawText(),
                PromptBuilder.lengthSpecs(ctx.lineCounts(), ctx.budgetCharsByLineCount()), ctx.candidateCount(),
                ctx.slotLineCounts());
        ModelResponse round1Response = client.call(PromptBuilder.SYSTEM_PROMPT, initialUserMessage);
        usages.add(round1Response.usage());

        if (round1Response.bullets().isEmpty()) {
            ModelResponse retryResponse = client.call(PromptBuilder.SYSTEM_PROMPT, RETURN_AT_LEAST_ONE);
            usages.add(retryResponse.usage());
            if (retryResponse.bullets().isEmpty()) {
                return new FitLoopResult(
                        "NO_OUTPUT", ctx.slotLineCounts(), usages.size(), Map.of(), Map.of(), usages);
            }
            round1Response = retryResponse;
        }

        ingestAndCheck(ctx, skills, candidates, round1Feedback, round1Response, 1);

        int round = 1;
        for (round = 2; round <= MAX_ROUNDS; round++) {
            List<PromptBuilder.RetryItem> retryItems = buildRetryItems(candidates);
            if (retryItems.isEmpty()) {
                round--; // everything already resolved; don't count an unneeded round
                break;
            }
            String userMessage = PromptBuilder.retryMessage(retryItems);
            ModelResponse response = client.call(PromptBuilder.SYSTEM_PROMPT, userMessage);
            usages.add(response.usage());
            ingestAndCheck(ctx, skills, candidates, round1Feedback, response, round);

            if (candidates.values().stream().noneMatch(s -> !s.failingLengths.isEmpty())) {
                break;
            }
        }
        int roundsRun = Math.min(round, MAX_ROUNDS);

        Map<String, CandidateOutcome> finalResults = new LinkedHashMap<>();
        for (Map.Entry<String, CandidateState> e : candidates.entrySet()) {
            String id = e.getKey();
            CandidateState state = e.getValue();
            // A length still failing after the last round is left out of this candidate rather
            // than killing the whole thing (PHASE4_SPEC.md section 2/6 revision 2: "a requested
            // length that's absent is simply not available for that candidate").
            for (int failingLength : new ArrayList<>(state.failingLengths)) {
                state.variants.remove(failingLength);
            }
            if (state.variants.isEmpty()) {
                String reason = state.lastReason != null ? state.lastReason : "NO_VALID_LENGTH";
                finalResults.put(id, CandidateOutcome.dropped(reason, roundsRun));
            } else {
                finalResults.put(id, CandidateOutcome.ok(state.variants, state.lastRoundTouched));
            }
        }

        dropDuplicates(finalResults);

        return new FitLoopResult(
                "GENERATED", ctx.slotLineCounts(), roundsRun, finalResults, round1Feedback, usages);
    }

    /** Ingests one round's response (assigning candidate ids/variants) and runs guard + batched
     * render check on every length just (re)submitted. */
    private static void ingestAndCheck(SectionContext ctx, SkillsDictionary skills,
            Map<String, CandidateState> candidates, Map<String, RoundOneFeedback> round1Feedback,
            ModelResponse response, int round) throws Exception {
        for (ModelResponse.BulletCandidate bc : response.bullets()) {
            CandidateState state = candidates.computeIfAbsent(bc.id(), id -> new CandidateState());
            state.lastRoundTouched = round;
            for (Map.Entry<String, String> e : bc.variants().entrySet()) {
                // The tool schema names bare digit keys ("1", "2", "3"); a real model call once
                // sent "1 line" instead, echoing the <lengths> block's own wording into the key.
                // Extract the leading digits rather than fail the whole call on a cosmetic key
                // mismatch, but this should not fire with the schema's keys now named explicitly.
                Integer length = parseLength(e.getKey());
                if (length == null) {
                    continue;
                }
                if (!e.getKey().equals(String.valueOf(length))) {
                    System.err.println("WARNING: variant key \"" + e.getKey()
                            + "\" did not match a named schema key; using leading digit " + length);
                }
                // EXISTING_ONLY (section 2 revision 3): a bullet may only be asked for lengths up
                // to its own line count — shortening is fine, lengthening would need facts the
                // candidate never gave. Enforced server-side too (not just via the prompt's
                // per-bullet listing), in case the model ignores the instruction.
                Integer ownMaxLength = perBulletMaxLength(ctx, bc.id());
                if (ownMaxLength != null && length > ownMaxLength) {
                    continue;
                }
                state.variants.put(length, e.getValue());
                state.failingLengths.add(length); // re-check every (re)submitted length below
            }
        }

        // Guard every length just (re)submitted this round; guard-passing ones go to the
        // batched render check.
        List<RenderItem> toRender = new ArrayList<>();
        for (Map.Entry<String, CandidateState> e : candidates.entrySet()) {
            String id = e.getKey();
            CandidateState state = e.getValue();
            if (state.lastRoundTouched != round) {
                continue; // not part of this round's response
            }
            for (int length : new ArrayList<>(state.failingLengths)) {
                String text = state.variants.get(length);
                // No budget here (unlike the prompt's <lengths> range and "about N characters"
                // retry feedback, which do use it): the batched render check right below is the
                // authoritative, more accurate fit test ("must really fill L lines" - section 6),
                // and double-gating on the calibration-hint budget too would reject some texts
                // the render alone would have passed, or report OVER_BUDGET for what render-checks
                // as TOO_LONG (PHASE4_SPEC.md's own job-0 fixture: c2's round-1 text is over the
                // 188-char budget for length 2 but is meant to fail with TOO_LONG, not OVER_BUDGET).
                List<String> guardReasons = TruthfulnessGuard.guard(text, ctx.sourceTexts(), null, skills);
                if (!guardReasons.isEmpty()) {
                    fail(state, guardReasons.get(0), null, null);
                    recordRoundOneFeedback(round1Feedback, round, id, guardReasons.get(0), null, null);
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
        List<BatchValidator.CandidateResult> results = BatchValidator.validate(
                ctx.baselineDocx(), ctx.renderer(), lineCounts, Map.of(), candidatesPerSlot,
                ctx.baselineDocx().getParent());

        for (BatchValidator.CandidateResult r : results) {
            RenderItem item = bySlot.get(r.slotIndex()).get(r.candidateIndex());
            CandidateState state = candidates.get(item.candidateId());
            switch (r.outcome()) {
                case FITS -> pass(state, item.length());
                case FITS_WITH_PADDING -> {
                    fail(state, "TOO_SHORT", item.length(), r.measuredLines());
                    recordRoundOneFeedback(round1Feedback, round, item.candidateId(), "TOO_SHORT",
                            item.length(), r.measuredLines());
                }
                default -> {
                    fail(state, "TOO_LONG", item.length(), r.measuredLines());
                    recordRoundOneFeedback(round1Feedback, round, item.candidateId(), "TOO_LONG",
                            item.length(), r.measuredLines());
                }
            }
        }
    }

    private static final Pattern BULLET_ID = Pattern.compile("^b(\\d+)$");

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

    private static final Pattern LEADING_INT = Pattern.compile("(\\d+)");

    /** null if the key has no digits at all (truly unparseable, not just oddly worded). */
    private static Integer parseLength(String key) {
        Matcher m = LEADING_INT.matcher(key);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static void pass(CandidateState state, int length) {
        state.failingLengths.remove(length);
    }

    private static void fail(CandidateState state, String reason, Integer targetLines, Integer measuredLines) {
        state.lastReason = reason;
        state.lastTargetLines = targetLines;
        state.lastMeasuredLines = measuredLines;
        // failingLengths already contains this length from the resubmission step above.
    }

    private static void recordRoundOneFeedback(Map<String, RoundOneFeedback> round1Feedback, int round, String id,
            String reason, Integer targetLines, Integer measuredLines) {
        if (round == 1 && !round1Feedback.containsKey(id)) {
            round1Feedback.put(id, new RoundOneFeedback(reason, targetLines, measuredLines));
        }
    }

    private static List<PromptBuilder.RetryItem> buildRetryItems(Map<String, CandidateState> candidates) {
        List<PromptBuilder.RetryItem> items = new ArrayList<>();
        for (Map.Entry<String, CandidateState> e : candidates.entrySet()) {
            CandidateState state = e.getValue();
            for (int length : state.failingLengths) {
                items.add(new PromptBuilder.RetryItem(e.getKey(), length,
                        feedbackText(state.lastReason, length, state.lastTargetLines, state.lastMeasuredLines,
                                state.variants.get(length))));
            }
        }
        return items;
    }

    private static String feedbackText(String reason, int length, Integer targetLines, Integer measuredLines,
            String text) {
        if ("TOO_LONG".equals(reason) && targetLines != null && measuredLines != null) {
            return "renders on " + measuredLines + " lines; it must fit in " + targetLines
                    + ". Shorten by about " + 5 + " characters.";
        }
        if ("TOO_SHORT".equals(reason) && targetLines != null && measuredLines != null) {
            return "renders on " + measuredLines + " lines; it must fill " + targetLines
                    + ". Lengthen it.";
        }
        if (reason != null && reason.startsWith("UNSUPPORTED_TECH:")) {
            return "mentions " + reason.substring("UNSUPPORTED_TECH:".length())
                    + ", which is not in the material. Remove it.";
        }
        if (reason != null && reason.startsWith("UNSUPPORTED_NUMBER:")) {
            return "states " + reason.substring("UNSUPPORTED_NUMBER:".length())
                    + ", which is not in the material. Remove or correct it.";
        }
        return switch (String.valueOf(reason)) {
            case "FIRST_PERSON" -> "uses first person; rewrite it without I/me/my/we/our/us.";
            case "URL" -> "contains a URL or email address; remove it.";
            case "MULTILINE" -> "contains a line break; write it as one line.";
            case "EMPTY" -> "was empty; write a bullet.";
            case "OVER_BUDGET" -> "is over the character budget; shorten it.";
            default -> "did not pass; revise it.";
        };
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
