# Paste this to Sonnet to start Phase 1

You are implementing Phase 1 of a resume-tailoring engine in Java 21 (Gradle,
Apache POI for package I/O, PDFBox, picocli, JUnit 5) with LibreOffice headless
for rendering, inside Docker.

Read, in order:
1. `PHASE1_SPEC.md` — the full specification. Section 0 rules are non-negotiable.
2. `golden/*.json` — the expected answers for the 9 corpus resumes.
3. `reference/*.py` — a Python version that produced the golden files. Use it to
   understand behavior; don't port it line by line.

Work through the build order in section 12, one step at a time. After each step,
run its tests and show me the results before moving on. Start with step 1.1:
the Dockerfile (with the fonts in section 2, including the patched Gelasio RT
from `tools/patch_gelasio.py`), the Gradle project, the `Renderer` interface
with `LibreOfficeRenderer`, and `tailor render`.

If a result disagrees with the golden files and it isn't a plain coding bug,
stop and tell me (section 13). Do not relax any rule to make a test pass.
