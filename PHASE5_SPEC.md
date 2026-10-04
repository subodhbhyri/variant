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

Reference: `parse_jd` (= `parse_jd_v3`). Input: plain text (from the extension or
pasted), capped at **20,000 characters** (longer → reject `JD_TOO_LONG`).
Revision 3 was built on **20 real postings**; revision 1 got 12 of their titles
wrong and inverted required/preferred on some. Their patterns are captured as
synthetic cases in `fixtures/phase5/jd_patterns/` (the real postings stay
private).

**Title** (`extract_title`), first rule that applies:
1. A label line: `Job Title:`, `Role -`, `Position:`, `Title:` followed by a role.
2. One of the first 3 lines that names a role (engineer, developer, SDET,
   programmer, architect, scientist, analyst, tester, assistant, as a **whole
   word**: "Engineering" is not "Engineer"), is at most 10 words, doesn't end
   like a sentence, and isn't itself a "seeking a…" phrase. Boilerplate lines
   are skipped: "About…", "Job Description", "Description", "Overview",
   "General Information", "Company:", "Summary", "Apply", "Job Details".
3. A role at the end of one of the first 8 lines after `>`, `:` or `,`
   ("Job Area: … > IT Software Developer").
4. A phrase "as a / seeking (a) / hiring (a) / looking for (a) … <role>",
   preferring multi-word roles ("backend engineers" → "Backend engineer") over a
   bare "engineer".
5. Otherwise **no title** (empty). Never a guessed line: 2 of the 20 real
   postings have no title, and a guessed line put skills into the title.

A skill named in the title counts as required.

**Sections.** A heading is a short line (≤ 70 characters, ≤ 6 words or ending in
`:` or all caps), not ending in sentence punctuation, and **not** a `label:
value` line ("Education: Bachelor's preferred" is content). Its kind, first
match wins:

| Kind | Weight | Heading words |
|---|---|---|
| ignore | 0 (skills not counted) | benefits, perks, salary, compensation, pay range/scale, equal opportunity, EEO, privacy, disclosures, physical, work environment, interview process, "About <company>" (not "About the role/you/the job"), our purpose/virtues/stands, who thrives, why work/join, E-Verify, accommodation |
| preferred | 0.5 | nice to have, preferred, bonus, desired/desirable, additional skills, good to have, a plus |
| required | 1.0 | requirements, required, qualifications, must have, minimum, what you'll need, what you bring/have, who you are, about you, what we're looking for, skills (but see below), knowledge, education and experience, you have |
| duties | 0.5 | responsibilities, what you'll do, what you will do/work on, duties, essential functions, the opportunity, the role, role description, your role, how we work, outcomes, day to day |
| other | 0.3 | any other heading ending in `:`, and text before the first heading |

A bare "Skills" or "Job Details" heading is a job-board tag list, so "other".
A parenthesised heading like "(Required)" counts. Inside a **required**
section, a line containing preferred, a plus, bonus, nice to have, desired,
desirable or "not required" weighs 0.5.

**Requirement text** = lines weighted ≥ 0.5, joined.

### 1.1 Skills dictionary

**Version 2** (`reference/data/skills_dictionary_v2.json`, bundled as the jar
resource, replacing the 74-term seed): 182 canonical terms, 267 aliases,
curated and checked against the 20 real postings. The seed missed Playwright,
Cypress, Selenium, Pytest, Hibernate, JPA, Maven, Gradle, Oracle, Snowflake,
Android, Jira, Copilot, microservices, HTML, CSS, Tomcat and Redux. The O*NET
import (about 1,500 terms) remains the next step, with this file as its seed.

**Case-sensitive aliases**: `_case_sensitive` lists aliases that double as
ordinary English (React, Swift, Spring, Go, Express, Node, Lambda…); they match
exact case only, so "react quickly to swift changes in spring" names no skill.
Aliases of 2 characters or fewer stay case-sensitive as before. The same
matcher serves the Phase 4 guard.

Fixtures keep their own small dictionary (`skills_fixture.json`) so fixture
results don't move when the production dictionary grows.

**Unknown terms**: tech-looking tokens not in the dictionary (CamelCase,
contain `.`/`+`/`#`/digits, or ALL-CAPS 2–6 letters) go to a review queue with
suggestions (section 7.1). They're ignored for scoring until approved.

The dictionary is **data shipped with the product**: bundled into the jar as a
versioned resource (optional override path by config). Never located by
walking up from the working directory; the runtime sandbox ships no fixtures.

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
with everything else re-optimized **by the same best-first rule as #1** (D3):
the project assignment is the lexicographic best among those that include the
left-out project. (Revision 2 placed it wherever the total was highest, which
put a data project at the top of a platform resume.) Candidates are ranked by total score; the
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
Phase 3 swaps (positions, stack fit, date tabs) in one document. After the
stage-by-stage checks, the **finished** document is verified once more against
the original onboarded document, with every edit as a region, and must pass
(the 0.5pt bound holds end to end; stage checks could add up). This final
check also compares content: it caught `BlockSwapper` placing a project's
bullets in library order instead of the order `Assembler` chose (forge
`[0, 2, 1]`), which the layout-only stage checks missed. The result must pass
the Verifier; anything else is `VERIFY_FAILED` and isn't shown. #2 and #3 are
assembled only when the user opens them. **If a swap fails verification**, mark that (project, position)
pair infeasible for this resume and re-solve (section 3, same rules), at most
3 times. If a position still can't be filled, it keeps its original content
(the user's own, unswapped) and `match.json` records it under `degraded` with
the reason. A resume is dropped only if it still fails verification after
that, and the drop is logged with its reason, never silently replaced.
Measured: one mis-parsed header made resume #1 fail for all 20 real postings,
so nothing at all was delivered.

`match-batch` writes a `summary.md` row for **every** posting, including
failed or degraded ones, with the reason; an empty summary is never valid
output.

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

Uses: (a) the similarity term in scoring; (b) the cache tiebreak; (c) low-
confidence alias hints (7.1). Uses (a) and (b) compare whole sentences, which
the model is built for; (c) compares short terms, which it is not.

**Native libraries.** ONNX Runtime (and DJL's tokenizer) load native code.
Prefer extracting it into the runtime image at build time, in a root-owned,
read-only directory the libraries are pointed at, so `/tmp` can stay
`noexec`. Only if that isn't possible, mount `/tmp` with `exec` and document
why in the README. Measured: with Docker's default `noexec` `/tmp`, ONNX
Runtime fails to load its extracted library.

### 7.1 Alias suggestions (revision 2)

Measured (P5-T7, real model): MiniLM cosine **cannot** separate aliases from
related-but-different skills. React ~ React Native scores 0.74 and Spring ~
Spring Boot 0.70, above genuine aliases like TS ~ TypeScript (0.40) and
Golang ~ Go (0.43). No threshold works, so none is used.

Suggestions for an unknown term are shown to the operator in two tiers:

1. **Spelling rules (high confidence)**, reference `alias_rules` in
   `jd_ref.py`: equal after dropping spaces, hyphens and dots but **keeping `+`
   and `#`** (C, C++ and C# stay distinct); a known affix (`js`, `lang`, `ful`:
   ReactJS, Golang, RESTful); a numeronym (K8s); an initialism of CamelCase
   parts (TS, JS). Measured: 7 of 10 fixture aliases, **0 of 12** hard negatives.
2. **MiniLM nearest neighbours (low confidence)**: the top 3 canonical terms
   with their cosines, labelled as hints. This is where irregular aliases
   (Postgres, sklearn, GCP) show up.

The operator approves or rejects every suggestion. Nothing is applied
automatically, whatever the tier.

---

## 8. Missing skills (step 5.8)

Reference: `missing_skills`: required (×1.0) JD skills that no text in the
user's material names (stored variants, library bullets, stacks, titles).
Fixture: frontend → `["React"]`; data → `["Airflow", "Spark"]`; platform → none.

---

## 8.1 Implied skills and the whole resume (revision 4)

Measured on the first real run: missing-skills lists claimed Git, SQL, CI/CD,
REST, MySQL and AWS were missing from a resume whose Skills section lists them
and whose projects use GitHub, PostgreSQL and GitHub Actions. Two rules:

- **Material is the whole onboarded resume** (every paragraph, including the
  Skills section and locked text) plus all stored variants and the library.
- **Implications**: dictionary v2.1 has `_implies` (reference
  `techs_implied`), e.g. PostgreSQL → SQL, GitHub Actions → CI/CD, GitHub, Git;
  EC2 → AWS; Spring Boot → Spring, Java. They're applied transitively to the
  user's material **and to bullets when scoring keyword coverage** (a
  PostgreSQL bullet covers an SQL requirement), **never to the job
  description** (a posting asking for PostgreSQL does not ask for every SQL
  database).

Measured on 5 real postings, missing lists shrank from 6–10 skills to 2–6, all
genuinely absent from the material.

## 9. CLI and output

```
tailor match <onboarded.docx> <variants.json> <library.json> <jd.txt> <outDir> [--embedder fake|minilm]
tailor aliases review
```

`match-batch` writes each posting's `match.json` into its subfolder, next to its
PDF, and adds a **feasibility** table: for each library project, the positions
whose shape it can fill (a project lacking 2-line variants can't fill an
all-2-line position; in the real run this kept one project out of three of four
positions for every posting).

`match` writes `match.json` (parsed JD, the 1–3 resumes with labels,
missing skills, cache decision) and `resume-1.pdf` (verified). `--embedder
fake` is for fixtures and tests.

---

## 10. Tests

| Id | Test | Pass condition |
|---|---|---|
| P5-T14 | Fail-soft assembly | A fixture position whose swap always fails verification (e.g. a header made unparseable on purpose) yields a delivered resume #1 with that position unswapped and listed under `degraded`, after at most 3 re-solves; `summary.md` has a row per posting including the degraded note |
| P5-T15 | Separator spaces | A header `Title \| stack \| link` written with non-breaking spaces around either `\|` parses to `TITLE SEP DETAIL SEP LINK`, and re-emitting it keeps the original characters |
| P5-T16 | Implied skills | With dictionary v2.1, a material text naming only PostgreSQL and GitHub Actions makes SQL, CI/CD, GitHub and Git present; a job description naming PostgreSQL does not acquire SQL; missing skills consider the whole onboarded resume |
| P5-T13 | Real-world patterns | Every case in `fixtures/phase5/jd_patterns/expected.json` (10 synthetic postings, one per pattern found in 20 real ones) parses to exactly its title and weighted skills, with dictionary v2 |
| P5-T1 | Parsing | Each fixture JD's title and weighted skills equal `expected_selection.json`; the platform rewording parses identically to the original |
| P5-T2 | Scoring | `scores_job` for every JD equal the expected values exactly (FakeEmbedder) |
| P5-T3 | Assembly | Resume #1 for platform, frontend and data equals expected: job slots, positions, projects, chosen bullet indices, project scores |
| P5-T4 | Alternatives | #2 for each JD equals expected (label, job, projects); achievement groups equal expected; no resume ever has two candidates of one group |
| P5-T5 | Cache + missing | The four cache decisions and Jaccard values, and each JD's missing skills, equal expected |
| P5-T6 | Render | Resume #1 for each fixture JD assembled on `fixtures/phase3/projects_synthetic.docx` passes the Verifier (`corpusTest` tag) |
| P5-T7 | MiniLM (manual) | With the real model: report cosine for every pair in `alias_pairs.json` (report only; done in revision 1: no threshold separates them) and embedding time per 100 bullets (measured 815 ms). Also run `tailor match --embedder minilm` in the runtime sandbox with `--network none` and a `noexec` `/tmp` |
| P5-T11 | Alias rules | `alias_rules` gives exactly `fixtures/phase5/alias_rules_expected.json`: 7 of 10 aliases matched, 0 of 12 hard negatives |
| P5-T12 | Packaging | In the runtime image, with no fixtures or repo present: `tailor match` and `tailor generate` find the bundled dictionary; `tailor aliases review` shows both suggestion tiers for a queued unknown term |
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
- `alias_rules` matches any hard negative, on the fixtures or in real use.
- Anything that would change a weight, threshold or rule here.
