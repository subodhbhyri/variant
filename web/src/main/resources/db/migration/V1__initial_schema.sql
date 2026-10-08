-- PHASE6_SPEC.md section 3. All ids are UUIDs, timestamps are UTC (timestamptz).
--
-- Section 3 and 9.2 say every user-owned row carries user_id and every query filters by it.
-- The spec's table lists user_id only on some tables ("key columns"); to make the rule uniform
-- (an owner check in the same query as the lookup, never a join), every user-owned table here
-- carries user_id, and deleting a user cascades to all of them (section 9.4).
-- usage_ledger is deliberately NOT a foreign key: its rows survive account deletion with ids and
-- amounts only (section 9.4).

CREATE TABLE users (
    id          uuid PRIMARY KEY,
    email       text        NOT NULL,
    google_sub  text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz,
    CONSTRAINT users_email_lower CHECK (email = lower(email))
);
CREATE UNIQUE INDEX users_email_key ON users (email);
CREATE UNIQUE INDEX users_google_sub_key ON users (google_sub) WHERE google_sub IS NOT NULL;

-- Email links: 32 random bytes, only the SHA-256 is stored, 15-minute life, single use.
CREATE TABLE login_tokens (
    id          uuid PRIMARY KEY,
    email       text        NOT NULL,
    token_hash  bytea       NOT NULL,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT login_tokens_hash_len CHECK (octet_length(token_hash) = 32)
);
CREATE UNIQUE INDEX login_tokens_hash_key ON login_tokens (token_hash);
CREATE INDEX login_tokens_email_created ON login_tokens (email, created_at);

CREATE TABLE resumes (
    id                uuid PRIMARY KEY,
    user_id           uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status            text        NOT NULL,
    active            boolean     NOT NULL DEFAULT false,
    original_key      text,
    normalized_key    text,
    preview_key       text,
    baseline_key      text,
    onboard_json      jsonb,
    blocks_json       jsonb,
    renderer_version  text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT resumes_status CHECK (status IN
        ('uploaded', 'onboarding', 'needs_user', 'ready', 'accepted', 'rejected', 'failed', 'archived'))
);
-- One active resume per user in v1.
CREATE UNIQUE INDEX resumes_one_active ON resumes (user_id) WHERE active;
CREATE INDEX resumes_user ON resumes (user_id, created_at DESC);

CREATE TABLE section_roles (
    resume_id       uuid NOT NULL REFERENCES resumes (id) ON DELETE CASCADE,
    user_id         uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    section_key     text NOT NULL,
    suggested_role  text,
    confirmed_role  text,
    PRIMARY KEY (resume_id, section_key)
);
CREATE INDEX section_roles_user ON section_roles (user_id);

CREATE TABLE intake_sections (
    resume_id   uuid NOT NULL REFERENCES resumes (id) ON DELETE CASCADE,
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    section_id  text NOT NULL,
    kind        text NOT NULL,
    mode        text,
    fields      jsonb NOT NULL DEFAULT '{}'::jsonb,
    raw_text    text,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (resume_id, section_id)
);
CREATE INDEX intake_sections_user ON intake_sections (user_id);

-- Immutable: a change to material is a new version (enforced below).
CREATE TABLE libraries (
    id          uuid PRIMARY KEY,
    resume_id   uuid        NOT NULL REFERENCES resumes (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    version     integer     NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (resume_id, version)
);
CREATE INDEX libraries_user ON libraries (user_id);

CREATE TABLE library_items (
    library_id    uuid NOT NULL REFERENCES libraries (id) ON DELETE CASCADE,
    user_id       uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    section_id    text NOT NULL,
    candidate_id  text NOT NULL,
    length        integer,
    text          text NOT NULL,
    source        text NOT NULL DEFAULT 'generated',
    home_section  text,
    -- Project header fields are stored per project (spec: library_items notes).
    project_header jsonb,
    PRIMARY KEY (library_id, section_id, candidate_id)
);
CREATE INDEX library_items_user ON library_items (user_id);

CREATE TABLE generation_runs (
    id           uuid PRIMARY KEY,
    resume_id    uuid        NOT NULL REFERENCES resumes (id) ON DELETE CASCADE,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    library_id   uuid        REFERENCES libraries (id) ON DELETE CASCADE,
    status       text        NOT NULL,
    report       jsonb,
    cost_usd     numeric(12, 6) NOT NULL DEFAULT 0,
    started_at   timestamptz NOT NULL DEFAULT now(),
    finished_at  timestamptz
);
CREATE INDEX generation_runs_resume ON generation_runs (resume_id, started_at DESC);
CREATE INDEX generation_runs_user ON generation_runs (user_id);

CREATE TABLE postings (
    id           uuid PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    text         text        NOT NULL,
    parsed       jsonb,
    fingerprint  text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT postings_text_len CHECK (char_length(text) <= 20000)
);
CREATE INDEX postings_user ON postings (user_id, created_at DESC);
CREATE INDEX postings_fingerprint ON postings (user_id, fingerprint);

CREATE TABLE matches (
    id          uuid PRIMARY KEY,
    user_id     uuid  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    posting_id  uuid  NOT NULL REFERENCES postings (id) ON DELETE CASCADE,
    library_id  uuid  NOT NULL REFERENCES libraries (id) ON DELETE CASCADE,
    result      jsonb NOT NULL,
    -- A cache hit points at the match it reuses.
    cache_of    uuid  REFERENCES matches (id) ON DELETE SET NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX matches_user ON matches (user_id);
CREATE INDEX matches_posting ON matches (posting_id);
-- The cache key includes the library version (section 6), so a stale match is never served.
CREATE INDEX matches_library ON matches (library_id);

CREATE TABLE snapshots (
    id          uuid PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    match_id    uuid        NOT NULL REFERENCES matches (id) ON DELETE CASCADE,
    rank        integer     NOT NULL,
    label       text        NOT NULL,
    assembly    jsonb       NOT NULL,
    parent_id   uuid        REFERENCES snapshots (id) ON DELETE CASCADE,
    edits       jsonb,
    docx_key    text,
    pdf_key     text,
    status      text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT snapshots_status CHECK (status IN ('stored', 'rendering', 'rendered', 'failed')),
    CONSTRAINT snapshots_rank CHECK (rank >= 0),
    CONSTRAINT snapshots_rendered_has_files CHECK (status <> 'rendered' OR (docx_key IS NOT NULL AND pdf_key IS NOT NULL))
);
CREATE INDEX snapshots_user ON snapshots (user_id);
CREATE INDEX snapshots_match ON snapshots (match_id, rank);
CREATE INDEX snapshots_parent ON snapshots (parent_id);

CREATE TABLE jobs (
    id               uuid PRIMARY KEY,
    user_id          uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type             text        NOT NULL,
    payload          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status           text        NOT NULL,
    priority         integer     NOT NULL,
    attempts         integer     NOT NULL DEFAULT 0,
    progress         jsonb,
    result           jsonb,
    error_code       text,
    idempotency_key  text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    started_at       timestamptz,
    finished_at      timestamptz,
    lease_until      timestamptz,
    CONSTRAINT jobs_status CHECK (status IN ('queued', 'running', 'succeeded', 'failed')),
    CONSTRAINT jobs_type CHECK (type IN
        ('onboard', 'generate', 'match', 'render_alternative', 'edit_revision'))
);
CREATE INDEX jobs_claim ON jobs (priority, created_at) WHERE status = 'queued';
CREATE INDEX jobs_lease ON jobs (lease_until) WHERE status = 'running';
CREATE INDEX jobs_user ON jobs (user_id, created_at DESC);
CREATE UNIQUE INDEX jobs_idempotency ON jobs (user_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- Section 8. No FK to users: rows outlive an account and hold only ids and amounts.
CREATE TABLE usage_ledger (
    id               uuid PRIMARY KEY,
    user_id          uuid        NOT NULL,
    kind             text        NOT NULL,
    ref_id           uuid,
    idempotency_key  text        NOT NULL,
    cost_usd         numeric(12, 6) NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT usage_ledger_kind CHECK (kind IN ('generation', 'tailoring', 'alternative'))
);
CREATE UNIQUE INDEX usage_ledger_idempotency ON usage_ledger (idempotency_key);
CREATE INDEX usage_ledger_user ON usage_ledger (user_id, created_at);

-- Operator-only; replaces the Phase 5 queue file.
CREATE TABLE alias_queue (
    term        text PRIMARY KEY,
    first_seen  timestamptz NOT NULL DEFAULT now(),
    count       integer     NOT NULL DEFAULT 1,
    status      text        NOT NULL DEFAULT 'pending'
);

-- ---------------------------------------------------------------------------------------------
-- Immutability, enforced by the database so no code path can break the section 6 rules.
-- ---------------------------------------------------------------------------------------------

-- "Libraries are immutable. Any change to material creates a new version." Rows are never updated.
-- (DELETE is allowed only so account deletion and resume cascades can remove them.)
CREATE FUNCTION forbid_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% rows are immutable (PHASE6_SPEC.md section 6)', TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER libraries_immutable BEFORE UPDATE ON libraries
    FOR EACH ROW EXECUTE FUNCTION forbid_update();
CREATE TRIGGER library_items_immutable BEFORE UPDATE ON library_items
    FOR EACH ROW EXECUTE FUNCTION forbid_update();

-- "A rendered snapshot never changes": once status = 'rendered', nothing about it may change
-- (document, PDF, assembly, label, lineage, edits). A snapshot is rendered by one transition
-- from a non-rendered status that sets docx_key and pdf_key.
CREATE FUNCTION guard_rendered_snapshot() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'rendered' AND (
           NEW.status      IS DISTINCT FROM OLD.status
        OR NEW.user_id     IS DISTINCT FROM OLD.user_id
        OR NEW.match_id    IS DISTINCT FROM OLD.match_id
        OR NEW.rank        IS DISTINCT FROM OLD.rank
        OR NEW.label       IS DISTINCT FROM OLD.label
        OR NEW.assembly    IS DISTINCT FROM OLD.assembly
        OR NEW.parent_id   IS DISTINCT FROM OLD.parent_id
        OR NEW.edits       IS DISTINCT FROM OLD.edits
        OR NEW.docx_key    IS DISTINCT FROM OLD.docx_key
        OR NEW.pdf_key     IS DISTINCT FROM OLD.pdf_key) THEN
        RAISE EXCEPTION 'a rendered snapshot never changes (PHASE6_SPEC.md section 6)'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER snapshots_rendered_frozen BEFORE UPDATE ON snapshots
    FOR EACH ROW EXECUTE FUNCTION guard_rendered_snapshot();
