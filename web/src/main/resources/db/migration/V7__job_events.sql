-- The engine's real stages with their timings, stored with the job (PHASE6_SPEC.md revision 3), so a finished job
-- can be replayed. A JSON array of events {seq, stage, state, at_ms, duration_ms?, detail}; reset when a run starts.
ALTER TABLE jobs ADD COLUMN events jsonb NOT NULL DEFAULT '[]'::jsonb;
