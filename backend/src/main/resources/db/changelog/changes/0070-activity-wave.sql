--liquibase formatted sql

-- GD-36 follow-up (2026-11-22): development activity lines about a whole wave (it starts, it waits for outside work)
-- name the wave, so each shows inside its wave on the Development card.

--changeset sdlc:0070-activity-wave
-- rls: adds a column to app.analysis_activity, whose row-level security policies already cover it.
ALTER TABLE app.analysis_activity ADD COLUMN wave int CHECK (wave >= 0);
GRANT INSERT (wave) ON app.analysis_activity TO sdlc_worker;

--changeset sdlc:0070-activity-backfill splitStatements:false
-- rls: updates existing rows of app.analysis_activity once (the append-only guard is paused for this changeset only).
-- Lines written before stories and waves were named on them: a line quoting a story's title belongs to that story; a
-- line starting "Wave N" belongs to wave N.
ALTER TABLE app.analysis_activity DISABLE TRIGGER analysis_activity_guard;
UPDATE app.analysis_activity a SET task_id = t.id
FROM app.dev_task t
WHERE a.stage = 'DEVELOPMENT' AND a.task_id IS NULL AND a.run_id = t.dev_run_id
  AND position('"' || t.title || '"' IN a.message) > 0;
UPDATE app.analysis_activity SET wave = substring(message FROM '^Wave ([0-9]+)')::int
WHERE stage = 'DEVELOPMENT' AND task_id IS NULL AND wave IS NULL AND message ~ '^Wave [0-9]+';
ALTER TABLE app.analysis_activity ENABLE TRIGGER analysis_activity_guard;
