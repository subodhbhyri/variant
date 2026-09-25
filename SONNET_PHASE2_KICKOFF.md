# Paste this to Sonnet (Claude Code, in the Variant repo) to start Phase 2

You are implementing Phase 2 of Variant, a resume-tailoring engine in Java 21
(Gradle, PDFBox, picocli, JUnit 5) with LibreOffice headless for rendering,
run inside Docker. Phase 1 is complete and passes `tailor corpus-check`.

Read, in order:
1. `PHASE2_SPEC.md` — this phase's full specification. Section 0 decisions and
   section 2's check order are fixed.
2. `fixtures/phase2/expected.json` — the expected outcome for every fixture.
3. `reference/gate_ref.py` — a Python version of the upload gate that already
   reproduces every expected reason. Use it to understand behavior.
4. `PHASE1_SPEC.md` sections 3, 4 and 7 — the Phase 1 rules Phase 2 builds on.

Work through the build order in PHASE2_SPEC.md section 7, one step at a time.
After each step, run its tests in Docker and show me the results:

    docker build -f docker/Dockerfile -t resume-tailor .
    docker run --rm resume-tailor gradle --no-daemon :engine:test --tests "<TestClass>"

Rules:
- A rejected upload must cost zero renders; prove it with a counting renderer.
- Never raise a limit, reorder the gate checks, or special-case a fixture to
  make a test pass (section 8). Stop and report instead.
- After step 2 (the upload gate), stop and tell me — it gets an Opus security
  review before you continue.
- At the end, `tailor corpus-check /app/corpus /app/golden` must still print
  ALL PASS.

Start with step 2.1a: depth limit in SafeXml and removing recursion from
BodyWalker and DomUtil, with the P2-T6 test.
