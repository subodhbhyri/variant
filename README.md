# resume-tailor — Phase 1

Build order and rules: see `PHASE1_SPEC.md`. Expected answers: `golden/*.json`.
Python lab that produced them: `reference/`. Gelasio patch tool: `tools/`.

## Status

- [x] Step 1.1 — project setup, `Renderer`, `LibreOfficeRenderer`, `tailor render`
- [x] Step 1.2 — font normalization, `tailor normalize` (passed on all 9)
- [x] Step 1.3 — bullet detection, `tailor detect` (passed on all 9, 130 bullets)
- [x] Step 1.4 — Substituter/Blanker/Padder, structural tests (passed on all 9)
- [~] Step 1.5 — in progress:
      - Measurement (`PdfLines`, `AnchorMeasurer`): done, T3 passed, real render-based T4 passed
      - Calibration (`BatchCalibrator`): done, needs your test run (T11 + self-consistency)
      - Validation (`BatchValidator`), Verification: not started — next delivery
- [ ] Step 1.6 — corpus-check

## Build (in Docker — see docker/Dockerfile)

```
docker build -f docker/Dockerfile -t resume-tailor .
docker run --rm resume-tailor gradle --no-daemon :cli:run --args="render corpus/resume_EHR.docx /tmp"
```

`corpus/` in this repo holds the 9 resumes used for golden-file testing.
