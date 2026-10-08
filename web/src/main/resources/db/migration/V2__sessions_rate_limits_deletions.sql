-- Spring Session JDBC (PHASE6_SPEC.md section 9.1: server-side sessions in PostgreSQL).
-- The standard schema from spring-session-jdbc, created here so Flyway owns every table.
CREATE TABLE spring_session (
    primary_id            char(36)     NOT NULL,
    session_id            char(36)     NOT NULL,
    creation_time         bigint       NOT NULL,
    last_access_time      bigint       NOT NULL,
    max_inactive_interval integer      NOT NULL,
    expiry_time           bigint       NOT NULL,
    principal_name        varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id char(36)     NOT NULL,
    attribute_name     varchar(200) NOT NULL,
    attribute_bytes    bytea        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

-- Rate limits that hold across api tasks (section 4.1: 5 email links per address and 20 per IP
-- per hour; section 4.5: 30 slot checks a minute; section 5: 200 postings a day). The subject is
-- a hash or an id, never an address or text.
CREATE TABLE rate_events (
    bucket      text        NOT NULL,
    subject     text        NOT NULL,
    created_at  timestamptz NOT NULL
);
CREATE INDEX rate_events_lookup ON rate_events (bucket, subject, created_at);

-- Section 9.4: DELETE /me removes the database rows at once; the user's S3 objects are removed
-- within 24 hours. A row here is the durable promise to do that, written in the same transaction
-- as the deletion so a crash can't lose it.
CREATE TABLE storage_deletions (
    prefix        text PRIMARY KEY,
    created_at    timestamptz NOT NULL DEFAULT now(),
    completed_at  timestamptz
);
