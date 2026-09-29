# Paste this to Sonnet (Claude Code, in the Variant repo) to start Phase 5

You are implementing Phase 5 of Variant: matching a job description to the
user's stored material and assembling up to 3 verified resumes. Phases 1–4
are complete. No LLM is called in this phase.

Read, in order:
1. `PHASE5_SPEC.md` — the full specification. Section 0 decisions and every
   weight, threshold and rule are fixed.
2. `fixtures/phase5/` — four job descriptions (`jds/`), `shapes.json`,
   `job_variants.json`, `skills_fixture.json`, `alias_pairs.json`, and
   `expected_selection.json` (the exact expected results). The project library
   is `fixtures/phase3/library.json`.
3. `reference/jd_ref.py` (with `reference/guard_ref.py`) — the Python reference
   that produced `expected_selection.json`.

Build an `Embedder` interface with `FakeEmbedder` (exactly `fake_similarity`
in the reference) for all fixture tests; `MiniLmEmbedder` comes last and needs
the model files the operator downloads.

Follow the build order in section 11, one step at a time, with each step's
tests. Results must match `expected_selection.json` exactly (determinism is a
requirement, D7). Stop after P5-T7 (the real-model measurement) for an Opus
review, and earlier if a section 12 rule triggers. At the end, `test`,
`corpusTest` and `corpus-check` must still pass.

Start with step 5.1: the job-description parser and P5-T1.
