-- A candidate has one text per line count (its "length"), so the length belongs in the item's key.
-- (V1 keyed items by library, section and candidate only; nothing had been stored yet.)
ALTER TABLE library_items DROP CONSTRAINT library_items_pkey;
ALTER TABLE library_items ADD PRIMARY KEY (library_id, section_id, candidate_id, length);
ALTER TABLE library_items ALTER COLUMN length SET NOT NULL;

-- Generation runs: the job that does the work, and a state the user can read.
ALTER TABLE generation_runs ADD COLUMN job_id uuid REFERENCES jobs (id) ON DELETE SET NULL;
ALTER TABLE generation_runs ADD CONSTRAINT generation_runs_status
    CHECK (status IN ('queued', 'running', 'succeeded', 'failed'));
