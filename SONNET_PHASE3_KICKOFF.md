# Paste this to Sonnet (Claude Code, in the Variant repo) to start Phase 3

You are implementing Phase 3 of Variant: project blocks. Phases 1 and 2 are
complete; `tailor corpus-check` and the Phase 2 onboarding tests pass.

Read, in order:
1. `PHASE3_SPEC.md` — the full specification. Section 0 decisions are fixed.
2. `fixtures/phase3/expected.json` and `fixtures/phase3/library.json` — the
   synthetic resume's expected positions and swap outcomes, and the project
   library format (the Phase 4 contract).
3. `golden/phase3_blocks.json` — expected sections and positions for all 9
   corpus resumes, plus which corpus rotations are skipped.
4. `reference/blocks_ref.py` — the Python reference for sections, blocks,
   header templates, header rendering, date-tab conversion and style-chain
   bullet detection. It produced both expected files.

Follow the build order in section 10, one step at a time, with each step's
tests in Docker. Measurement-sensitive rules in sections 4.1 and 6 (date tabs,
where line breaks go, which run's formatting inline bullets use) were each found
by a measured failure; implement them exactly.

- Never add or remove paragraphs to make a shape fit.
- Stop after step 2 (header templates, link relationships, date tabs) for an
  Opus review, and whenever a section 11 stop rule triggers.
- At the end, all Phase 1 and 2 tests and `corpus-check` must still pass.

Start with step 3.1: sections, roles, blocks, positions and shapes, with
`tailor blocks` and tests P3-T1 and P3-T2.
