# Paste this to Sonnet (Claude Code, in the Variant repo) to start Phase 4

You are implementing Phase 4 of Variant: generating bullet and project
variants with Claude Sonnet 5 at onboarding. Phases 1–3 are complete.

Read, in order:
1. `PHASE4_SPEC.md` — the full specification. Section 0 decisions, the system
   prompt and tool schema in section 3, and the guard rules in section 5 are
   fixed: implement them exactly, don't reword the prompt.
2. `fixtures/phase4/` — `guard_cases.json` (20 guard cases), `intake_jane_doe.json`,
   `recorded_responses.json` + `expected_generation.json` (fit-loop replay),
   `skills_seed.json` (dictionary).
3. `reference/guard_ref.py` — the Python guard that produced the guard cases.

Follow the build order in section 10, one step at a time, with each step's
tests. All tests are offline except P4-T6 (the live smoke run), which reads
`ANTHROPIC_API_KEY` from the environment (pass it with
`docker run --env-file .env ...`; never write it into code, images, logs or git).

- Never log prompt or response text: they contain the user's resume.
- Stop after the live smoke run (P4-T6) for an Opus review, with its generated
  variants, dropped variants and their reasons, token counts and cost.
- Stop earlier if a section 11 rule triggers.
- At the end, `test`, `corpusTest` and `corpus-check` must still pass.

Start with step 4.4: the truthfulness guard and P4-T1.
