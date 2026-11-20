--liquibase formatted sql

-- GD-34 (owner, 2026-11-20): an approved estimate moves the requirement to the Delivery plan. Estimators plan and edit;
-- a BRD Approver approves. The approved plan is visible to the whole project; drafts only to Estimators and BRD
-- Approvers. Design: docs/architecture/garuda-delivery-plan-stage.md.

--changeset sdlc:0056-plan-tables splitStatements:false
CREATE TABLE app.plan_version (
  id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id           uuid        NOT NULL REFERENCES app.tenant (id),
  project_id          uuid        NOT NULL,
  requirement_id      uuid        NOT NULL,
  version_no          int         NOT NULL CHECK (version_no >= 1),
  estimate_version_id uuid        NOT NULL,
  status              text        NOT NULL CHECK (status IN ('GENERATING', 'IN_REVIEW', 'APPROVED', 'SUPERSEDED',
                                                             'FAILED')),
  -- Sprint length, start date and team capacity, frozen when the version is requested.
  settings            jsonb       NOT NULL CHECK (jsonb_typeof(settings) = 'object' AND octet_length(settings::text) <= 8000),
  risks               jsonb       NOT NULL DEFAULT '[]'::jsonb
                                  CHECK (jsonb_typeof(risks) = 'array' AND octet_length(risks::text) <= 20000),
  content_hash        text        CHECK (content_hash ~ '^[0-9a-f]{64}$'),
  model_provider      text,
  model_id            text,
  change_request      text        CHECK (length(change_request) BETWEEN 10 AND 5000),
  requested_by        uuid        NOT NULL,
  failure_code        text        CHECK (failure_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  attempts            int         NOT NULL DEFAULT 1 CHECK (attempts BETWEEN 1 AND 10),
  created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
  status_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (requirement_id, version_no),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, estimate_version_id) REFERENCES app.estimate_version (tenant_id, id),
  FOREIGN KEY (tenant_id, requested_by) REFERENCES app.app_user (tenant_id, id),
  CHECK (status IN ('GENERATING', 'FAILED') OR content_hash IS NOT NULL)
);
CREATE UNIQUE INDEX plan_version_one_open_uq ON app.plan_version (requirement_id)
  WHERE status IN ('GENERATING', 'IN_REVIEW', 'FAILED');

CREATE TABLE app.plan_epic (
  id              uuid    NOT NULL DEFAULT gen_random_uuid(),
  tenant_id       uuid    NOT NULL REFERENCES app.tenant (id),
  project_id      uuid    NOT NULL,
  plan_version_id uuid    NOT NULL,
  ordinal         int     NOT NULL CHECK (ordinal >= 1),
  epic_key        text    NOT NULL CHECK (epic_key ~ '^[a-z0-9-]{1,80}$'),
  title           text    NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
  goal            text    NOT NULL CHECK (length(goal) BETWEEN 1 AND 600),
  design_refs     jsonb   NOT NULL DEFAULT '[]'::jsonb
                          CHECK (jsonb_typeof(design_refs) = 'array' AND octet_length(design_refs::text) <= 8000),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (plan_version_id, epic_key),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, plan_version_id) REFERENCES app.plan_version (tenant_id, id)
);

CREATE TABLE app.plan_story (
  id              uuid          NOT NULL DEFAULT gen_random_uuid(),
  tenant_id       uuid          NOT NULL REFERENCES app.tenant (id),
  project_id      uuid          NOT NULL,
  plan_version_id uuid          NOT NULL,
  ordinal         int           NOT NULL CHECK (ordinal >= 1),
  story_key       text          NOT NULL CHECK (story_key ~ '^[a-z0-9-]{1,80}$'),
  epic_key        text          NOT NULL CHECK (epic_key ~ '^[a-z0-9-]{1,80}$'),
  title           text          NOT NULL CHECK (length(title) BETWEEN 1 AND 300),
  acceptance_criteria jsonb     NOT NULL CHECK (jsonb_typeof(acceptance_criteria) = 'array'
                                                AND octet_length(acceptance_criteria::text) <= 8000),
  estimate_refs   jsonb         NOT NULL DEFAULT '[]'::jsonb
                                CHECK (jsonb_typeof(estimate_refs) = 'array' AND octet_length(estimate_refs::text) <= 8000),
  brd_refs        jsonb         NOT NULL DEFAULT '[]'::jsonb
                                CHECK (jsonb_typeof(brd_refs) = 'array' AND octet_length(brd_refs::text) <= 8000),
  role            text          NOT NULL CHECK (role IN ('FRONTEND', 'BACKEND', 'DATA', 'DEVOPS', 'QA', 'DESIGN', 'OTHER')),
  priority        text          NOT NULL CHECK (priority IN ('MUST', 'SHOULD', 'COULD')),
  depends_on      jsonb         NOT NULL DEFAULT '[]'::jsonb
                                CHECK (jsonb_typeof(depends_on) = 'array' AND octet_length(depends_on::text) <= 8000),
  -- Expected effort in person-days from the approved estimate (never from the model).
  effort_days     numeric(7,2)  NOT NULL CHECK (effort_days >= 0),
  flags           jsonb         NOT NULL DEFAULT '[]'::jsonb
                                CHECK (jsonb_typeof(flags) = 'array' AND octet_length(flags::text) <= 1000),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (plan_version_id, ordinal),
  UNIQUE (plan_version_id, story_key),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, plan_version_id) REFERENCES app.plan_version (tenant_id, id)
);

-- A planner's change to a story: any of title, acceptance criteria, priority or a pinned sprint, with a reason.
CREATE TABLE app.plan_edit (
  id              uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id       uuid        NOT NULL REFERENCES app.tenant (id),
  project_id      uuid        NOT NULL,
  plan_version_id uuid        NOT NULL,
  story_key       text        NOT NULL CHECK (story_key ~ '^[a-z0-9-]{1,80}$'),
  changes         jsonb       NOT NULL CHECK (jsonb_typeof(changes) = 'object' AND octet_length(changes::text) <= 10000),
  reason          text        NOT NULL CHECK (length(reason) BETWEEN 10 AND 1000),
  edited_by       uuid        NOT NULL,
  edited_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, plan_version_id) REFERENCES app.plan_version (tenant_id, id),
  FOREIGN KEY (tenant_id, edited_by) REFERENCES app.app_user (tenant_id, id)
);
CREATE INDEX plan_edit_version_ix ON app.plan_edit (tenant_id, plan_version_id, story_key);

CREATE TABLE app.plan_decision (
  id              uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id       uuid        NOT NULL REFERENCES app.tenant (id),
  project_id      uuid        NOT NULL,
  requirement_id  uuid        NOT NULL,
  plan_version_id uuid        NOT NULL,
  -- FLAG_ACCEPTED: a flagged story kept as is; GAP_ACCEPTED: an estimate work item or BRD criterion knowingly unplanned.
  kind            text        NOT NULL CHECK (kind IN ('FLAG_ACCEPTED', 'GAP_ACCEPTED', 'APPROVED', 'CHANGES_REQUESTED')),
  item_key        text        CHECK (length(item_key) <= 100),
  note            text        CHECK (length(note) <= 5000),
  decided_by      uuid        NOT NULL,
  decided_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, plan_version_id) REFERENCES app.plan_version (tenant_id, id),
  FOREIGN KEY (tenant_id, decided_by) REFERENCES app.app_user (tenant_id, id),
  CHECK ((kind IN ('FLAG_ACCEPTED', 'GAP_ACCEPTED')) = (item_key IS NOT NULL)),
  CHECK (kind <> 'CHANGES_REQUESTED' OR length(note) >= 10),
  CHECK (kind <> 'APPROVED' OR note IS NOT NULL)
);
CREATE INDEX plan_decision_version_ix ON app.plan_decision (tenant_id, plan_version_id, kind);
CREATE UNIQUE INDEX plan_decision_once_uq ON app.plan_decision (plan_version_id, kind, coalesce(item_key, ''))
  WHERE kind <> 'CHANGES_REQUESTED';

CREATE TABLE app.project_plan_defaults (
  project_id      uuid        NOT NULL,
  tenant_id       uuid        NOT NULL REFERENCES app.tenant (id),
  settings        jsonb       NOT NULL CHECK (jsonb_typeof(settings) = 'object' AND octet_length(settings::text) <= 8000),
  updated_by      uuid        NOT NULL,
  updated_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (project_id),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, updated_by) REFERENCES app.app_user (tenant_id, id)
);

--changeset sdlc:0056-plan-functions splitStatements:false
-- rls: functions only.
-- GD-34: plan content is for Estimators and BRD Approvers while drafted, and for the whole project once approved.
CREATE FUNCTION app.sees_plan(p_project uuid, p_version uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN app.current_member() IS NULL OR app.is_estimator(p_project) OR app.is_brd_approver(p_project)
      OR EXISTS (SELECT 1 FROM app.plan_version v WHERE v.id = p_version AND v.status = 'APPROVED');
REVOKE ALL ON FUNCTION app.sees_plan(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.sees_plan(uuid, uuid) TO sdlc_app, sdlc_worker, sdlc_retention;

--changeset sdlc:0056-plan-guards splitStatements:false
-- rls: trigger functions only.
CREATE FUNCTION app.plan_version_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  member uuid := app.current_member();
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'plan versions are not deleted' USING ERRCODE = 'P0001';
  END IF;
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'GENERATING' OR NEW.content_hash IS NOT NULL THEN
      RAISE EXCEPTION 'a plan version starts empty, generating' USING ERRCODE = 'P0001';
    END IF;
    IF member IS NULL OR member IS DISTINCT FROM NEW.requested_by
       OR NOT (app.is_estimator(NEW.project_id)
               OR (app.is_brd_approver(NEW.project_id) AND NEW.change_request IS NOT NULL)) THEN
      RAISE EXCEPTION 'a delivery plan is started by an Estimator of the project' USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM app.estimate_version e WHERE e.id = NEW.estimate_version_id
                   AND e.requirement_id = NEW.requirement_id AND e.status = 'APPROVED') THEN
      RAISE EXCEPTION 'a delivery plan is made from the approved estimate' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.version_no <> coalesce((SELECT max(version_no) FROM app.plan_version
                                   WHERE requirement_id = NEW.requirement_id), 0) + 1 THEN
      RAISE EXCEPTION 'plan versions are numbered in order' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.requirement_id <> OLD.requirement_id
     OR NEW.project_id <> OLD.project_id OR NEW.version_no <> OLD.version_no
     OR NEW.estimate_version_id <> OLD.estimate_version_id OR NEW.requested_by <> OLD.requested_by
     OR NEW.change_request IS DISTINCT FROM OLD.change_request OR NEW.settings <> OLD.settings
     OR NEW.created_at <> OLD.created_at THEN
    RAISE EXCEPTION 'plan version identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status <> 'GENERATING' AND (NEW.risks IS DISTINCT FROM OLD.risks
     OR NEW.content_hash IS DISTINCT FROM OLD.content_hash OR NEW.model_provider IS DISTINCT FROM OLD.model_provider
     OR NEW.model_id IS DISTINCT FROM OLD.model_id) THEN
    RAISE EXCEPTION 'plan content is immutable once produced' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status <> OLD.status AND NOT (
       (OLD.status = 'GENERATING' AND NEW.status IN ('IN_REVIEW', 'FAILED'))
    OR (OLD.status = 'FAILED' AND NEW.status = 'GENERATING')
    OR (OLD.status = 'IN_REVIEW' AND NEW.status = 'SUPERSEDED')
    OR (OLD.status = 'IN_REVIEW' AND NEW.status = 'APPROVED'
        AND EXISTS (SELECT 1 FROM app.plan_decision d WHERE d.plan_version_id = OLD.id AND d.kind = 'APPROVED'))
    OR (OLD.status = 'APPROVED' AND NEW.status = 'SUPERSEDED')) THEN
    RAISE EXCEPTION 'plan status % cannot become %', OLD.status, NEW.status USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status <> OLD.status THEN
    NEW.status_at := clock_timestamp();
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.plan_version_guard() FROM PUBLIC;
CREATE TRIGGER plan_version_guard BEFORE INSERT OR UPDATE OR DELETE ON app.plan_version
  FOR EACH ROW EXECUTE FUNCTION app.plan_version_guard();

-- Epics and stories are written only while the plan is generated.
CREATE FUNCTION app.plan_content_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'plan epics and stories are immutable; edits are recorded separately' USING ERRCODE = 'P0001';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM app.plan_version v WHERE v.id = NEW.plan_version_id
                 AND v.project_id = NEW.project_id AND v.status = 'GENERATING') THEN
    RAISE EXCEPTION 'plan content is written only while the plan is generated' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.plan_content_guard() FROM PUBLIC;
CREATE TRIGGER plan_epic_guard BEFORE INSERT OR UPDATE OR DELETE ON app.plan_epic
  FOR EACH ROW EXECUTE FUNCTION app.plan_content_guard();
CREATE TRIGGER plan_story_guard BEFORE INSERT OR UPDATE OR DELETE ON app.plan_story
  FOR EACH ROW EXECUTE FUNCTION app.plan_content_guard();

CREATE FUNCTION app.plan_edit_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  member uuid := app.current_member();
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'plan edits are append-only' USING ERRCODE = 'P0001';
  END IF;
  IF member IS NULL OR member IS DISTINCT FROM NEW.edited_by OR NOT app.is_estimator(NEW.project_id) THEN
    RAISE EXCEPTION 'an Estimator of the project edits the plan' USING ERRCODE = 'P0001';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM app.plan_story s JOIN app.plan_version v ON v.id = s.plan_version_id
                 WHERE v.id = NEW.plan_version_id AND v.project_id = NEW.project_id AND v.status = 'IN_REVIEW'
                   AND s.story_key = NEW.story_key) THEN
    RAISE EXCEPTION 'only a story of a plan in review can be edited' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.plan_edit_guard() FROM PUBLIC;
CREATE TRIGGER plan_edit_guard BEFORE INSERT OR UPDATE OR DELETE ON app.plan_edit
  FOR EACH ROW EXECUTE FUNCTION app.plan_edit_guard();

CREATE FUNCTION app.plan_decision_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  member uuid := app.current_member();
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'plan decisions are append-only' USING ERRCODE = 'P0001';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM app.plan_version v WHERE v.id = NEW.plan_version_id
                 AND v.requirement_id = NEW.requirement_id AND v.project_id = NEW.project_id
                 AND v.status = 'IN_REVIEW') THEN
    RAISE EXCEPTION 'only a plan in review can be decided' USING ERRCODE = 'P0001';
  END IF;
  IF member IS NULL OR member IS DISTINCT FROM NEW.decided_by THEN
    RAISE EXCEPTION 'a plan decision is made by the signed-in member' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.kind IN ('FLAG_ACCEPTED', 'GAP_ACCEPTED') AND NOT app.is_estimator(NEW.project_id) THEN
    RAISE EXCEPTION 'flagged stories and planning gaps are decided by an Estimator' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.kind = 'APPROVED' AND NOT app.is_brd_approver(NEW.project_id) THEN
    RAISE EXCEPTION 'a BRD Approver approves the delivery plan' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.kind = 'CHANGES_REQUESTED' AND NOT (app.is_estimator(NEW.project_id) OR app.is_brd_approver(NEW.project_id)) THEN
    RAISE EXCEPTION 'changes are requested by an Estimator or a BRD Approver' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.plan_decision_guard() FROM PUBLIC;
CREATE TRIGGER plan_decision_guard BEFORE INSERT OR UPDATE OR DELETE ON app.plan_decision
  FOR EACH ROW EXECUTE FUNCTION app.plan_decision_guard();

CREATE FUNCTION app.plan_defaults_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  member uuid := app.current_member();
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'plan defaults are not deleted' USING ERRCODE = 'P0001';
  END IF;
  IF member IS NULL OR member IS DISTINCT FROM NEW.updated_by OR NOT app.is_estimator(NEW.project_id) THEN
    RAISE EXCEPTION 'an Estimator of the project sets its plan defaults' USING ERRCODE = 'P0001';
  END IF;
  NEW.updated_at := clock_timestamp();
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.plan_defaults_guard() FROM PUBLIC;
CREATE TRIGGER plan_defaults_guard BEFORE INSERT OR UPDATE OR DELETE ON app.project_plan_defaults
  FOR EACH ROW EXECUTE FUNCTION app.plan_defaults_guard();

--changeset sdlc:0056-plan-rls splitStatements:false
-- rls: enables row-level security on the new tables.
ALTER TABLE app.plan_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.plan_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.plan_version
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());
CREATE POLICY project_access ON app.plan_version AS RESTRICTIVE
  USING (app.current_member() IS NULL OR app.member_sees_project(project_id));
DO $rls$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['plan_epic', 'plan_story', 'plan_edit', 'plan_decision'] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON app.%I USING (tenant_id = app.current_tenant()) '
                   'WITH CHECK (tenant_id = app.current_tenant())', t);
    EXECUTE format('CREATE POLICY plan_access ON app.%I AS RESTRICTIVE '
                   'USING (app.sees_plan(project_id, plan_version_id))', t);
  END LOOP;
END
$rls$;
ALTER TABLE app.project_plan_defaults ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.project_plan_defaults FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.project_plan_defaults
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());
CREATE POLICY estimate_access ON app.project_plan_defaults AS RESTRICTIVE USING (app.sees_estimate(project_id));

--changeset sdlc:0056-plan-job-event splitStatements:false
-- rls: constraint changes and a job type row (not tenant data).
ALTER TABLE app.job_type DROP CONSTRAINT job_type_code_check;
ALTER TABLE app.job_type ADD CONSTRAINT job_type_code_check CHECK (code IN ('REPO_VALIDATE', 'DOCUMENT_SCAN',
  'DOCUMENT_EXTRACT', 'BA_ANALYSIS', 'REPO_VERIFY', 'REPO_SNAPSHOT', 'REPO_INDEX', 'BA_COMMIT_RESULT', 'SECURITY_SCAN',
  'ENV_VERIFY', 'ENV_PIN', 'TDD_GENERATION', 'ESTIMATE_GENERATION', 'PLAN_GENERATION'));
INSERT INTO app.job_type (code, max_attempts, max_infra_failures, backoff_base_seconds, backoff_cap_seconds,
                          lease_seconds, max_runtime_seconds, max_retry_seconds)
  VALUES ('PLAN_GENERATION', 3, 10, 30, 900, 300, 1800, 86400);
ALTER TABLE app.outbox_event DROP CONSTRAINT outbox_event_event_type_check;
ALTER TABLE app.outbox_event ADD CONSTRAINT outbox_event_event_type_check CHECK (event_type IN (
  'RequirementSubmitted', 'RepoValidationStarted', 'RepoValidated', 'DocumentDecisionRequested',
  'DocumentDecisionRecorded', 'ManualIntakeEntered', 'ManualIntakeResumed', 'AnalysisStarted',
  'AnalysisResumed', 'ExecutionStale', 'ClarificationOpened', 'ClarificationResolved',
  'ClarificationEscalated', 'BrdVersionCreated', 'ReviewQueued', 'BrdApprovedInternal',
  'BrdReleasedToClient', 'RevisionRequested', 'RequirementRejected', 'ClientChangesRequested',
  'RequirementCancelled', 'WorkflowFailed', 'JobDead', 'AdminRetryRequested',
  'AdminRetryCompleted', 'AdminRetryRejected', 'DocumentUploaded', 'DocumentScanned',
  'DocumentProcessed', 'DocumentFailed', 'RetentionDue', 'TenantProvisioned', 'RepositoryRegistered',
  'RepositorySnapshotIndexed', 'ApprovalInvalidated', 'ClarificationAnswered', 'ReleaseApproved',
  'ChangeRequestSubmitted', 'ChangeRequestAccepted', 'EnvironmentBranchesChanged', 'BrdQuoteAccepted',
  'SourcesRoundOpened', 'TddRequested', 'EstimateRequested', 'TddApproved', 'PlanRequested', 'EstimateApproved'));
ALTER TABLE app.analysis_activity DROP CONSTRAINT analysis_activity_stage_check;
ALTER TABLE app.analysis_activity ADD CONSTRAINT analysis_activity_stage_check
  CHECK (stage IN ('BRD', 'DESIGN', 'ESTIMATE', 'PLAN'));

--changeset sdlc:0056-plan-prompt
-- rls: inserts a prompt registry row (not a tenant table).
INSERT INTO app.prompt_version (prompt_id, version, agent, text_sha256, schema_version, status) VALUES
  ('plan.backlog', 1, 'PLANNER', 'a464f0a95c06ab11459a5be974e0e72962026d15905c029bb16dfce679f2a008', 1, 'ACTIVE');

--changeset sdlc:0056-plan-grants
-- rls: grants only.
GRANT SELECT ON app.plan_version, app.plan_epic, app.plan_story, app.plan_edit, app.plan_decision,
  app.project_plan_defaults TO sdlc_app, sdlc_worker, sdlc_retention;
GRANT INSERT (tenant_id, project_id, requirement_id, version_no, estimate_version_id, status, settings, change_request,
              requested_by) ON app.plan_version TO sdlc_app;
GRANT UPDATE (status, attempts, failure_code) ON app.plan_version TO sdlc_app;
GRANT INSERT ON app.plan_edit, app.plan_decision TO sdlc_app;
GRANT INSERT, UPDATE ON app.project_plan_defaults TO sdlc_app;
GRANT UPDATE (status, risks, content_hash, model_provider, model_id, failure_code) ON app.plan_version TO sdlc_worker;
GRANT INSERT ON app.plan_epic, app.plan_story TO sdlc_worker;
