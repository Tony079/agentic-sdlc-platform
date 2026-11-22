--liquibase formatted sql

-- GD-36 follow-up (2026-11-22): a Technical Approver or Estimator can mark outside work done on the Development card
-- (as closing its GitHub issue does), so the waves that wait for it can start.

--changeset sdlc:0067-outside-work-done splitStatements:false
-- rls: replaces a guard function (no new table).
CREATE OR REPLACE FUNCTION app.dev_task_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  member uuid := app.current_member();
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'development tasks are not deleted' USING ERRCODE = 'P0001';
  END IF;
  IF TG_OP = 'UPDATE' THEN
    NEW.updated_at := clock_timestamp();
  END IF;
  IF member IS NULL THEN
    RETURN NEW;
  END IF;
  IF TG_OP = 'INSERT' THEN
    RAISE EXCEPTION 'development tasks are created by Garuda' USING ERRCODE = 'P0001';
  END IF;
  IF NOT (app.is_technical_approver(OLD.project_id) OR app.is_estimator(OLD.project_id))
     OR NEW.status_by IS DISTINCT FROM member THEN
    RAISE EXCEPTION 'a Technical Approver or Estimator of the project controls development' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.dev_run_id <> OLD.dev_run_id
     OR NEW.story_key <> OLD.story_key OR NEW.wave <> OLD.wave OR NEW.external <> OLD.external
     OR NEW.pr_number IS DISTINCT FROM OLD.pr_number OR NEW.branch IS DISTINCT FROM OLD.branch
     OR NOT ((OLD.status = 'FAILED' AND NEW.status = 'QUEUED')
          OR (OLD.status IN ('WAITING', 'FAILED', 'QUEUED') AND NEW.status = 'SKIPPED')
          OR (OLD.external AND OLD.status = 'WAITING' AND NEW.status = 'DONE')) THEN
    RAISE EXCEPTION 'people may only retry a failed story, skip one that is not being coded, or mark outside work done'
      USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
