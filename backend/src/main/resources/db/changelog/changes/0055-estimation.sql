--liquibase formatted sql

-- GD-33 (owner, 2026-11-20): an approved technical design moves the requirement to Estimation. A new project role,
-- Estimator, starts, reviews and adjusts the estimate; a BRD Approver approves it. Only Estimators and BRD Approvers
-- see estimate content. Design: docs/architecture/garuda-estimation-stage.md.

--changeset sdlc:0055-estimator-role splitStatements:false
-- rls: constraint and function changes only; no rows are read or changed.
ALTER TABLE app.project_member DROP CONSTRAINT project_member_roles_check;
ALTER TABLE app.project_member ADD CONSTRAINT project_member_roles_check CHECK (
  cardinality(roles) BETWEEN 1 AND 5
  AND roles <@ ARRAY['REQUIREMENT_AUTHOR', 'BRD_APPROVER', 'TECHNICAL_APPROVER', 'ESTIMATOR', 'VIEWER']::text[]
  AND (NOT 'VIEWER' = ANY (roles) OR cardinality(roles) = 1)
  AND app.distinct_elements(roles));

-- Estimators work from the approved design, so they see restricted requirements like the approvers do.
CREATE OR REPLACE FUNCTION app.member_sees_requirement(p_project uuid, p_restricted boolean, p_owner uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN EXISTS (SELECT 1 FROM app.project_member m WHERE m.project_id = p_project
                 AND m.user_id = app.current_member() AND m.removed_at IS NULL
                 AND (NOT p_restricted OR m.roles && ARRAY['BRD_APPROVER', 'TECHNICAL_APPROVER', 'ESTIMATOR']::text[]
                      OR p_owner = app.current_member()));

CREATE FUNCTION app.is_estimator(p_project uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN app.current_member() IS NOT NULL AND EXISTS (SELECT 1 FROM app.project_member m JOIN app.app_user u ON u.id = m.user_id
         WHERE m.project_id = p_project AND m.user_id = app.current_member() AND m.removed_at IS NULL
           AND 'ESTIMATOR' = ANY (m.roles) AND u.status = 'ACTIVE');
REVOKE ALL ON FUNCTION app.is_estimator(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.is_estimator(uuid) TO sdlc_app, sdlc_worker, sdlc_retention;

-- Estimate content is for Estimators and BRD Approvers only (the worker and staff run without a member).
CREATE FUNCTION app.sees_estimate(p_project uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN app.current_member() IS NULL OR app.is_estimator(p_project) OR app.is_brd_approver(p_project);
REVOKE ALL ON FUNCTION app.sees_estimate(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.sees_estimate(uuid) TO sdlc_app, sdlc_worker, sdlc_retention;

--changeset sdlc:0055-estimate-tables splitStatements:false
CREATE TABLE app.estimate_version (
  id              uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id       uuid        NOT NULL REFERENCES app.tenant (id),
  project_id      uuid        NOT NULL,
  requirement_id  uuid        NOT NULL,
  version_no      int         NOT NULL CHECK (version_no >= 1),
  tdd_version_id  uuid        NOT NULL,
  status          text        NOT NULL CHECK (status IN ('GENERATING', 'IN_REVIEW', 'APPROVED', 'SUPERSEDED',
                                                         'FAILED')),
  -- Team, calendar, contingency and optional day rates, frozen when the version is requested.
  settings        jsonb       NOT NULL CHECK (jsonb_typeof(settings) = 'object' AND octet_length(settings::text) <= 8000),
  assumptions     jsonb       NOT NULL DEFAULT '[]'::jsonb
                              CHECK (jsonb_typeof(assumptions) = 'array' AND octet_length(assumptions::text) <= 20000),
  excluded        jsonb       NOT NULL DEFAULT '[]'::jsonb
                              CHECK (jsonb_typeof(excluded) = 'array' AND octet_length(excluded::text) <= 20000),
  content_hash    text        CHECK (content_hash ~ '^[0-9a-f]{64}$'),
  model_provider  text,
  model_id        text,
  change_request  text        CHECK (length(change_request) BETWEEN 10 AND 5000),
  requested_by    uuid        NOT NULL,
  failure_code    text        CHECK (failure_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  attempts        int         NOT NULL DEFAULT 1 CHECK (attempts BETWEEN 1 AND 10),
  created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  status_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (requirement_id, version_no),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, tdd_version_id) REFERENCES app.tdd_version (tenant_id, id),
  FOREIGN KEY (tenant_id, requested_by) REFERENCES app.app_user (tenant_id, id),
  CHECK (status IN ('GENERATING', 'FAILED') OR content_hash IS NOT NULL)
);
CREATE UNIQUE INDEX estimate_version_one_open_uq ON app.estimate_version (requirement_id)
  WHERE status IN ('GENERATING', 'IN_REVIEW', 'FAILED');

CREATE TABLE app.estimate_item (
  id              uuid          NOT NULL DEFAULT gen_random_uuid(),
  tenant_id       uuid          NOT NULL REFERENCES app.tenant (id),
  project_id      uuid          NOT NULL,
  estimate_version_id uuid      NOT NULL,
  ordinal         int           NOT NULL CHECK (ordinal >= 1),
  item_key        text          NOT NULL CHECK (item_key ~ '^[a-z0-9-]{1,80}$'),
  title           text          NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
  detail          text          NOT NULL CHECK (length(detail) BETWEEN 1 AND 1000),
  role            text          NOT NULL CHECK (role IN ('FRONTEND', 'BACKEND', 'DATA', 'DEVOPS', 'QA', 'DESIGN', 'OTHER')),
  phase           text          NOT NULL CHECK (phase IN ('SETUP', 'BUILD', 'TEST', 'HARDENING', 'RELEASE')),
  design_refs     jsonb         NOT NULL DEFAULT '[]'::jsonb
                                CHECK (jsonb_typeof(design_refs) = 'array' AND octet_length(design_refs::text) <= 8000),
  -- As proposed by the agent (after the engine's sanity clipping); adjustments are separate rows.
  low_days        numeric(6,2)  NOT NULL CHECK (low_days > 0),
  likely_days     numeric(6,2)  NOT NULL CHECK (likely_days >= low_days),
  high_days       numeric(6,2)  NOT NULL CHECK (high_days >= likely_days),
  assumptions     jsonb         NOT NULL DEFAULT '[]'::jsonb
                                CHECK (jsonb_typeof(assumptions) = 'array' AND octet_length(assumptions::text) <= 8000),
  risk            text          CHECK (length(risk) <= 1000),
  depends_on      jsonb         NOT NULL DEFAULT '[]'::jsonb
                                CHECK (jsonb_typeof(depends_on) = 'array' AND octet_length(depends_on::text) <= 8000),
  -- Engine flags: CLIPPED (figures out of range), UNCERTAIN (wide range), LARGE (should be split).
  flags           jsonb         NOT NULL DEFAULT '[]'::jsonb
                                CHECK (jsonb_typeof(flags) = 'array' AND octet_length(flags::text) <= 1000),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (estimate_version_id, ordinal),
  UNIQUE (estimate_version_id, item_key),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, estimate_version_id) REFERENCES app.estimate_version (tenant_id, id)
);

CREATE TABLE app.estimate_adjustment (
  id              uuid          NOT NULL DEFAULT gen_random_uuid(),
  tenant_id       uuid          NOT NULL REFERENCES app.tenant (id),
  project_id      uuid          NOT NULL,
  estimate_version_id uuid      NOT NULL,
  item_key        text          NOT NULL CHECK (item_key ~ '^[a-z0-9-]{1,80}$'),
  low_days        numeric(6,2)  NOT NULL CHECK (low_days > 0),
  likely_days     numeric(6,2)  NOT NULL CHECK (likely_days >= low_days),
  high_days       numeric(6,2)  NOT NULL CHECK (high_days >= likely_days AND high_days <= 999),
  reason          text          NOT NULL CHECK (length(reason) BETWEEN 10 AND 1000),
  adjusted_by     uuid          NOT NULL,
  adjusted_at     timestamptz   NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, estimate_version_id) REFERENCES app.estimate_version (tenant_id, id),
  FOREIGN KEY (tenant_id, adjusted_by) REFERENCES app.app_user (tenant_id, id)
);
CREATE INDEX estimate_adjustment_version_ix ON app.estimate_adjustment (tenant_id, estimate_version_id, item_key);

CREATE TABLE app.estimate_decision (
  id              uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id       uuid        NOT NULL REFERENCES app.tenant (id),
  project_id      uuid        NOT NULL,
  requirement_id  uuid        NOT NULL,
  estimate_version_id uuid    NOT NULL,
  -- FLAG_ACCEPTED: a flagged work item kept as is; GAP_ACCEPTED: a design item knowingly left unestimated.
  kind            text        NOT NULL CHECK (kind IN ('FLAG_ACCEPTED', 'GAP_ACCEPTED', 'APPROVED', 'CHANGES_REQUESTED')),
  item_key        text        CHECK (length(item_key) <= 100),
  note            text        CHECK (length(note) <= 5000),
  decided_by      uuid        NOT NULL,
  decided_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, estimate_version_id) REFERENCES app.estimate_version (tenant_id, id),
  FOREIGN KEY (tenant_id, decided_by) REFERENCES app.app_user (tenant_id, id),
  CHECK ((kind IN ('FLAG_ACCEPTED', 'GAP_ACCEPTED')) = (item_key IS NOT NULL)),
  CHECK (kind <> 'CHANGES_REQUESTED' OR length(note) >= 10),
  CHECK (kind <> 'APPROVED' OR note IS NOT NULL)
);
CREATE INDEX estimate_decision_version_ix ON app.estimate_decision (tenant_id, estimate_version_id, kind);
CREATE UNIQUE INDEX estimate_decision_once_uq ON app.estimate_decision (estimate_version_id, kind, coalesce(item_key, ''))
  WHERE kind <> 'CHANGES_REQUESTED';

CREATE TABLE app.project_estimate_defaults (
  project_id      uuid        NOT NULL,
  tenant_id       uuid        NOT NULL REFERENCES app.tenant (id),
  settings        jsonb       NOT NULL CHECK (jsonb_typeof(settings) = 'object' AND octet_length(settings::text) <= 8000),
  updated_by      uuid        NOT NULL,
  updated_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (project_id),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, updated_by) REFERENCES app.app_user (tenant_id, id)
);

--changeset sdlc:0055-estimate-guards splitStatements:false
-- rls: trigger functions only.
CREATE FUNCTION app.estimate_version_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  member uuid := app.current_member();
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'estimate versions are not deleted' USING ERRCODE = 'P0001';
  END IF;
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'GENERATING' OR NEW.content_hash IS NOT NULL THEN
      RAISE EXCEPTION 'an estimate version starts empty, generating' USING ERRCODE = 'P0001';
    END IF;
    IF member IS NULL OR member IS DISTINCT FROM NEW.requested_by
       OR NOT (app.is_estimator(NEW.project_id)
               OR (app.is_brd_approver(NEW.project_id) AND NEW.change_request IS NOT NULL)) THEN
      RAISE EXCEPTION 'an estimate is started by an Estimator of the project' USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM app.tdd_version t WHERE t.id = NEW.tdd_version_id
                   AND t.requirement_id = NEW.requirement_id AND t.status = 'APPROVED') THEN
      RAISE EXCEPTION 'an estimate is made from the approved technical design' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.version_no <> coalesce((SELECT max(version_no) FROM app.estimate_version
                                   WHERE requirement_id = NEW.requirement_id), 0) + 1 THEN
      RAISE EXCEPTION 'estimate versions are numbered in order' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.requirement_id <> OLD.requirement_id
     OR NEW.project_id <> OLD.project_id OR NEW.version_no <> OLD.version_no OR NEW.tdd_version_id <> OLD.tdd_version_id
     OR NEW.requested_by <> OLD.requested_by OR NEW.change_request IS DISTINCT FROM OLD.change_request
     OR NEW.settings <> OLD.settings OR NEW.created_at <> OLD.created_at THEN
    RAISE EXCEPTION 'estimate version identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status <> 'GENERATING' AND (NEW.assumptions IS DISTINCT FROM OLD.assumptions
     OR NEW.excluded IS DISTINCT FROM OLD.excluded OR NEW.content_hash IS DISTINCT FROM OLD.content_hash
     OR NEW.model_provider IS DISTINCT FROM OLD.model_provider OR NEW.model_id IS DISTINCT FROM OLD.model_id) THEN
    RAISE EXCEPTION 'estimate content is immutable once produced' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status <> OLD.status AND NOT (
       (OLD.status = 'GENERATING' AND NEW.status IN ('IN_REVIEW', 'FAILED'))
    OR (OLD.status = 'FAILED' AND NEW.status = 'GENERATING')
    OR (OLD.status = 'IN_REVIEW' AND NEW.status = 'SUPERSEDED')
    OR (OLD.status = 'IN_REVIEW' AND NEW.status = 'APPROVED'
        AND EXISTS (SELECT 1 FROM app.estimate_decision d WHERE d.estimate_version_id = OLD.id AND d.kind = 'APPROVED'))
    OR (OLD.status = 'APPROVED' AND NEW.status = 'SUPERSEDED')) THEN
    RAISE EXCEPTION 'estimate status % cannot become %', OLD.status, NEW.status USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status <> OLD.status THEN
    NEW.status_at := clock_timestamp();
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.estimate_version_guard() FROM PUBLIC;
CREATE TRIGGER estimate_version_guard BEFORE INSERT OR UPDATE OR DELETE ON app.estimate_version
  FOR EACH ROW EXECUTE FUNCTION app.estimate_version_guard();

CREATE FUNCTION app.estimate_item_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'estimate items are immutable; adjustments are recorded separately' USING ERRCODE = 'P0001';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM app.estimate_version v WHERE v.id = NEW.estimate_version_id
                 AND v.project_id = NEW.project_id AND v.status = 'GENERATING') THEN
    RAISE EXCEPTION 'estimate items are written only while the estimate is generated' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.estimate_item_guard() FROM PUBLIC;
CREATE TRIGGER estimate_item_guard BEFORE INSERT OR UPDATE OR DELETE ON app.estimate_item
  FOR EACH ROW EXECUTE FUNCTION app.estimate_item_guard();

CREATE FUNCTION app.estimate_adjustment_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  member uuid := app.current_member();
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'estimate adjustments are append-only' USING ERRCODE = 'P0001';
  END IF;
  IF member IS NULL OR member IS DISTINCT FROM NEW.adjusted_by OR NOT app.is_estimator(NEW.project_id) THEN
    RAISE EXCEPTION 'an Estimator of the project adjusts the estimate' USING ERRCODE = 'P0001';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM app.estimate_item i JOIN app.estimate_version v ON v.id = i.estimate_version_id
                 WHERE v.id = NEW.estimate_version_id AND v.project_id = NEW.project_id AND v.status = 'IN_REVIEW'
                   AND i.item_key = NEW.item_key) THEN
    RAISE EXCEPTION 'only a work item of an estimate in review can be adjusted' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.estimate_adjustment_guard() FROM PUBLIC;
CREATE TRIGGER estimate_adjustment_guard BEFORE INSERT OR UPDATE OR DELETE ON app.estimate_adjustment
  FOR EACH ROW EXECUTE FUNCTION app.estimate_adjustment_guard();

CREATE FUNCTION app.estimate_decision_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  member uuid := app.current_member();
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'estimate decisions are append-only' USING ERRCODE = 'P0001';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM app.estimate_version v WHERE v.id = NEW.estimate_version_id
                 AND v.requirement_id = NEW.requirement_id AND v.project_id = NEW.project_id
                 AND v.status = 'IN_REVIEW') THEN
    RAISE EXCEPTION 'only an estimate in review can be decided' USING ERRCODE = 'P0001';
  END IF;
  IF member IS NULL OR member IS DISTINCT FROM NEW.decided_by THEN
    RAISE EXCEPTION 'an estimate decision is made by the signed-in member' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.kind IN ('FLAG_ACCEPTED', 'GAP_ACCEPTED') AND NOT app.is_estimator(NEW.project_id) THEN
    RAISE EXCEPTION 'flagged items and coverage gaps are decided by an Estimator' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.kind = 'APPROVED' AND NOT app.is_brd_approver(NEW.project_id) THEN
    RAISE EXCEPTION 'a BRD Approver approves the estimate' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.kind = 'CHANGES_REQUESTED' AND NOT (app.is_estimator(NEW.project_id) OR app.is_brd_approver(NEW.project_id)) THEN
    RAISE EXCEPTION 'changes are requested by an Estimator or a BRD Approver' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.estimate_decision_guard() FROM PUBLIC;
CREATE TRIGGER estimate_decision_guard BEFORE INSERT OR UPDATE OR DELETE ON app.estimate_decision
  FOR EACH ROW EXECUTE FUNCTION app.estimate_decision_guard();

CREATE FUNCTION app.estimate_defaults_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  member uuid := app.current_member();
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'estimate defaults are not deleted' USING ERRCODE = 'P0001';
  END IF;
  IF member IS NULL OR member IS DISTINCT FROM NEW.updated_by OR NOT app.is_estimator(NEW.project_id) THEN
    RAISE EXCEPTION 'an Estimator of the project sets its estimate defaults' USING ERRCODE = 'P0001';
  END IF;
  NEW.updated_at := clock_timestamp();
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.estimate_defaults_guard() FROM PUBLIC;
CREATE TRIGGER estimate_defaults_guard BEFORE INSERT OR UPDATE OR DELETE ON app.project_estimate_defaults
  FOR EACH ROW EXECUTE FUNCTION app.estimate_defaults_guard();

--changeset sdlc:0055-estimate-rls splitStatements:false
-- rls: enables row-level security on the new tables.
ALTER TABLE app.estimate_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.estimate_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.estimate_version
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());
CREATE POLICY project_access ON app.estimate_version AS RESTRICTIVE
  USING (app.current_member() IS NULL OR app.member_sees_project(project_id));
DO $rls$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['estimate_item', 'estimate_adjustment', 'estimate_decision', 'project_estimate_defaults'] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON app.%I USING (tenant_id = app.current_tenant()) '
                   'WITH CHECK (tenant_id = app.current_tenant())', t);
    -- GD-33: estimate content only for the project's Estimators and BRD Approvers.
    EXECUTE format('CREATE POLICY estimate_access ON app.%I AS RESTRICTIVE USING (app.sees_estimate(project_id))', t);
  END LOOP;
END
$rls$;

--changeset sdlc:0055-estimate-job-event splitStatements:false
-- rls: constraint changes and a job type row (not tenant data).
ALTER TABLE app.job_type DROP CONSTRAINT job_type_code_check;
ALTER TABLE app.job_type ADD CONSTRAINT job_type_code_check CHECK (code IN ('REPO_VALIDATE', 'DOCUMENT_SCAN',
  'DOCUMENT_EXTRACT', 'BA_ANALYSIS', 'REPO_VERIFY', 'REPO_SNAPSHOT', 'REPO_INDEX', 'BA_COMMIT_RESULT', 'SECURITY_SCAN',
  'ENV_VERIFY', 'ENV_PIN', 'TDD_GENERATION', 'ESTIMATE_GENERATION'));
INSERT INTO app.job_type (code, max_attempts, max_infra_failures, backoff_base_seconds, backoff_cap_seconds,
                          lease_seconds, max_runtime_seconds, max_retry_seconds)
  VALUES ('ESTIMATE_GENERATION', 3, 10, 30, 900, 300, 1800, 86400);
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
  'SourcesRoundOpened', 'TddRequested', 'EstimateRequested', 'TddApproved'));
ALTER TABLE app.analysis_activity DROP CONSTRAINT analysis_activity_stage_check;
ALTER TABLE app.analysis_activity ADD CONSTRAINT analysis_activity_stage_check
  CHECK (stage IN ('BRD', 'DESIGN', 'ESTIMATE'));

--changeset sdlc:0055-estimation-prompt
-- rls: inserts a prompt registry row (not a tenant table).
INSERT INTO app.prompt_version (prompt_id, version, agent, text_sha256, schema_version, status) VALUES
  ('est.items', 1, 'ESTIMATOR', '968a45f74168b4160cb1ecf74fded9f63763391e860c268f0d7ea9c89f54ddb0', 1, 'ACTIVE');

--changeset sdlc:0055-estimate-grants
-- rls: grants only.
GRANT SELECT ON app.estimate_version, app.estimate_item, app.estimate_adjustment, app.estimate_decision,
  app.project_estimate_defaults TO sdlc_app, sdlc_worker, sdlc_retention;
GRANT INSERT (tenant_id, project_id, requirement_id, version_no, tdd_version_id, status, settings, change_request,
              requested_by) ON app.estimate_version TO sdlc_app;
GRANT UPDATE (status, attempts, failure_code) ON app.estimate_version TO sdlc_app;
GRANT INSERT ON app.estimate_adjustment, app.estimate_decision TO sdlc_app;
GRANT INSERT, UPDATE ON app.project_estimate_defaults TO sdlc_app;
GRANT UPDATE (status, assumptions, excluded, content_hash, model_provider, model_id, failure_code)
  ON app.estimate_version TO sdlc_worker;
GRANT INSERT ON app.estimate_item TO sdlc_worker;
