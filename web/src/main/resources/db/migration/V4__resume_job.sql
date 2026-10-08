-- The onboarding job of a resume, so its status can be reported as failed if the job dies
-- without the worker ever getting to say so (lost worker, time limit).
ALTER TABLE resumes ADD COLUMN onboard_job_id uuid REFERENCES jobs (id) ON DELETE SET NULL;
