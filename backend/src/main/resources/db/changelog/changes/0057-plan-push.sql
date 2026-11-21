--liquibase formatted sql

-- GD-34 phase 2 (owner, 2026-11-21): an Estimator pushes the approved delivery plan to GitHub Issues of a repository
-- registered to the project. Stories become issues with acceptance-criteria checklists, sprints become milestones with
-- due dates, epics, roles and priorities become labels. The API freezes what is pushed when it is requested; the worker
-- pushes it through the GitHub App (the API never holds GitHub credentials). Each pushed item is recorded as soon as
-- GitHub returns it, so pushing again updates the same issues instead of creating new ones.
-- Design: docs/architecture/garuda-delivery-plan-stage.md, section 9.

--changeset sdlc:0057-plan-push-tables splitStatements:false
CREATE TABLE app.plan_push (
  id               uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id        uuid        NOT NULL REFERENCES app.tenant (id),
  project_id       uuid        NOT NULL,
  requirement_id   uuid        NOT NULL,
  plan_version_id  uuid        NOT NULL,
  repository_id    uuid        NOT NULL,
  status           text        NOT NULL DEFAULT 'QUEUED'
                               CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'PARTIAL', 'FAILED')),
  -- Milestones and issues exactly as they are pushed (frozen at request time).
  payload          jsonb       NOT NULL CHECK (jsonb_typeof(payload) = 'object'
                                               AND octet_length(payload::text) <= 2000000),
  items_total      int         NOT NULL CHECK (items_total BETWEEN 1 AND 1000),
  items_created    int         NOT NULL DEFAULT 0 CHECK (items_created >= 0),
  items_updated    int         NOT NULL DEFAULT 0 CHECK (items_updated >= 0),
  items_failed     int         NOT NULL DEFAULT 0 CHECK (items_failed >= 0),
  failure_code     text        CHECK (failure_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  requested_by     uuid        NOT NULL,
  requested_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  finished_at      timestamptz,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, plan_version_id) REFERENCES app.plan_version (tenant_id, id),
  FOREIGN KEY (tenant_id, repository_id) REFERENCES app.repository (tenant_id, id),
  FOREIGN KEY (tenant_id, requested_by) REFERENCES app.app_user (tenant_id, id),
  CHECK ((status IN ('DONE', 'PARTIAL', 'FAILED')) = (finished_at IS NOT NULL))
);
CREATE INDEX plan_push_requirement_ix ON app.plan_push (tenant_id, requirement_id, requested_at DESC);
-- One push at a time per requirement and repository.
CREATE UNIQUE INDEX plan_push_one_open_uq ON app.plan_push (requirement_id, repository_id)
  WHERE status IN ('QUEUED', 'RUNNING');

-- What Garuda created in the repository, per requirement: a later push (or a later approved plan version with the
-- same story) updates the recorded issue or milestone.
CREATE TABLE app.plan_push_item (
  tenant_id        uuid        NOT NULL REFERENCES app.tenant (id),
  project_id       uuid        NOT NULL,
  requirement_id   uuid        NOT NULL,
  repository_id    uuid        NOT NULL,
  kind             text        NOT NULL CHECK (kind IN ('MILESTONE', 'ISSUE')),
  item_key         text        NOT NULL CHECK (item_key ~ '^[a-z0-9-]{1,80}$'),
  external_number  int         NOT NULL CHECK (external_number > 0),
  external_url     text        NOT NULL CHECK (external_url ~ '^https://' AND length(external_url) <= 500),
  last_push_id     uuid        NOT NULL,
  pushed_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (requirement_id, repository_id, kind, item_key),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, repository_id) REFERENCES app.repository (tenant_id, id),
  FOREIGN KEY (tenant_id, last_push_id) REFERENCES app.plan_push (tenant_id, id)
);

--changeset sdlc:0057-plan-push-guards splitStatements:false
-- rls: trigger functions only.
CREATE FUNCTION app.plan_push_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  member uuid := app.current_member();
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'plan pushes are not deleted' USING ERRCODE = 'P0001';
  END IF;
  IF TG_OP = 'INSERT' THEN
    IF member IS NULL OR member IS DISTINCT FROM NEW.requested_by OR NOT app.is_estimator(NEW.project_id) THEN
      RAISE EXCEPTION 'an Estimator of the project pushes its plan' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status <> 'QUEUED' OR NEW.items_created <> 0 OR NEW.items_updated <> 0 OR NEW.items_failed <> 0
       OR NEW.finished_at IS NOT NULL OR NEW.failure_code IS NOT NULL THEN
      RAISE EXCEPTION 'a plan push starts queued' USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM app.plan_version v WHERE v.id = NEW.plan_version_id
                   AND v.requirement_id = NEW.requirement_id AND v.project_id = NEW.project_id
                   AND v.status = 'APPROVED') THEN
      RAISE EXCEPTION 'only an approved delivery plan is pushed' USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM app.project_repository pr JOIN app.repository r ON r.id = pr.repository_id
                   WHERE pr.project_id = NEW.project_id AND pr.repository_id = NEW.repository_id
                     AND r.status = 'VERIFIED') THEN
      RAISE EXCEPTION 'a plan is pushed to a verified repository of its project' USING ERRCODE = 'P0001';
    END IF;
    NEW.requested_at := clock_timestamp();
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.project_id <> OLD.project_id
     OR NEW.requirement_id <> OLD.requirement_id OR NEW.plan_version_id <> OLD.plan_version_id
     OR NEW.repository_id <> OLD.repository_id OR NEW.payload <> OLD.payload OR NEW.items_total <> OLD.items_total
     OR NEW.requested_by <> OLD.requested_by OR NEW.requested_at <> OLD.requested_at THEN
    RAISE EXCEPTION 'plan push identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status IN ('DONE', 'PARTIAL', 'FAILED') THEN
    RAISE EXCEPTION 'a finished plan push is not changed' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status <> OLD.status AND NOT (
       (OLD.status = 'QUEUED' AND NEW.status IN ('RUNNING', 'FAILED'))
    OR (OLD.status = 'RUNNING' AND NEW.status IN ('DONE', 'PARTIAL', 'FAILED'))) THEN
    RAISE EXCEPTION 'plan push status % cannot become %', OLD.status, NEW.status USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.plan_push_guard() FROM PUBLIC;
CREATE TRIGGER plan_push_guard BEFORE INSERT OR UPDATE OR DELETE ON app.plan_push
  FOR EACH ROW EXECUTE FUNCTION app.plan_push_guard();

CREATE FUNCTION app.plan_push_item_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'pushed plan items are not deleted' USING ERRCODE = 'P0001';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM app.plan_push p WHERE p.id = NEW.last_push_id AND p.status = 'RUNNING'
                 AND p.requirement_id = NEW.requirement_id AND p.repository_id = NEW.repository_id
                 AND p.project_id = NEW.project_id) THEN
    RAISE EXCEPTION 'a pushed item is recorded by a running push of the same requirement and repository'
      USING ERRCODE = 'P0001';
  END IF;
  IF TG_OP = 'UPDATE' AND (NEW.tenant_id <> OLD.tenant_id OR NEW.project_id <> OLD.project_id
     OR NEW.requirement_id <> OLD.requirement_id OR NEW.repository_id <> OLD.repository_id
     OR NEW.kind <> OLD.kind OR NEW.item_key <> OLD.item_key) THEN
    RAISE EXCEPTION 'pushed plan item identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  NEW.pushed_at := clock_timestamp();
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.plan_push_item_guard() FROM PUBLIC;
CREATE TRIGGER plan_push_item_guard BEFORE INSERT OR UPDATE OR DELETE ON app.plan_push_item
  FOR EACH ROW EXECUTE FUNCTION app.plan_push_item_guard();

--changeset sdlc:0057-plan-push-rls splitStatements:false
-- rls: enables row-level security on the new tables.
ALTER TABLE app.plan_push ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.plan_push FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.plan_push
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());
CREATE POLICY plan_access ON app.plan_push AS RESTRICTIVE USING (app.sees_plan(project_id, plan_version_id));
ALTER TABLE app.plan_push_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.plan_push_item FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.plan_push_item
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());
CREATE POLICY project_access ON app.plan_push_item AS RESTRICTIVE
  USING (app.current_member() IS NULL OR app.member_sees_project(project_id));

--changeset sdlc:0057-plan-push-job-event splitStatements:false
-- rls: constraint changes and a job type row (not tenant data).
ALTER TABLE app.job_type DROP CONSTRAINT job_type_code_check;
ALTER TABLE app.job_type ADD CONSTRAINT job_type_code_check CHECK (code IN ('REPO_VALIDATE', 'DOCUMENT_SCAN',
  'DOCUMENT_EXTRACT', 'BA_ANALYSIS', 'REPO_VERIFY', 'REPO_SNAPSHOT', 'REPO_INDEX', 'BA_COMMIT_RESULT', 'SECURITY_SCAN',
  'ENV_VERIFY', 'ENV_PIN', 'TDD_GENERATION', 'ESTIMATE_GENERATION', 'PLAN_GENERATION', 'PLAN_PUSH'));
-- Pushing pauses between writes (GitHub's limits on content creation), so a large plan takes several minutes.
INSERT INTO app.job_type (code, max_attempts, max_infra_failures, backoff_base_seconds, backoff_cap_seconds,
                          lease_seconds, max_runtime_seconds, max_retry_seconds)
  VALUES ('PLAN_PUSH', 5, 10, 60, 1800, 300, 3600, 86400);
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
  'PlanPushRequested'));

--changeset sdlc:0057-plan-push-grants
-- rls: grants only.
GRANT SELECT ON app.plan_push, app.plan_push_item TO sdlc_app, sdlc_worker, sdlc_retention;
GRANT INSERT (tenant_id, project_id, requirement_id, plan_version_id, repository_id, payload, items_total,
              requested_by) ON app.plan_push TO sdlc_app;
GRANT UPDATE (status, items_created, items_updated, items_failed, failure_code, finished_at) ON app.plan_push
  TO sdlc_worker;
GRANT INSERT, UPDATE (external_number, external_url, last_push_id, pushed_at) ON app.plan_push_item TO sdlc_worker;
