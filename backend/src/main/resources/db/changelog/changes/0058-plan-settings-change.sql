--liquibase formatted sql

-- GD-34 (owner, 2026-11-21): an Estimator can change the sprint settings (sprint length, start date, working days,
-- team) of a plan in review. Garuda makes a new version with the same epics and stories (edits applied), without a
-- model call, and lays out the sprints again. copied_from names the version whose stories were kept.

--changeset sdlc:0058-plan-copied-from splitStatements:false
-- rls: adds a column and a trigger function to an existing table; no tenant rows are read or changed.
ALTER TABLE app.plan_version ADD COLUMN copied_from uuid;
ALTER TABLE app.plan_version ADD CONSTRAINT plan_version_copied_from_fk
  FOREIGN KEY (tenant_id, copied_from) REFERENCES app.plan_version (tenant_id, id);

CREATE FUNCTION app.plan_version_copy_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF TG_OP = 'UPDATE' AND NEW.copied_from IS DISTINCT FROM OLD.copied_from THEN
    RAISE EXCEPTION 'plan version identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  IF TG_OP = 'INSERT' AND NEW.copied_from IS NOT NULL AND NOT EXISTS (
       SELECT 1 FROM app.plan_version v WHERE v.id = NEW.copied_from AND v.requirement_id = NEW.requirement_id
         AND v.estimate_version_id = NEW.estimate_version_id AND v.content_hash IS NOT NULL
         AND v.version_no < NEW.version_no) THEN
    RAISE EXCEPTION 'a plan version copies a produced version of the same requirement and estimate'
      USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.plan_version_copy_guard() FROM PUBLIC;
CREATE TRIGGER plan_version_copy_guard BEFORE INSERT OR UPDATE ON app.plan_version
  FOR EACH ROW EXECUTE FUNCTION app.plan_version_copy_guard();

--changeset sdlc:0058-plan-copied-from-grants
-- rls: grants only.
GRANT INSERT (copied_from) ON app.plan_version TO sdlc_app;
