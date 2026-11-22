--liquibase formatted sql

-- GD-36 follow-up (2026-11-22): by default the Developer agent codes a wave's stories one at a time and starts the next
-- only after the previous story's pull request is merged, so each story starts from code that includes the ones before
-- it (no conflicting pull requests). PARALLEL keeps the earlier behaviour (up to agent_slots at once). Applies to
-- running developments too.

--changeset sdlc:0068-dev-serial-mode splitStatements:false
-- rls: adds a column to app.dev_run, whose row-level security policies already cover it.
ALTER TABLE app.dev_run ADD COLUMN mode text NOT NULL DEFAULT 'SERIAL' CHECK (mode IN ('SERIAL', 'PARALLEL'));
GRANT UPDATE (mode) ON app.dev_run TO sdlc_app;

-- People may pause, resume, or change the branch or mode of a development that is running or paused.
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
                 AND (NEW.base_branch IS DISTINCT FROM OLD.base_branch OR NEW.mode IS DISTINCT FROM OLD.mode))) THEN
    RAISE EXCEPTION 'people may only pause or resume development, or change its branch or mode' USING ERRCODE = 'P0001';
  END IF;
  NEW.status_at := clock_timestamp();
  RETURN NEW;
END
$fn$;
