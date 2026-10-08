# PHASE6_SPEC — Web app: API, jobs, storage, edits, AWS

Revision 2 (2026-10-08). Owner decisions: React + Vite frontend; sign-in with Google
or an email link; edits apply to that resume only.

**What changed in revision 2:** the AWS design (about $200/month for dev, $580 for
prod) is unaffordable before revenue. **v1 runs on one server for $0/month**
(section 10A); the AWS design is kept, unapplied, as section 10B. Also:

- file storage gains a **filesystem mode** (section 3.1); the unmaintained MinIO
  image is development-only and optional;
- sign-in links can no longer be consumed by email scanners (sections 4.1, 9.1);
- email sign-in can be **switched off** (no domain yet: Google only, section 4.1);
- edit revisions are verified against the **stored onboarding baseline** (section 6);
- section role changes use the engine's existing override (section 4.2);
- an **ARM gate** before any ARM server is trusted (section 10A.4);
- new tests P6-T15 to P6-T19.

Phase 6 turns the engine (Phases 1–5, all done) into a service a user reaches from a
browser. This spec covers **6A, the backend**: API, data, background jobs, rendering,
edits, security and AWS. **6B, the frontend** (React + Vite) is built against the
OpenAPI contract that 6A produces; its look and interaction design are decided
separately by the owner. Payments are Phase 8; this phase only records usage.

The engine's behaviour does not change in this phase. Everything here wraps it.

---

## 1. What "done" means

A new user, with the 6B frontend, goes from sign-in to upload, onboarding preview,
intake, generation, pasting a posting, resume #1 with alternatives, an edit, and a
PDF download, with nobody's help, on the single server (section 10A). Section 12 lists the acceptance tests.

---

## 2. Architecture

```
browser (React + Vite SPA)
   │  HTTPS, session cookie
   ▼
ALB ──► api        (Spring Boot, `web` module)     ──► RDS PostgreSQL (data + job queue)
                                                   ──► S3 (files, private)
        worker     (same image, role=worker)       ──► RDS, S3, Anthropic API
           │  internal HTTP, files in the request body
           ▼
        renderer   (LibreOffice pool; NO network egress)
```

The diagram shows the AWS form (10B). On the single server (10A), the ALB becomes
Caddy, RDS becomes a Postgres container, and S3 becomes filesystem storage
(section 3.1); everything else is the same code.

- **One codebase, one language.** A new Gradle module `web` (Spring Boot) holds the
  API and the worker. Both run from one image; an environment variable picks the role.
  The engine and CLI modules stay as they are; the CLI keeps working for development.
- **api** handles HTTP only: auth, validation, reads, enqueueing jobs. It never runs
  LibreOffice and never calls the Anthropic API.
- **worker** claims jobs from PostgreSQL and runs the engine (onboarding, generation,
  matching, alternatives, edit verification). It loads MiniLM once per process.
- **renderer** is a separate service that only turns `.docx` bytes into PDF bytes.
  It keeps a pool of persistent LibreOffice processes (the Phase 5 backlog item).
  It has **no outbound network access at all** (security group with no egress rules),
  a read-only root filesystem, a non-root user, a `tmpfs` scratch directory, and
  per-render time limits. This preserves the Phase 2 sandbox guarantee in the cloud.
- **Engine change (the only one):** a `RemoteRenderer` implementing the engine's
  existing renderer interface, calling the renderer service. The local renderer stays
  for the CLI and tests. Same inputs must give identical layout results either way
  (P6-T9).

### 2.1 Renderer service

- `POST /render` with the `.docx` bytes (≤ 10 MB) returns the PDF bytes, or an error
  code: `RENDER_TIMEOUT` (30 s per render), `RENDER_FAILED`.
- Pool: N LibreOffice processes per task (start with one per vCPU). A process is
  restarted after 200 renders, on a crash, or on a timeout. Health check = a 1-page
  fixture render under 5 s.
- Each request gets its own scratch directory, deleted afterwards. No file outlives
  its request.
- Only the worker's security group may reach it.
- The renderer reports its LibreOffice version (`rendererVersion`), stored with every
  onboarding. If a deployment changes it, onboardings from the older version must be
  re-onboarded (the Phase 5 "re-onboard" error), never silently reused.

---

## 3. Data model (PostgreSQL)

Migrations with Flyway. All ids are UUIDs. Every user-owned row carries `user_id`,
and every query filters by it (section 9). Timestamps are UTC.

| Table | Key columns | Notes |
|---|---|---|
| `users` | id, email (unique, lower-case), google_sub (nullable, unique), created_at, deleted_at | One account per email; Google and email-link sign-ins with the same verified email are the same user. |
| `login_tokens` | id, email, token_hash, expires_at, used_at | Email links: 32 random bytes, SHA-256 stored, 15-minute life, single use. |
| `resumes` | id, user_id, status, active, original_key, normalized_key, preview_key, baseline_key, onboard_json, blocks_json, renderer_version, created_at | One `active` resume per user in v1. A new upload creates a new row and, once accepted, makes it active; the old one is archived, never deleted silently. |
| `section_roles` | resume_id, section_key, suggested_role, confirmed_role | Confirmed by the user before intake. |
| `intake_sections` | resume_id, section_id, kind, mode, fields (jsonb), raw_text, updated_at | Phase 4 intake, one row per section, including added projects (`project-new-N`). |
| `libraries` | id, resume_id, version, created_at | **Immutable.** Any change to material (regeneration, a variant removed in review) creates a new version. |
| `library_items` | library_id, section_id, candidate_id, length, text, source, home_section | `source` = `generated` in v1. Project header fields are stored per project. |
| `generation_runs` | id, resume_id, library_id, status, report (jsonb), cost_usd, started_at, finished_at | The Phase 4 generation report, coverage included. |
| `postings` | id, user_id, text, parsed (jsonb), fingerprint, created_at | Text ≤ 20,000 characters (`JD_TOO_LONG`). |
| `matches` | id, posting_id, library_id, result (jsonb), cache_of (nullable) | The Phase 5 `match.json`. Cache hits point at the match they reuse. |
| `snapshots` | id, user_id, match_id, rank, label, assembly (jsonb), parent_id, edits (jsonb), docx_key, pdf_key, status, created_at | A tailored resume. **Immutable once rendered** (section 6). |
| `jobs` | id, user_id, type, payload (jsonb), status, priority, attempts, progress (jsonb), error_code, created_at, started_at, finished_at, lease_until | The job queue (section 5). |
| `usage_ledger` | id, user_id, kind, ref_id, idempotency_key (unique), cost_usd, created_at | Section 8. |
| `alias_queue` | term, first_seen, count, status | Replaces the Phase 5 queue file; operator-only. |

Files are keyed `users/{user_id}/resumes/{resume_id}/…` and
`users/{user_id}/snapshots/{snapshot_id}/…`. The uploaded original is never modified
or overwritten.

### 3.1 Storage modes

One storage interface, two implementations, chosen by `APP_STORAGE_MODE`:

| Mode | Where files live | How a user downloads |
|---|---|---|
| `filesystem` (**v1 default**) | A directory on a Docker volume (`APP_STORAGE_DIR`), owned by the app's non-root user, mode 700 | `GET /files/{opaque id}` on the api: ownership check, then the bytes are streamed. No public URLs exist. |
| `s3` (section 10B) | A private S3 bucket, server-side encryption | A 5-minute presigned link, issued after the ownership check |

- Both modes write atomically (write to a temporary name, then rename) and treat
  originals as write-once.
- Deleting a user (`DELETE /me`) removes their whole key prefix in either mode.
- MinIO stays usable for testing the `s3` mode locally, but nothing in the default
  path depends on it. If kept, pin a specific image version rather than `latest`.

---

## 4. API

REST over HTTPS, JSON, session cookie. Every endpoint is described in an **OpenAPI
3.1 document generated from the code and committed** (`web/openapi.json`); it is the
contract 6B builds against. Errors share one shape:

```json
{"code": "INVALID_LINK", "message": "user-facing text", "details": {"field": "links[0].url"}}
```

Codes are the engine's (all Phase 2–5 codes) plus those below. `message` is draft
copy; the frontend may replace it but must handle every `code`.

### 4.1 Auth

| Method, path | Does |
|---|---|
| `GET /auth/google` → `GET /auth/google/callback` | OpenID Connect sign-in. Requires `email_verified`. |
| `GET /config` | Public, no session: which sign-in methods are on (`{"signin": ["google"]}`), so the frontend shows only those. |
| `POST /auth/email` `{email}` | Sends a sign-in link. Always answers 202, whether or not the email exists, so it can't be used to probe accounts. When email sign-in is off (`APP_MAIL_MODE=off`) it answers 404 `EMAIL_SIGNIN_DISABLED`. |
| `GET /auth/email/verify?token=…` | **Does not consume the token.** Checks it is valid and shows a minimal server-rendered page: "Continue as {email}" with one button. Email security scanners open links before people do; a `GET` that signs in would burn every token. |
| `POST /auth/email/verify` `{token}` | Consumes the single-use, 15-minute token and starts the session. Protected against CSRF like every other POST. |
| `POST /auth/logout` | Ends the session. |
| `GET /me` | The user, active resume id, and usage summary. |
| `DELETE /me` | Deletes the account and all data (section 9.4). |

Rate limits: 5 email links per address per hour, 20 per IP per hour.

### 4.2 Resume and onboarding

| Method, path | Does |
|---|---|
| `POST /resumes` (multipart, ≤ 2 MB) | Runs the Phase 2 upload gate **synchronously** (no rendering; instant). A rejection returns 422 with the gate's code and nothing is stored. Otherwise stores the original and returns 202 `{resume_id, job_id}` for onboarding. |
| `GET /resumes/{id}` | Status, onboarding report, section roles and positions (`blocks`), font substitutions. |
| `GET /resumes/{id}/preview` | A short-lived link to the preview PDF. |
| `PUT /resumes/{id}/sections/{key}/role` `{role}` | Confirms or changes a section's role (`projects`, `experience`, `other`). Changing a role uses the engine's existing role override (Phase 3, the CLI's `--section-role "HEADING=role"`) and re-runs the position analysis; no new onboarding. `ROLE_CHANGE_UNSUPPORTED` is only for a specific case the engine truly can't handle, named in the response. |
| `POST /resumes/{id}/accept` | The user approves the normalized preview; the resume becomes active. |

`NEEDS_USER` (the resume can't keep its page count with stand-in fonts) is reported
with its options in `details`. How the user picks among them is a 6B question; the API
accepts the choice as `POST /resumes/{id}/accept {font_choice}`.

### 4.3 Intake and generation

| Method, path | Does |
|---|---|
| `GET /resumes/{id}/intake` | The pre-filled intake (Phase 4 template merged with saved answers). |
| `PUT /resumes/{id}/intake/sections/{sid}` | Saves one section: mode, fields, notes. Validates links (`INVALID_LINK`) and length (`NOTES_TOO_LONG`, 1,500 words). |
| `POST /resumes/{id}/intake/projects` / `DELETE …/projects/{sid}` | Adds or removes a project (`TOO_MANY_PROJECTS` past 8). |
| `POST /resumes/{id}/generate` | 202 `{job_id}`. Charges the user nothing in Phase 6; records cost in the ledger. |
| `GET /resumes/{id}/library` | The active library: variants per section, projects, coverage, drop summary. |
| `POST /libraries/{id}/variants/{vid}/remove` | Review: the user removes a generated variant they don't stand behind. Creates a new library version. |

Generation for some sections only (after a user edits one section's notes) is
`POST /resumes/{id}/generate {sections:[…]}`; untouched sections carry over unchanged
into the new library version.

### 4.4 Postings, matching and snapshots

| Method, path | Does |
|---|---|
| `POST /postings` `{text}` | Parses and matches. Returns 200 with the match on a cache hit; otherwise 202 `{posting_id, job_id}`. |
| `GET /postings` | The user's postings, newest first (history). |
| `GET /postings/{id}/match` | The match: resume #1's snapshot, alternatives (scored), missing skills, infeasible reasons. Library ids in labels are resolved to project titles here, so the frontend never maps ids itself. |
| `POST /snapshots/{id}/render` | Renders an alternative on request. 202 `{job_id}`. |
| `GET /snapshots/{id}` | Assembly, editable slots with their `hintChars`, edits, status, revision chain. |
| `GET /snapshots/{id}/pdf` | A short-lived download link. |

### 4.5 Edits (section 6)

| Method, path | Does |
|---|---|
| `POST /snapshots/{id}/slots/{slot}/check` `{text}` | Render check for one slot: `FITS`, `FITS_WITH_PADDING` or `TOO_LONG`. Synchronous, about 1 s. 30 per minute per user. |
| `POST /snapshots/{id}/revisions` `{edits:[{slot, text}…]}` | Applies edits as a **new snapshot** (a revision of this one), verifies it, renders its PDF. 202 `{snapshot_id, job_id}`. |

### 4.6 Jobs

| Method, path | Does |
|---|---|
| `GET /jobs/{id}` | Status (`queued`, `running`, `succeeded`, `failed`), progress, result reference, error code. |
| `GET /jobs/{id}/events` | Server-sent events carrying the same information as it changes. |

Progress is coarse and honest: named stages (`gate`, `normalize`, `detect`,
`calibrate`; `generating section 2 of 6`; `assembling`, `verifying`), never an
invented percentage.

---

## 5. Background jobs

- **Queue:** the `jobs` table. Workers claim with `SELECT … FOR UPDATE SKIP LOCKED`
  and set `lease_until`; a job whose lease expires (a crashed worker) is claimed again.
- **Types, priority (highest first) and time limits:**

| Type | Priority | Limit | Retried on infrastructure failure? |
|---|---|---|---|
| `match`, `edit_revision` | 1 | 60 s | Once |
| `render_alternative` | 2 | 60 s | Once |
| `onboard` | 3 | 180 s (Phase 2 deadline) | Once |
| `generate` | 4 | 10 min | **Never automatically** (it costs money; the user retries) |

- An engine **rejection** (a gate code, `NEEDS_USER`, `TOO_FEW_EDITABLE`…) is a
  result, not a failure: never retried.
- **Per-user limits:** at most one `onboard` or `generate` job and two `match` jobs
  running per user; further jobs wait. Postings: 200 per user per day (provisional;
  revisit with real usage).
- **Idempotency:** creating a job accepts an `Idempotency-Key` header; the same key
  within 24 hours returns the existing job.
- **Latency target:** a cache-miss posting returns resume #1 in ≤ 8 s at p50 and
  ≤ 15 s at p95 with warm services (engine time ~5 s plus queue and transfer).

---

## 6. Snapshots and edits

**The rule: a rendered snapshot never changes.** This is how hand edits are never
overwritten.

- Matching a posting creates up to three snapshots (ranks 1–3). Rank 1 is rendered at
  once; ranks 2–3 are stored as assemblies and rendered on request. Once rendered, a
  snapshot's document, PDF, assembly and label are frozen.
- **An edit never modifies a snapshot.** Saving edits creates a **revision**: a new
  snapshot whose `parent_id` is the edited one, built by applying the edits to the
  parent's document (not by re-assembling from the library), then verified with the
  Phase 5 combined verification against the **stored onboarding baseline**
  (`baseline.json`), never against the parent or the chain's first snapshot. Checking
  each revision against its parent would let drift add up, up to 0.5 pt per
  revision; checking against the baseline keeps every revision within 0.5 pt of the
  original. The parent stays
  byte-identical. Revisions chain; the latest revision is the one offered for
  download by default.
- **Library changes never touch snapshots.** When the library gets a new version, the
  next match of a posting creates new snapshots from it; existing snapshots, edited or
  not, stay exactly as they are and stay downloadable. The cache key includes the
  library version, so a stale match is never served.
- **Edits apply to that resume only** (owner decision). No edit flows into the library
  or into other snapshots.

### 6.1 What can be edited (v1)

- **Bullet slots** the snapshot marks editable: job bullets and project bullets.
  Locked slots are not editable.
- **Text** (any words; it's the user's own claim) or **blank** (empty text = the
  Phase 1 blank: the slot keeps its height).
- Not editable in v1: headers, titles, stacks, links, job order, which project is in
  which position (that's choosing an alternative instead).
- Input normalization before checking: trim; newlines and tabs become spaces; runs of
  spaces collapse; at most 1,000 characters (`EDIT_TOO_LONG`, before any render).
- **Bullet endings** follow the resume's convention (Phase 4 §6.1), as for generated
  text.

### 6.2 Checking an edit

- The frontend may show a live estimate from the slot's `hintChars`; it is an estimate
  only.
- `…/check` renders the parent with that one slot replaced (one render) and returns
  the verdict. Saving re-checks everything, so a stale check can't slip through.
- A revision whose verification fails is not created: the response names the slot and
  the reason (`TOO_LONG`, `VERIFY_FAILED`). Unverified output is never stored as a
  downloadable snapshot.

---

## 7. Generation and the Anthropic API

- Only the worker calls the API, with the key from the environment (`deploy/.env` on
  the single server; Secrets Manager in 10B).
- Phase 4 rules apply unchanged: strict tool use, no fit retries, the top-up call,
  the $0.50 per-onboarding cap (`COST_LIMIT`).
- A user can run generation again (after changing notes); each run is a new library
  version. **Soft limit:** 5 generation runs per resume per day (provisional), so
  repeated regenerations can't run up cost.
- API errors (429, 5xx) follow the Phase 4 retry policy for **API calls** (3 retries
  with backoff), which is separate from the removed fit retries.

---

## 8. Usage ledger (groundwork for Phase 8)

Every billable or costly event writes one row with a unique idempotency key, so a
retried request can never write twice:

| kind | When | Idempotency key |
|---|---|---|
| `generation` | A generation run finishes (records `cost_usd`) | generation run id |
| `tailoring` | Resume #1 of a posting is delivered for the first time | user id + posting fingerprint + library version |
| `alternative` | An alternative is rendered | snapshot id |

Phase 6 charges nothing. Phase 8 sets prices and credits from these rows ("first
tailoring free" = no prior `tailoring` row). Edits and cache hits write nothing.

---

## 9. Security and privacy

### 9.1 Accounts and sessions

- Sessions: server-side (Spring Session in PostgreSQL), cookie `HttpOnly`, `Secure`,
  `SameSite=Lax`, 14-day idle expiry. CSRF protection on every state-changing request
  (double-submit token readable by the SPA).
- Google: OpenID Connect with `state` and `nonce`; reject unverified emails.
- Email links: single-use, 15-minute tokens (section 3). A link may be opened on a
  different device from the one that requested it. Opening the link only shows a
  confirmation page; the sign-in happens on its POST (section 4.1). This stops
  scanners consuming tokens, and the page names the account, so a link someone else
  sent you can't silently sign you into their account.

### 9.2 Authorization

- Every resource is looked up **by id and owner** in one query; another user's
  resource answers 404 (not 403), so ids can't be probed.
- File downloads always pass the ownership check first: streamed by the api in
  `filesystem` mode, or a 5-minute S3 presigned link in `s3` mode (bucket blocks
  public access).
- The operator alias review stays a CLI command against the database in v1; it is not
  in the public API.

### 9.3 Data handling

- Upload limits and the Phase 2 gate run before anything is stored or rendered.
- **No resume, notes, posting or bullet text in logs**, in errors sent to monitoring,
  or in logged job payloads. Logs carry ids, codes, counts and timings. Enforced by a
  test (P6-T10).
- Data sent to Anthropic: only at generation, only the section's intake and current
  bullets (the Phase 4 prompt). The privacy policy must say so.
- Encryption at rest on RDS and S3. TLS everywhere, including ALB to tasks.

### 9.4 Deletion

- `DELETE /me` removes the account immediately and all of the user's rows and S3
  objects (originals, previews, snapshots) within 24 hours. Ledger rows keep only ids
  and amounts (no text).
- Retention while an account is active: kept until the user deletes it (provisional;
  confirm with the owner before launch).

---

## 10A. Single-server deployment (v1)

**Cost: $0/month.** One Linux server runs the existing docker-compose stack behind
Caddy. Nothing in this section is specific to one cloud provider: it works on any
Ubuntu 24.04 machine with 4 GB of RAM or more, ARM or x86.

### 10A.1 Where

| Choice | Machine | Cost |
|---|---|---|
| **Default** | Oracle Cloud Always Free, `VM.Standard.A1.Flex`: 2 OCPU, 12 GB RAM, ARM (aarch64), Ubuntu 24.04 | $0 |
| Fallback, x86 | One small EC2 machine (4 GB) on the owner's AWS free-plan credit | $0 until the credit or the 6-month free plan ends |
| Fallback, paid | Any 4 GB VPS | about $8/month |

Oracle reclaims an Always Free machine only when CPU, network **and** memory all stay
below 20% for 7 days. With Postgres, both JVMs, MiniLM and the LibreOffice pool
resident, memory stays above that. Never add artificial load to avoid reclamation.

### 10A.2 What runs

`deploy/docker-compose.prod.yml` (the development `docker-compose.yml` stays as is):

| Service | Public? | Notes |
|---|---|---|
| `caddy` | Ports **80 and 443 only** | Automatic HTTPS (Let's Encrypt). Serves the SPA's static build at `/` and proxies `/api/*` to `api`. |
| `api` | No | `APP_STORAGE_MODE=filesystem`, `APP_MAIL_MODE=off`, `APP_ENV=prod` |
| `worker` | No | Shares the internal network with `renderer` |
| `renderer` | No | **Internal network only** (`internal: true`, no route out), as in development; read-only root, non-root, tmpfs, `cap_drop: ALL` |
| `postgres` | No | **No published port.** Random password from `.env`. |

- **Same origin.** The SPA and the API share one host name, so the session cookie is
  first-party (`SameSite=Lax`) and no CORS is needed. `APP_PUBLIC_BASE_URL` is
  `https://{host}/api`; every absolute URL the api generates (sign-in links, the
  Google redirect `https://{host}/api/auth/google/callback`) is built from it. Choose
  either Caddy stripping `/api` or a servlet context path, and test that redirects,
  OpenAPI and cookies all agree.
- Spring trusts `X-Forwarded-*` only from Caddy, so rate limits see the real client IP.
- **Memory limits** (`mem_limit` plus explicit `-Xmx`), sized for 12 GB: api 1 GB,
  worker 3 GB, renderer 3 GB with a pool of 2, Postgres 1 GB, Caddy 128 MB. A
  `small` profile for 4 GB hosts: renderer pool 1, smaller heaps, plus a 2 GB swap
  file. Renderer pool size comes from `RENDERER_POOL_SIZE`.
- Restart policy `unless-stopped`; health checks on every service; Docker log
  rotation (`max-size` 10 MB, 3 files), since logs carry no user text (9.3).

### 10A.3 Host name and sign-in without a domain

- **Host name:** a free DuckDNS subdomain (`{name}.duckdns.org`) pointing at the
  server's public IP. Caddy gets its certificate with the HTTP challenge, so no DNS
  credentials are needed. Google's redirect-URI rules (HTTPS, no raw IPs, a
  public-suffix TLD) are met.
- **Sign-in: Google only.** `APP_MAIL_MODE=off`; `GET /config` reports it.
- **Later, with a real domain:** a new `APP_MAIL_MODE=smtp` (`SMTP_HOST`, `SMTP_PORT`,
  `SMTP_USER`, `SMTP_PASSWORD`, `APP_MAIL_FROM`), so any transactional email provider
  works without code changes. `ses` remains for 10B. Not needed for v1.

### 10A.4 The ARM gate (required before trusting an ARM server)

Every golden file and corpus check was measured with LibreOffice 24.2 on x86. The
product's promise is that nothing on the page moves, so ARM must be proven, not
assumed:

1. Build the images for `linux/arm64` (on the server itself, or multi-arch with
   `docker buildx`). LibreOffice must be the same 24.2.x version; the renderer reports
   it (`rendererVersion`).
2. On the ARM server, inside the arm64 image: run `corpus-check` on all 9 corpus
   resumes and P5-T17's identity check on the 3 fixture postings.
3. **All must pass.** If any fails, report the differences and do not use ARM; use the
   x86 fallback.
4. The corpus resumes are real people's data: copy them up only for the check, then
   delete them from the server (`shred` the files, then remove the directory).

### 10A.5 Backups

- `deploy/backup.sh`, nightly by cron: `pg_dump` plus a tar of the storage volume,
  **encrypted with `age`** to a public key whose private key never touches the
  server. Keeps the last 7 locally.
- `deploy/pull-backup.ps1` (Windows) copies the latest backup to the owner's machine
  over SSH. Run weekly at least.
- `deploy/restore.sh` restores into an empty stack; P6-T17 proves the round trip.

### 10A.6 Host security

- Open ports: 22 (SSH), 80, 443. On Oracle, open them in **both** the VCN security
  list and the host firewall (Oracle's Ubuntu images ship restrictive `iptables`
  rules).
- SSH: key only, password and root login disabled.
- `unattended-upgrades` for security updates.
- `deploy/.env`: mode 600, never committed (`deploy/.env.example` documents every
  variable). Holds the Postgres password, Anthropic key, Google client secret and the
  backup public key.
- Docker publishes nothing except Caddy's 80 and 443.

### 10A.7 Scripts

| Script | Does |
|---|---|
| `deploy/install.sh` | Fresh Ubuntu 24.04: Docker, firewall rules, swap (small profile), unattended upgrades, a `variant` user, the backup cron. Safe to run twice. |
| `deploy/deploy.sh` | Pulls the repo at a given commit, builds images on the host, runs migrations, restarts with zero manual steps; refuses to start if the ARM gate hasn't passed on this host. |
| `deploy/backup.sh`, `deploy/restore.sh`, `deploy/pull-backup.ps1` | Section 10A.5. |
| `deploy/README.md` | Step by step, for the owner, from creating the server to the first sign-in. |

Capacity (estimate): with a renderer pool of 2, about two postings are matched at once
at ~5 to 6 s each, enough for a small private beta. Measure it (P6-T13) rather than
assume it.

---

## 10B. AWS deployment (deferred; do not apply)

Kept for when revenue justifies it (about $200/month dev, $580/month prod, revision 1
estimate). Before it is ever applied: add a VPC endpoint policy limiting the
renderer's S3 access to our own buckets and the region's ECR layer bucket, and
re-estimate cost. Everything below is unchanged from revision 1.

| Piece | Service | Notes |
|---|---|---|
| api | ECS Fargate behind an ALB with an ACM certificate | 2 tasks minimum |
| worker | ECS Fargate | 1–2 tasks; MiniLM files baked into the image with their SHA-256 pins |
| renderer | ECS Fargate | 2 tasks; security group with **no egress**; read-only root, non-root, tmpfs |
| Database | RDS PostgreSQL 16 | Encrypted; automated backups |
| Files | S3 | Private, encrypted, versioning on |
| Email links | SES | Verified sending domain |
| Secrets | Secrets Manager | Anthropic key, OAuth client secret, session secret |
| Logs and metrics | CloudWatch | No text content (9.3) |
| Frontend | S3 + CloudFront | The SPA's static files; CORS limited to the app origin |

- Infrastructure as code with Terraform in `infra/`, separate `dev` and `prod`
  workspaces. Nothing created by hand.
- The worker needs outbound HTTPS to the Anthropic API (through a NAT gateway or
  equivalent); the renderer needs none; S3 through a VPC endpoint.
- **Cost:** estimate from the AWS pricing pages before creating `prod`; the NAT gateway
  and the always-on Fargate tasks are the main fixed costs. Record the estimate in the
  README.
- Local development: docker-compose with PostgreSQL, MinIO (S3-compatible), the
  renderer, api and worker; email links printed to the log; Google sign-in with a test
  client.

---

## 11. Build order (Sonnet)

Each step ends with its tests passing; `test`, `corpusTest` and `corpus-check` stay
green throughout (the engine must not change behaviour).

1. **6.1 Skeleton:** `web` module, Spring Boot, Flyway migrations for section 3,
   docker-compose for local development, OpenAPI generation.
2. **6.2 Renderer service and `RemoteRenderer`:** pool, limits, health check; the
   identity test (P6-T9).
3. **6.3 Auth:** Google, email links, sessions, CSRF, `DELETE /me`.
4. **6.4 Jobs:** queue, leases, priorities, per-user limits, SSE progress.
5. **6.5 Resume and onboarding endpoints.**
6. **6.6 Intake and generation endpoints**, library versions, variant removal.
7. **6.7 Postings, matching, snapshots, alternatives**, the cache keyed with the
   library version.
8. **6.8 Edits:** slot check, revisions, immutability.
9. **6.9 Ledger, rate limits, log hygiene.**
10. **6.10 Terraform for AWS** (written; not applied, section 10B).
11. **6.11 Revision 2 changes:** email-link confirmation, `GET /config` and
    `APP_MAIL_MODE=off`, role overrides, baseline-verified revisions, filesystem
    storage.
12. **6.12 Single-server deployment** (section 10A): `deploy/` compose, Caddy, scripts,
    README. Then the owner creates the server, runs the ARM gate and deploys.

---

## 12. Acceptance tests

| Id | Test |
|---|---|
| P6-T1 | Sign-in: Google (mocked provider) and email link; a link works once and expires after 15 minutes; `POST /auth/email` answers the same for known and unknown emails. |
| P6-T2 | **Isolation:** for every endpoint, user B gets 404 for user A's resources, and presigned links are only issued after the owner check. |
| P6-T3 | Upload: each gate rejection fixture returns its code synchronously with nothing stored; a good fixture produces an onboarding job whose result equals the CLI's `onboard.json`. |
| P6-T4 | Intake validation: `INVALID_LINK`, `NOTES_TOO_LONG`, `TOO_MANY_PROJECTS`. |
| P6-T5 | Generation with recorded responses equals the CLI result; the ledger row is written once even if the job is delivered twice. |
| P6-T6 | Matching the 3 fixture postings through the API gives the same `match.json` and PDF text as the CLI; a repeated posting is a cache hit; a new library version is never served a stale match. |
| P6-T7 | Edits: `check` verdicts match the engine on fixture slots; a revision leaves its parent byte-identical; a revision that fails verification is not created; edits never change the library. |
| P6-T8 | Snapshot immutability: after regeneration, old snapshots (edited and not) are unchanged and downloadable; new matches use the new library. |
| P6-T9 | Renderer: `RemoteRenderer` and the local renderer give identical layout results on all 9 corpus resumes; the renderer cannot reach any outside host; a killed LibreOffice process is replaced and the job succeeds. |
| P6-T10 | Log hygiene: a full fixture run's logs contain none of the fixture's resume, notes or posting text. |
| P6-T11 | Jobs: lease expiry re-runs a job; per-user limits hold; `generate` is never retried automatically; idempotency keys return the same job. |
| P6-T12 | `web/openapi.json` is generated from the code and matches the committed file (CI fails on drift). |
| P6-T13 | Latency: on the single server (10A), cache-miss postings return resume #1 within the section 5 targets. |
| P6-T14 | Deletion: `DELETE /me` removes every row and file for the user, in both storage modes. |
| P6-T15 | ARM gate: on the arm64 image, `corpus-check` passes on all 9 resumes and P5-T17 identity holds; `deploy.sh` refuses to start without that result. |
| P6-T16 | Email links: a `GET` of the link (as a scanner would) leaves the token usable; the `POST` signs in once; a second `POST` fails; with `APP_MAIL_MODE=off`, `POST /auth/email` returns `EMAIL_SIGNIN_DISABLED` and `GET /config` omits email. |
| P6-T17 | Backups: `backup.sh` then `restore.sh` into an empty stack reproduces users, libraries, snapshots and files; the archive can't be read without the private key. |
| P6-T18 | Revisions: three successive edit revisions each stay within 0.5 pt of the onboarding baseline on every fixed line. |
| P6-T19 | Single-server exposure: from outside the host only 80 and 443 answer (plus 22); Postgres and the api are unreachable directly; the renderer still cannot reach any outside host. |

---

## 13. Out of scope for 6A

Applying the AWS design (10B), the frontend's design and code (6B), payments (Phase 8), the Chrome extension
(Phase 7), multiple active resumes per user, editing headers or adding bullets, team
or admin consoles.