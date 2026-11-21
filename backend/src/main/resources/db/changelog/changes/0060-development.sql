--liquibase formatted sql

-- GD-36 (owner, 2026-11-21): Garuda's Developer agent. After an approved agent-mode plan is pushed to GitHub Issues,
-- development runs on its own: wave by wave, the agent codes each story in the devbox sandbox and opens a pull request
-- on a garuda/... branch; people review and merge in GitHub; the next wave starts when the previous one is merged.
-- The worker writes runs and tasks; Technical Approvers and Estimators may pause, resume, retry and skip.
-- Design: docs/architecture/garuda-developer-agent.md.

--changeset sdlc:0060-dev-tables splitStatements:false
CREATE TABLE app.dev_run (
  id               uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id        uuid        NOT NULL REFERENCES app.tenant (id),
  project_id       uuid        NOT NULL,
  requirement_id   uuid        NOT NULL,
  plan_version_id  uuid        NOT NULL,
  repository_id    uuid        NOT NULL,
  status           text        NOT NULL DEFAULT 'RUNNING'
                               CHECK (status IN ('RUNNING', 'PAUSED', 'DELIVERED', 'STOPPED')),
  current_wave     int         NOT NULL DEFAULT 0 CHECK (current_wave >= 0),
  agent_slots      int         NOT NULL DEFAULT 2 CHECK (agent_slots BETWEEN 1 AND 50),
  started_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  finished_at      timestamptz,
  status_by        uuid,
  status_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, plan_version_id) REFERENCES app.plan_version (tenant_id, id),
  FOREIGN KEY (tenant_id, repository_id) REFERENCES app.repository (tenant_id, id),
  FOREIGN KEY (tenant_id, status_by) REFERENCES app.app_user (tenant_id, id)
);
CREATE UNIQUE INDEX dev_run_one_active_uq ON app.dev_run (requirement_id) WHERE status IN ('RUNNING', 'PAUSED');
CREATE INDEX dev_run_status_ix ON app.dev_run (tenant_id, status);

CREATE TABLE app.dev_task (
  id               uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id        uuid        NOT NULL REFERENCES app.tenant (id),
  project_id       uuid        NOT NULL,
  requirement_id   uuid        NOT NULL,
  dev_run_id       uuid        NOT NULL,
  story_key        text        NOT NULL CHECK (story_key ~ '^[a-z0-9-]{1,80}$'),
  title            text        NOT NULL CHECK (length(title) BETWEEN 1 AND 300),
  wave             int         NOT NULL CHECK (wave >= 0),
  -- Outside work (people or suppliers): followed through its GitHub issue, never coded.
  external         boolean     NOT NULL DEFAULT false,
  issue_number     int         CHECK (issue_number > 0),
  status           text        NOT NULL DEFAULT 'WAITING'
                               CHECK (status IN ('WAITING', 'QUEUED', 'CODING', 'PR_OPEN', 'MERGED', 'CLOSED', 'FAILED',
                                                 'SKIPPED', 'DONE')),
  attempts         int         NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 20),
  branch           text        CHECK (branch ~ '^garuda/[a-z0-9-]{1,40}/[a-z0-9-]{1,80}$'),
  base_sha         text        CHECK (base_sha ~ '^[0-9a-f]{40}$'),
  head_sha         text        CHECK (head_sha ~ '^[0-9a-f]{40}$'),
  pr_number        int         CHECK (pr_number > 0),
  pr_url           text        CHECK (pr_url ~ '^https://' AND length(pr_url) <= 500),
  summary          text        CHECK (length(summary) <= 4000),
  tests_passed     boolean,
  files_changed    int         CHECK (files_changed >= 0),
  failure_code     text        CHECK (failure_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  status_by        uuid,
  updated_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (dev_run_id, story_key),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, dev_run_id) REFERENCES app.dev_run (tenant_id, id),
  FOREIGN KEY (tenant_id, status_by) REFERENCES app.app_user (tenant_id, id)
);
CREATE INDEX dev_task_run_ix ON app.dev_task (tenant_id, dev_run_id, wave);

--changeset sdlc:0060-dev-guards splitStatements:false
-- rls: trigger functions only.
-- People (API, a signed-in member) may only: pause or resume a run, retry a failed story, skip a waiting or failed
-- story; and only as a Technical Approver or Estimator of the project. Everything else is the worker's.
CREATE FUNCTION app.dev_run_guard() RETURNS trigger
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
     OR NOT ((OLD.status = 'RUNNING' AND NEW.status = 'PAUSED') OR (OLD.status = 'PAUSED' AND NEW.status = 'RUNNING')) THEN
    RAISE EXCEPTION 'people may only pause or resume development' USING ERRCODE = 'P0001';
  END IF;
  NEW.status_at := clock_timestamp();
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.dev_run_guard() FROM PUBLIC;
CREATE TRIGGER dev_run_guard BEFORE INSERT OR UPDATE OR DELETE ON app.dev_run
  FOR EACH ROW EXECUTE FUNCTION app.dev_run_guard();

CREATE FUNCTION app.dev_task_guard() RETURNS trigger
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
          OR (OLD.status IN ('WAITING', 'FAILED', 'QUEUED') AND NEW.status = 'SKIPPED')) THEN
    RAISE EXCEPTION 'people may only retry a failed story or skip one that is not being coded' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.dev_task_guard() FROM PUBLIC;
CREATE TRIGGER dev_task_guard BEFORE INSERT OR UPDATE OR DELETE ON app.dev_task
  FOR EACH ROW EXECUTE FUNCTION app.dev_task_guard();

--changeset sdlc:0060-dev-rls splitStatements:false
-- rls: enables row-level security on the new tables.
DO $rls$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['dev_run', 'dev_task'] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON app.%I USING (tenant_id = app.current_tenant()) '
                   'WITH CHECK (tenant_id = app.current_tenant())', t);
    EXECUTE format('CREATE POLICY project_access ON app.%I AS RESTRICTIVE '
                   'USING (app.current_member() IS NULL OR app.member_sees_project(project_id))', t);
  END LOOP;
END
$rls$;

--changeset sdlc:0060-dev-job-event splitStatements:false
-- rls: constraint changes and job type rows (not tenant data).
ALTER TABLE app.job_type DROP CONSTRAINT job_type_code_check;
ALTER TABLE app.job_type ADD CONSTRAINT job_type_code_check CHECK (code IN ('REPO_VALIDATE', 'DOCUMENT_SCAN',
  'DOCUMENT_EXTRACT', 'BA_ANALYSIS', 'REPO_VERIFY', 'REPO_SNAPSHOT', 'REPO_INDEX', 'BA_COMMIT_RESULT', 'SECURITY_SCAN',
  'ENV_VERIFY', 'ENV_PIN', 'TDD_GENERATION', 'ESTIMATE_GENERATION', 'PLAN_GENERATION', 'PLAN_PUSH', 'DEV_STORY',
  'DEV_TICK'));
-- A story can take the agent a long time (builds and tests in the devbox): long lease and runtime, few retries.
INSERT INTO app.job_type (code, max_attempts, max_infra_failures, backoff_base_seconds, backoff_cap_seconds,
                          lease_seconds, max_runtime_seconds, max_retry_seconds)
  VALUES ('DEV_STORY', 2, 10, 120, 1800, 600, 7200, 86400),
         ('DEV_TICK', 3, 10, 30, 300, 120, 600, 3600);
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
  'SourcesRoundOpened', 'TddRequested', 'EstimateRequested', 'TddApproved', 'PlanRequested', 'EstimateApproved',
  'PlanPushRequested', 'DevelopmentChanged'));
ALTER TABLE app.analysis_activity DROP CONSTRAINT analysis_activity_stage_check;
ALTER TABLE app.analysis_activity ADD CONSTRAINT analysis_activity_stage_check
  CHECK (stage IN ('BRD', 'DESIGN', 'ESTIMATE', 'PLAN', 'DEVELOPMENT'));

--changeset sdlc:0060-dev-grants
-- rls: grants only.
GRANT SELECT ON app.dev_run, app.dev_task TO sdlc_app, sdlc_worker, sdlc_retention;
GRANT UPDATE (status, status_by) ON app.dev_run TO sdlc_app;
GRANT UPDATE (status, status_by, failure_code) ON app.dev_task TO sdlc_app;
GRANT INSERT ON app.dev_run, app.dev_task TO sdlc_worker;
GRANT UPDATE (status, current_wave, finished_at) ON app.dev_run TO sdlc_worker;
GRANT UPDATE (status, attempts, base_sha, head_sha, branch, pr_number, pr_url, summary, tests_passed, files_changed,
              failure_code, issue_number) ON app.dev_task TO sdlc_worker;

--changeset sdlc:0060-dev-prompt
-- rls: inserts a prompt registry row (not a tenant table).
INSERT INTO app.prompt_version (prompt_id, version, agent, text_sha256, schema_version, status) VALUES
  ('dev.story', 1, 'DEVELOPER', '12bcb5017018515158492554b0c4725b6e0b7ad344b44965f079c8e4cc3e6ce8', 1, 'ACTIVE');
