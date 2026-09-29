# Phase 5 Spec — Job-Description Matching and Resume Selection

Written by Opus for the implementing model. Read it all before writing code.
Phases 1–4 still apply. Every rule below was run in `reference/jd_ref.py` on
the fixtures in `fixtures/phase5/`, which produced `expected_selection.json`.

---

## 0. Decisions (made; don't revisit)

| # | Decision |
|---|---|
| D1 | **No LLM call per job description.** Everything here is dictionary matching, the local MiniLM model, and the existing render/verify machinery. |
| D2 | Bullets may be **reordered within a job** (best first among slots of the same line count). Job order never changes. |
| D3 | The **best project goes in the top project position**, subject to Phase 3 shapes. |
| D4 | Required skills that nothing in the user's material mentions are **shown** ("missing skills"). Never added. |
| D5 | Suggested skill aliases are reviewed by the **operator** (`tailor aliases review`), never by users. Embeddings only suggest. |
| D6 | Weights and thresholds below are **starting values**, to be tuned on ~20 real job descriptions the operator collects (P5-T8). |
| D7 | **Deterministic**: same inputs → same output, byte for byte. Scores are rounded to 4 decimals; ties break by id. |

Only verified content reaches a resume: stored Phase 4 variants, library
projects, and the user's originals. Every assembled resume passes the
Verifier before it's shown.

---

## 1. Parsing a job description (step 5.1)

Reference: `parse_jd`. Input: plain text (from the extension or pasted),
capped at **20,000 characters** (longer → reject `JD_TOO_LONG`).

- **Title** = first non-empty line. A skill in the title counts as required.
- **Sections**: a line that looks like a heading (short, ends with `:`, or
  title-cased with no sentence punctuation) and names a section sets the weight
  for the lines after it:
  - required ×1.0: requirements, qualifications, must have, what you'll need, you have, minimum;
  - preferred ×0.5: nice to have, preferred, bonus, plus, good to have;
  - any other heading ×0.3 (responsibilities, about us, …); the text before the first heading is ×0.3.
- **Skills** are found with the Phase 4 dictionary matcher (`techs`); each skill's
  weight is the **highest** weight of any line it appears on.
- **Requirement text** = the lines under required and preferred headings,
  joined. It's what embeddings compare against.

Measured on the fixtures: the reworded platform posting ("Qualifications",
"Preferred") parses to exactly the same weighted skills as the original
("Requirements", "Nice to have").

### 1.1 Skills dictionary

Production dictionary: the Phase 4 seed **plus O*NET's Technology Skills
list** (free US Department of Labor data), about 1,500 canonical terms, each
with aliases. Stored as data, versioned. Fixtures use
`fixtures/phase5/skills_fixture.json` (the seed + 6 terms).

**Unknown terms**: tech-looking tokens not in the dictionary (CamelCase,
contain `.`/`+`/`#`/digits, or ALL-CAPS 2–6 letters) go to a review queue with
MiniLM's nearest dictionary term as a **suggestion** (section 7). They're
ignored for scoring until approved.

---

## 2. Scoring (step 5.3)

Reference: `score`.

```
score(text) = round(0.7 × keyword_coverage + 0.3 × similarity, 4)
keyword_coverage = Σ weight of JD skills the text names  /  Σ weight of all JD skills
similarity       = MiniLM cosine(text, requirement text), clipped to [0, 1]
```

**Project score** in a position = the mean score of its placed bullets, plus
0.3 × keyword coverage of its stack **only if that position's header shows a
stack** (`shows_detail`).

**Fixtures use a stand-in for MiniLM**: `fake_similarity` = cosine of
content-stem counts (Phase 4's `content_stems`). The Java engine has an
`Embedder` interface with two implementations, `FakeEmbedder` (exactly this,
for fixture tests) and `MiniLmEmbedder` (production). Fixture results must be
reproduced exactly with `FakeEmbedder`.

---

## 3. Assembling resume #1 (step 5.4)

Reference: `assemble`.

**Job slots** (`fill_job`): for each line count, in slot order, take the
highest-scoring stored variant of that length that is **not the same
achievement** as one already chosen; no candidate → keep the original bullet.

> Same achievement: two candidates whose content words overlap ≥ 50% in either
> direction (Phase 4 `grounding`). Phase 4 deliberately allows restating an
> achievement several ways; without this rule, resume #1 filled both of the
> fixture job's slots with the same Kubernetes migration in two wordings.
> Fixture groups: `{n1, n3}` and `{n2, n4}`.

**Project positions** (`assign_projects`): among all assignments of library
projects to the swappable positions (each project at most once, each fitting
the position's shape), choose the one that maximizes the **first position's
score, then the second's**, and so on (lexicographic, D3). Within a project,
choose which of its bullets fill the slots and in what order to maximize the
total (`place_project`). A weighted sum was tried first: with near-equal
weights it put the frontend posting's only frontend project second.

**Stack order**: stack items are ordered by the JD weight of the skill they
name (required first), original order otherwise; Phase 3's stack fit then
trims from the end.

Fixture results (`expected_selection.json`): Forge tops the platform resume,
Quill the frontend one, Relay the data one.

---

## 4. Alternatives #2 and #3 (step 5.5)

Reference: `top3`. **An alternative is the best resume that includes one item
#1 left out**: an unused library project, or an unused job achievement group,
with everything else re-optimized. Candidates are ranked by total score; the
top 2 are offered. Each is labelled with what it adds ("includes harbor
instead of relay"). If nothing was left out, fewer than 3 resumes are offered.

Two earlier rules were tried and rejected on the fixtures:
- a fixed penalty for reusing #1's items (0.5) swamped scores below 0.4 and put
  an irrelevant project first;
- a "differs in 25% of slots" rule counted rewordings of the same achievement as
  differences, and otherwise never triggered.

---

## 5. Rendering and verification (step 5.6)

Resume #1 is assembled with Phase 1 substitution (job slots, padding) and
Phase 3 swaps (positions, stack fit, date tabs) in one document, and must pass
the Verifier; anything else is `VERIFY_FAILED` and isn't shown. #2 and #3 are
assembled only when the user opens them. A resume whose assembly fails
verification is dropped from the offer and logged (never silently replaced).

---

## 6. Cache (step 5.7)

Reference: `cache_decision`. Fingerprint = the parsed weighted skill set +
title. Key = (user id, library version, fingerprint). The library version
changes on **any** edit to the user's material, so edits always invalidate.

- identical skill sets, or weighted Jaccard ≥ **0.9** → **HIT** (reuse the stored result);
- 0.8 ≤ Jaccard < 0.9 → HIT only if requirement-text similarity ≥ **0.95**;
- otherwise MISS.

Weighted Jaccard = Σ min(w) / Σ max(w) over the union of skills. Fixture:
platform vs its rewording → HIT (1.0); all other pairs MISS.

---

## 7. Embeddings (step 5.2)

`MiniLmEmbedder`: `all-MiniLM-L6-v2` exported to ONNX, run in-process with
ONNX Runtime's Java bindings, CPU only. Tokenizer from the model's
`tokenizer.json`; mean pooling over token embeddings with the attention mask,
then L2 normalization (the model's standard sentence embedding). Pin the model
file (store its SHA-256) and the runtime version; a mismatch at startup is an
error. Model files live outside git and the runtime image mounts them read-only.

Uses: (a) the similarity term in scoring; (b) the cache tiebreak; (c) alias
suggestions for unknown terms (nearest canonical term with cosine ≥ a
threshold set in P5-T7), queued for the operator, never applied automatically.

---

## 8. Missing skills (step 5.8)

Reference: `missing_skills`: required (×1.0) JD skills that no text in the
user's material names (stored variants, library bullets, stacks, titles).
Fixture: frontend → `["React"]`; data → `["Airflow", "Spark"]`; platform → none.

---

## 9. CLI and output

```
tailor match <onboarded.docx> <variants.json> <library.json> <jd.txt> <outDir> [--embedder fake|minilm]
tailor aliases review
```

`match` writes `match.json` (parsed JD, the 1–3 resumes with labels,
missing skills, cache decision) and `resume-1.pdf` (verified). `--embedder
fake` is for fixtures and tests.

---

## 10. Tests

| Id | Test | Pass condition |
|---|---|---|
| P5-T1 | Parsing | Each fixture JD's title and weighted skills equal `expected_selection.json`; the platform rewording parses identically to the original |
| P5-T2 | Scoring | `scores_job` for every JD equal the expected values exactly (FakeEmbedder) |
| P5-T3 | Assembly | Resume #1 for platform, frontend and data equals expected: job slots, positions, projects, chosen bullet indices, project scores |
| P5-T4 | Alternatives | #2 for each JD equals expected (label, job, projects); achievement groups equal expected; no resume ever has two candidates of one group |
| P5-T5 | Cache + missing | The four cache decisions and Jaccard values, and each JD's missing skills, equal expected |
| P5-T6 | Render | Resume #1 for each fixture JD assembled on `fixtures/phase3/projects_synthetic.docx` passes the Verifier (`corpusTest` tag) |
| P5-T7 | MiniLM (manual) | With the real model: report cosine for every pair in `alias_pairs.json`. Pass: every hard negative scores **below** the alias-suggestion threshold you set, and report how many aliases clear it. Also report embedding time per 100 bullets |
| P5-T8 | Tuning set (manual, operator) | The operator's ~20 real JDs run through `tailor match --embedder minilm`; report per JD the top skills, #1's projects and missing skills, for the operator to judge |
| P5-T9 | Determinism | Running P5-T3 twice gives byte-identical `match.json` |
| P5-T10 | No regressions | `test`, `corpusTest`, `corpus-check` ALL PASS |

---

## 11. Build order

1. **5.1** Parser and dictionary loading (P5-T1).
2. **5.3** Scoring with `Embedder` + `FakeEmbedder` (P5-T2).
3. **5.4–5.5** Assembly and alternatives (P5-T3, P5-T4, P5-T9).
4. **5.7–5.8** Cache and missing skills (P5-T5).
5. **5.6** Render and verify; `tailor match` (P5-T6).
6. **5.2** `MiniLmEmbedder`, pinned model, P5-T7 (needs the model files).

Stop for an **Opus review after P5-T7** with its pair scores and timings.

## 12. When to stop and escalate to Opus

- Any fixture result differs from `expected_selection.json`.
- A resume places two candidates of one achievement group, or a project in a
  shape it can't fill.
- In P5-T7 any hard negative scores above the alias threshold you'd need for
  the aliases.
- Anything that would change a weight, threshold or rule here.
