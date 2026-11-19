--liquibase formatted sql

-- GD-29 (owner, 2026-11-19): the requirement page shows what the agents are doing while they work, as a timeline of
-- steps written by the worker (reading inputs, choosing files, writing, a rejected answer and its repair, checking
-- references, questions, done or failed). Lines are written by trusted code, never by the model; a rejection reason
-- holds field paths and rule messages only. Append-only.

--changeset sdlc:0048-analysis-activity splitStatements:false
CREATE TABLE app.analysis_activity (
  id             bigint      GENERATED ALWAYS AS IDENTITY,
  tenant_id      uuid        NOT NULL REFERENCES app.tenant (id),
  project_id     uuid        NOT NULL,
  requirement_id uuid        NOT NULL,
  stage          text        NOT NULL CHECK (stage IN ('BRD', 'DESIGN')),
  run_id         uuid,
  kind           text        NOT NULL CHECK (kind IN ('STEP', 'DONE', 'WARN', 'FAIL')),
  message        text        NOT NULL CHECK (length(message) BETWEEN 1 AND 600),
  created_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id)
);
CREATE INDEX analysis_activity_requirement_ix ON app.analysis_activity (tenant_id, requirement_id, id);

CREATE FUNCTION app.analysis_activity_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  RAISE EXCEPTION 'analysis activity is append-only' USING ERRCODE = 'P0001';
END
$fn$;
REVOKE ALL ON FUNCTION app.analysis_activity_guard() FROM PUBLIC;
CREATE TRIGGER analysis_activity_guard BEFORE UPDATE OR DELETE ON app.analysis_activity
  FOR EACH ROW EXECUTE FUNCTION app.analysis_activity_guard();

ALTER TABLE app.analysis_activity ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.analysis_activity FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.analysis_activity
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());
CREATE POLICY project_access ON app.analysis_activity AS RESTRICTIVE
  USING (app.current_member() IS NULL OR app.member_sees_project(project_id));

GRANT SELECT ON app.analysis_activity TO sdlc_app, sdlc_worker, sdlc_retention;
GRANT INSERT (tenant_id, project_id, requirement_id, stage, run_id, kind, message) ON app.analysis_activity TO sdlc_worker;
