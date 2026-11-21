--liquibase formatted sql

-- GD-36 follow-up (2026-11-21): the branch development works from and opens pull requests into is chosen when the plan
-- is pushed (and can be changed on a running development); activity lines name the story they are about, so each
-- story shows its own log.

--changeset sdlc:0065-dev-base-branch splitStatements:false
-- rls: adds columns to app.dev_run and app.plan_push, whose row-level security policies already cover them.
ALTER TABLE app.dev_run ADD COLUMN base_branch text
  CHECK (length(base_branch) BETWEEN 1 AND 255 AND base_branch !~ '[[:cntrl:] ~^:?*\[\\]' AND base_branch !~ '\.\.'
         AND base_branch !~ '^/|/$' AND base_branch !~ '^garuda/');
ALTER TABLE app.plan_push ADD COLUMN base_branch text
  CHECK (length(base_branch) BETWEEN 1 AND 255 AND base_branch !~ '[[:cntrl:] ~^:?*\[\\]' AND base_branch !~ '\.\.'
         AND base_branch !~ '^/|/$' AND base_branch !~ '^garuda/');
GRANT INSERT (base_branch) ON app.plan_push TO sdlc_app;
GRANT UPDATE (base_branch) ON app.dev_run TO sdlc_app;

-- People may pause, resume, or change the branch of a development that is running or paused (and nothing else).
CREATE OR REPLACE FUNCTION app.dev_run_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  member uuid := app.current_member();
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'development runs are not deleted' USING ERRCODE = 'P0001';
  END IF;
  IF member IS NULL THEN
    IF TG_OP = 'UPDATE' THEN
      NEW.status_at := CASE WHEN NEW.status <> OLD.status THEN clock_timestamp() ELSE OLD.status_at END;
    END IF;
    RETURN NEW;
  END IF;
  IF TG_OP = 'INSERT' THEN
    RAISE EXCEPTION 'development starts on its own after the plan is pushed' USING ERRCODE = 'P0001';
  END IF;
  IF NOT (app.is_technical_approver(OLD.project_id) OR app.is_estimator(OLD.project_id))
     OR NEW.status_by IS DISTINCT FROM member THEN
    RAISE EXCEPTION 'a Technical Approver or Estimator of the project controls development' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.project_id <> OLD.project_id
     OR NEW.requirement_id <> OLD.requirement_id OR NEW.plan_version_id <> OLD.plan_version_id
     OR NEW.repository_id <> OLD.repository_id OR NEW.current_wave <> OLD.current_wave
     OR NEW.agent_slots <> OLD.agent_slots OR NEW.started_at <> OLD.started_at
     OR NEW.finished_at IS DISTINCT FROM OLD.finished_at
     OR NOT ((OLD.status = 'RUNNING' AND NEW.status = 'PAUSED') OR (OLD.status = 'PAUSED' AND NEW.status = 'RUNNING')
             OR (OLD.status IN ('RUNNING', 'PAUSED') AND NEW.status = OLD.status
                 AND NEW.base_branch IS DISTINCT FROM OLD.base_branch)) THEN
    RAISE EXCEPTION 'people may only pause or resume development, or change its branch' USING ERRCODE = 'P0001';
  END IF;
  NEW.status_at := clock_timestamp();
  RETURN NEW;
END
$fn$;

--changeset sdlc:0065-activity-task
-- rls: adds a column to app.analysis_activity, whose row-level security policies already cover it.
ALTER TABLE app.analysis_activity ADD COLUMN task_id uuid;
CREATE INDEX analysis_activity_task_ix ON app.analysis_activity (tenant_id, task_id, id) WHERE task_id IS NOT NULL;
GRANT INSERT (task_id) ON app.analysis_activity TO sdlc_worker;
