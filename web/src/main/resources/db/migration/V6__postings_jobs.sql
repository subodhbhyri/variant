-- A posting is matched against one library version (the cache key includes it), by one job.
ALTER TABLE postings ADD COLUMN library_id uuid REFERENCES libraries (id) ON DELETE CASCADE;
ALTER TABLE postings ADD COLUMN job_id uuid REFERENCES jobs (id) ON DELETE SET NULL;
CREATE INDEX postings_library ON postings (library_id);

-- At most one match per posting (a cache hit is a match row that points at the one it reuses).
CREATE UNIQUE INDEX matches_one_per_posting ON matches (posting_id);

-- A snapshot's chain of revisions is walked by parent_id; ranks are unique per match for originals.
CREATE UNIQUE INDEX snapshots_one_original_per_rank ON snapshots (match_id, rank) WHERE parent_id IS NULL;
