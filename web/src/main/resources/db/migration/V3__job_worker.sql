-- Jobs (PHASE6_SPEC.md section 5). A job is claimed by one worker; every later write to it (progress,
-- lease extension, result) is fenced by (worker_id, attempts), so a worker that lost its lease can
-- never overwrite the outcome of the run that replaced it.
ALTER TABLE jobs ADD COLUMN worker_id text;
