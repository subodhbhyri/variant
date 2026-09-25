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

## Deployment (Phase 2 sandbox)

Uploads are onboarded inside a restricted, non-root container, not the dev
image above — see `PHASE2_SPEC.md` section 3. Build and run it with:

```
docker build -f docker/Dockerfile --target runtime -t variant-runtime .
docker run --rm --network none --read-only --tmpfs /tmp:rw,size=512m \
  --memory 1g --cpus 1 --pids-limit 256 --cap-drop ALL \
  --security-opt no-new-privileges \
  -v <input dir>:/in:ro -v <output dir>:/out \
  variant-runtime java -jar /app/tailor.jar onboard /in/resume.docx /out
```

**The `/out` mount must be writable by UID 10001 (the `variant` user)
before the container starts** — the sandbox runs read-only and non-root, so
it cannot chown its own output directory. Whatever prepares that mount
(the deploying orchestration, a provisioning script, …) needs to either
create it with permissive permissions or `chown -R 10001:10001` it first;
otherwise every onboarding run fails writing `normalized.docx`.
