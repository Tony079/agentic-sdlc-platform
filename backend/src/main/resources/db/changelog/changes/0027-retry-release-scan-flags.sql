--liquibase formatted sql

--changeset sdlc:0027-retry-policy
-- O-27 (owner decision 2026-11-08): bounded retries. Besides the attempt limit, each job type has a maximum retry
-- window measured from the job's creation; a retryable failure after that window ends the job DEAD.
ALTER TABLE app.job_type ADD COLUMN max_retry_seconds int NOT NULL DEFAULT 86400
  CHECK (max_retry_seconds BETWEEN 60 AND 604800);
UPDATE app.job_type SET max_retry_seconds = 21600 WHERE code IN ('DOCUMENT_SCAN', 'DOCUMENT_EXTRACT', 'REPO_VALIDATE');
ALTER TABLE app.job DROP CONSTRAINT job_dead_reason_check;
ALTER TABLE app.job ADD CONSTRAINT job_dead_reason_check
  CHECK (dead_reason IN ('RETRIES_EXHAUSTED', 'FATAL_FAILURE', 'INFRA_FAILURES', 'RETRY_WINDOW_EXCEEDED'));
GRANT SELECT ON app.job_type TO sdlc_worker;

--changeset sdlc:0027-admin-retry
-- Operator retry after review (O-27, PRD T26): a staff member with JOB_RETRY in the active tenant asks for one DEAD
-- BA job of a FAILED requirement to be retried. The worker re-validates (request pending, requirement FAILED, tenant
-- ACTIVE, the job still dead and not a permanent failure) and performs T26 with a new input set, or rejects it.
-- Only BA jobs (TDD O-27); repository-validation failures keep no retry path (PC-01 not approved).
CREATE TABLE app.admin_retry_request (
  id             uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id      uuid        NOT NULL,
  job_id         uuid        NOT NULL,
  requirement_id uuid        NOT NULL,
  requested_by   uuid        NOT NULL REFERENCES app.staff_user (id),
  reason         text        NOT NULL CHECK (length(reason) BETWEEN 10 AND 1000),
  status         text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'COMPLETED', 'REJECTED')),
  outcome_code   text        CHECK (outcome_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  requested_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  decided_at     timestamptz,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, job_id) REFERENCES app.job (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  CHECK ((status = 'PENDING') = (decided_at IS NULL))
);
CREATE UNIQUE INDEX admin_retry_request_pending_uq ON app.admin_retry_request (tenant_id, job_id) WHERE status = 'PENDING';
ALTER TABLE app.admin_retry_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.admin_retry_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.admin_retry_request
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());
GRANT SELECT, INSERT ON app.admin_retry_request TO sdlc_app;
GRANT SELECT ON app.admin_retry_request TO sdlc_worker;
GRANT UPDATE (status, outcome_code, decided_at) ON app.admin_retry_request TO sdlc_worker;
-- Operators review failures without the API getting queue access (Q-M3-3): a view owned by the schema owner exposes
-- only DEAD jobs and content-free columns (no payload, no lease data). Forced RLS still applies to the owner, so the
-- view shows the current tenant's rows only.
CREATE VIEW app.dead_job WITH (security_barrier = true) AS
  SELECT id, tenant_id, job_type, aggregate_type, aggregate_id, attempt_count, retryable_failure_count,
         last_error_code, dead_reason, created_at, finished_at
  FROM app.job WHERE status = 'DEAD';
GRANT SELECT ON app.dead_job TO sdlc_app;

ALTER TABLE app.workflow_transition DROP CONSTRAINT workflow_transition_transition_check;
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_transition_check
  CHECK (transition IN ('CREATE', 'T01_SUBMIT', 'T02_ROUTE_REPOSITORY', 'T03_ROUTE_MANUAL_INTAKE', 'T04_RESUME_MANUAL_INTAKE',
                        'T05_CLOSE_MANUAL_INTAKE',
                        'T06_REPO_VALIDATED', 'T07_REPO_VALIDATED_DOCUMENT_DECISION', 'T09_REPO_VALIDATION_FAILED',
                        'T10_DOCUMENT_DECISION_NEEDED', 'T11_START_ANALYSIS', 'T12_DOCUMENT_DECISION_RECORDED',
                        'T13_START_ANALYSIS_AFTER_DECISION', 'T14_ANALYSIS_STALE_WAIT', 'T15_ANALYSIS_RESTART',
                        'T16_AWAIT_CLARIFICATION', 'T17_RESUME_ANALYSIS',
                        'T18_BRD_DRAFTED', 'T19_ANALYSIS_FAILED', 'T20_QUEUE_REVIEW',
                        'T21_APPROVE_INTERNAL', 'T22_REQUEST_REVISION', 'T23_REJECT', 'T24_REANALYSE_REVISION',
                        'T25_RELEASE_TO_CLIENT', 'T26_ADMIN_RETRY', 'T27_CLIENT_CHANGES', 'T28_REANALYSE_CLIENT_CHANGES', 'T29_CANCEL'));
ALTER TABLE app.workflow_transition DROP CONSTRAINT workflow_transition_legal_pair_check;
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_legal_pair_check
  CHECK (from_state IS NULL
      OR (from_state, to_state, transition) IN (('DRAFT', 'SUBMITTED', 'T01_SUBMIT'),
                                                ('SUBMITTED', 'REPO_VALIDATING', 'T02_ROUTE_REPOSITORY'),
                                                ('SUBMITTED', 'MANUAL_INTAKE', 'T03_ROUTE_MANUAL_INTAKE'),
                                                ('MANUAL_INTAKE', 'REPO_VALIDATING', 'T04_RESUME_MANUAL_INTAKE'),
                                                ('MANUAL_INTAKE', 'CANCELLED', 'T05_CLOSE_MANUAL_INTAKE'),
                                                ('REPO_VALIDATING', 'AWAITING_DOCUMENTS', 'T06_REPO_VALIDATED'),
                                                ('REPO_VALIDATING', 'AWAITING_DOCUMENT_DECISION',
                                                 'T07_REPO_VALIDATED_DOCUMENT_DECISION'),
                                                ('REPO_VALIDATING', 'FAILED', 'T09_REPO_VALIDATION_FAILED'),
                                                ('AWAITING_DOCUMENTS', 'AWAITING_DOCUMENT_DECISION',
                                                 'T10_DOCUMENT_DECISION_NEEDED'),
                                                ('AWAITING_DOCUMENTS', 'ANALYSING', 'T11_START_ANALYSIS'),
                                                ('AWAITING_DOCUMENT_DECISION', 'AWAITING_DOCUMENTS',
                                                 'T12_DOCUMENT_DECISION_RECORDED'),
                                                ('AWAITING_DOCUMENT_DECISION', 'ANALYSING',
                                                 'T13_START_ANALYSIS_AFTER_DECISION'),
                                                ('ANALYSING', 'AWAITING_DOCUMENTS', 'T14_ANALYSIS_STALE_WAIT'),
                                                ('ANALYSING', 'ANALYSING', 'T15_ANALYSIS_RESTART'),
                                                ('ANALYSING', 'AWAITING_CLARIFICATION', 'T16_AWAIT_CLARIFICATION'),
                                                ('AWAITING_CLARIFICATION', 'ANALYSING', 'T17_RESUME_ANALYSIS'),
                                                ('ANALYSING', 'BRD_DRAFTED', 'T18_BRD_DRAFTED'),
                                                ('ANALYSING', 'FAILED', 'T19_ANALYSIS_FAILED'),
                                                ('BRD_DRAFTED', 'INTERNAL_REVIEW', 'T20_QUEUE_REVIEW'),
                                                ('INTERNAL_REVIEW', 'INTERNAL_APPROVED', 'T21_APPROVE_INTERNAL'),
                                                ('INTERNAL_REVIEW', 'REVISION_REQUESTED', 'T22_REQUEST_REVISION'),
                                                ('INTERNAL_REVIEW', 'REJECTED', 'T23_REJECT'),
                                                ('REVISION_REQUESTED', 'ANALYSING', 'T24_REANALYSE_REVISION'),
                                                ('INTERNAL_APPROVED', 'SHARED_WITH_CLIENT', 'T25_RELEASE_TO_CLIENT'),
                                                ('FAILED', 'ANALYSING', 'T26_ADMIN_RETRY'),
                                                ('SHARED_WITH_CLIENT', 'CLIENT_CHANGES_REQUESTED', 'T27_CLIENT_CHANGES'),
                                                ('CLIENT_CHANGES_REQUESTED', 'ANALYSING', 'T28_REANALYSE_CLIENT_CHANGES'),
                                                ('DRAFT', 'CANCELLED', 'T29_CANCEL'),
                                                ('SUBMITTED', 'CANCELLED', 'T29_CANCEL'),
                                                ('MANUAL_INTAKE', 'CANCELLED', 'T29_CANCEL'),
                                                ('REPO_VALIDATING', 'CANCELLED', 'T29_CANCEL'),
                                                ('AWAITING_DOCUMENTS', 'CANCELLED', 'T29_CANCEL'),
                                                ('AWAITING_DOCUMENT_DECISION', 'CANCELLED', 'T29_CANCEL'),
                                                ('FAILED', 'CANCELLED', 'T29_CANCEL'),
                                                ('ANALYSING', 'CANCELLED', 'T29_CANCEL'),
                                                ('AWAITING_CLARIFICATION', 'CANCELLED', 'T29_CANCEL'),
                                                ('BRD_DRAFTED', 'CANCELLED', 'T29_CANCEL'),
                                                ('INTERNAL_REVIEW', 'CANCELLED', 'T29_CANCEL'),
                                                ('REVISION_REQUESTED', 'CANCELLED', 'T29_CANCEL'),
                                                ('INTERNAL_APPROVED', 'CANCELLED', 'T29_CANCEL'),
                                                ('SHARED_WITH_CLIENT', 'CANCELLED', 'T29_CANCEL'),
                                                ('CLIENT_CHANGES_REQUESTED', 'CANCELLED', 'T29_CANCEL')));
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_retry_actor_check
  CHECK (transition <> 'T26_ADMIN_RETRY' OR (actor_type = 'SYSTEM' AND actor_id IS NULL));
UPDATE app.workflow_definition SET transitions = transitions || '[
  {"transition": "T26_ADMIN_RETRY", "from": "FAILED", "to": "ANALYSING", "actor": "ENGINE"}
]'::jsonb WHERE version = 'R1-v1';

--changeset sdlc:0027-release-approval splitStatements:false
-- O-16 (owner decision 2026-11-08): manual release. A Release Manager (RELEASE_APPROVE) approves the release of one
-- internally approved BRD version, bound to its content hash and the pinned repository commit, after every mandatory
-- gate passed; the approver cannot be the internal approver or a correction author of that version. The release itself
-- (T25) is a separate operation performed by the worker, which re-checks the gates. No automatic release.
CREATE TABLE app.release_approval (
  id             uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id      uuid        NOT NULL,
  brd_version_id uuid        NOT NULL,
  requirement_id uuid        NOT NULL,
  release_label  text        NOT NULL CHECK (release_label ~ '^[A-Za-z0-9._-]{1,64}$'),
  content_hash   text        NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
  commit_sha     text        NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
  approver_staff_id uuid     NOT NULL REFERENCES app.staff_user (id),
  reason         text        NOT NULL CHECK (length(reason) BETWEEN 10 AND 2000),
  evidence       jsonb       NOT NULL CHECK (jsonb_typeof(evidence) = 'array' AND octet_length(evidence::text) <= 65536),
  status         text        NOT NULL DEFAULT 'APPROVED' CHECK (status IN ('APPROVED', 'EXECUTED', 'INVALIDATED')),
  approved_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  executed_at    timestamptz,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, brd_version_id) REFERENCES app.brd_version (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  CHECK ((status = 'EXECUTED') = (executed_at IS NOT NULL))
);
CREATE UNIQUE INDEX release_approval_one_live_uq ON app.release_approval (tenant_id, brd_version_id)
  WHERE status IN ('APPROVED', 'EXECUTED');
ALTER TABLE app.release_approval ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.release_approval FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.release_approval
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());

-- Separation of duties in the database: the release approver is neither the internal approver of the version nor the
-- author of a correction note in its input set, and holds RELEASE_APPROVE.
CREATE FUNCTION app.release_approval_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF NEW.brd_version_id <> OLD.brd_version_id OR NEW.content_hash <> OLD.content_hash
       OR NEW.commit_sha <> OLD.commit_sha OR NEW.approver_staff_id <> OLD.approver_staff_id THEN
      RAISE EXCEPTION 'release approval % is immutable', OLD.id USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status <> 'APPROVED' THEN
      RAISE EXCEPTION 'release approval % is final', OLD.id USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM app.staff_capability_grant g WHERE g.staff_id = NEW.approver_staff_id
                 AND g.capability = 'RELEASE_APPROVE' AND g.revoked_at IS NULL) THEN
    RAISE EXCEPTION 'the release approver needs RELEASE_APPROVE' USING ERRCODE = 'P0001';
  END IF;
  IF EXISTS (SELECT 1 FROM app.approval a WHERE a.brd_version_id = NEW.brd_version_id
             AND a.approver_staff_id = NEW.approver_staff_id) THEN
    RAISE EXCEPTION 'the internal approver cannot also approve the release' USING ERRCODE = 'P0001';
  END IF;
  IF EXISTS (SELECT 1 FROM app.brd_version v JOIN app.analysis_input_set s ON s.id = v.input_set_id,
                  jsonb_array_elements(s.corrections) c
             WHERE v.id = NEW.brd_version_id AND c ->> 'authorStaffId' = NEW.approver_staff_id::text) THEN
    RAISE EXCEPTION 'an author of this version cannot approve its release' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.release_approval_guard() FROM PUBLIC;
CREATE TRIGGER release_approval_guard BEFORE INSERT OR UPDATE ON app.release_approval
  FOR EACH ROW EXECUTE FUNCTION app.release_approval_guard();
GRANT SELECT, INSERT ON app.release_approval TO sdlc_app;
GRANT SELECT ON app.release_approval TO sdlc_worker, sdlc_retention;
GRANT UPDATE (status, executed_at) ON app.release_approval TO sdlc_worker;

--changeset sdlc:0027-security-scan splitStatements:false
-- O-03 (owner decision 2026-11-08): provider-neutral security scanning of the pinned repository snapshot (static, no
-- code execution). Each run records provider, version, kind, target commit, status and normalised findings. A run that
-- did not execute is UNAVAILABLE or FAILED, never COMPLETED. Evidence is masked; secret values are never stored.
CREATE TABLE app.security_scan_run (
  id               uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id        uuid        NOT NULL,
  snapshot_id      uuid        NOT NULL,
  commit_sha       text        NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
  kind             text        NOT NULL CHECK (kind IN ('DEPENDENCY', 'SECRET', 'SAST')),
  provider         text        NOT NULL CHECK (provider ~ '^[a-z][a-z0-9_.-]{1,63}$'),
  provider_version text        NOT NULL CHECK (length(provider_version) BETWEEN 1 AND 64),
  status           text        NOT NULL CHECK (status IN ('COMPLETED', 'FAILED', 'UNAVAILABLE')),
  error_code       text        CHECK (error_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  files_scanned    int         NOT NULL DEFAULT 0 CHECK (files_scanned >= 0),
  started_at       timestamptz NOT NULL,
  finished_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, snapshot_id) REFERENCES app.repository_snapshot (tenant_id, id),
  CHECK ((status = 'COMPLETED') = (error_code IS NULL))
);
CREATE INDEX security_scan_run_snapshot_ix ON app.security_scan_run (tenant_id, snapshot_id, kind);
CREATE TABLE app.security_finding (
  id          uuid    NOT NULL DEFAULT gen_random_uuid(),
  tenant_id   uuid    NOT NULL,
  scan_run_id uuid    NOT NULL,
  severity    text    NOT NULL CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO')),
  rule_id     text    NOT NULL CHECK (rule_id ~ '^[A-Za-z0-9._:-]{1,100}$'),
  category    text    NOT NULL CHECK (length(category) BETWEEN 1 AND 100),
  path        text    CHECK (length(path) <= 1024),
  line        int     CHECK (line >= 1),
  evidence    text    CHECK (length(evidence) <= 200),
  fingerprint text    CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
  PRIMARY KEY (id),
  FOREIGN KEY (tenant_id, scan_run_id) REFERENCES app.security_scan_run (tenant_id, id)
);
CREATE INDEX security_finding_run_ix ON app.security_finding (tenant_id, scan_run_id);
DO $rls$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['security_scan_run', 'security_finding'] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON app.%I USING (tenant_id = app.current_tenant()) '
                   'WITH CHECK (tenant_id = app.current_tenant())', t);
  END LOOP;
END
$rls$;
GRANT SELECT, INSERT ON app.security_scan_run, app.security_finding TO sdlc_worker;
GRANT SELECT ON app.security_scan_run, app.security_finding TO sdlc_app;
GRANT SELECT, DELETE ON app.security_finding TO sdlc_retention;
ALTER TABLE app.job_type DROP CONSTRAINT job_type_code_check;
ALTER TABLE app.job_type ADD CONSTRAINT job_type_code_check CHECK (code IN ('REPO_VALIDATE', 'DOCUMENT_SCAN',
  'DOCUMENT_EXTRACT', 'BA_ANALYSIS', 'REPO_VERIFY', 'REPO_SNAPSHOT', 'REPO_INDEX', 'BA_COMMIT_RESULT', 'SECURITY_SCAN'));
INSERT INTO app.job_type (code, max_attempts, max_infra_failures, backoff_base_seconds, backoff_cap_seconds,
                          lease_seconds, max_runtime_seconds, max_retry_seconds)
  VALUES ('SECURITY_SCAN', 3, 5, 30, 600, 300, 900, 21600);

--changeset sdlc:0027-feature-flags splitStatements:false
-- O-08 (owner decision 2026-11-08, feature-flag acknowledgement): every change of a registered flag records name,
-- environment, previous and new value, reason, actor and time; a different operator holding FEATURE_FLAG_ACK
-- acknowledges it. Protected flags (security and release-gate controls) cannot be changed through this path at all.
CREATE TABLE app.feature_flag (
  name        text        NOT NULL CHECK (name ~ '^[a-z][a-z0-9_.-]{1,63}$'),
  environment text        NOT NULL CHECK (environment ~ '^[a-z][a-z0-9-]{1,31}$'),
  value       text        NOT NULL CHECK (length(value) <= 200),
  protected   boolean     NOT NULL DEFAULT false,
  description text        NOT NULL CHECK (length(description) BETWEEN 1 AND 500),
  updated_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (name, environment)
);
CREATE TABLE app.feature_flag_change (
  id              uuid        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  name            text        NOT NULL,
  environment     text        NOT NULL,
  previous_value  text        NOT NULL,
  new_value       text        NOT NULL,
  reason          text        NOT NULL CHECK (length(reason) BETWEEN 10 AND 1000),
  changed_by      uuid        NOT NULL REFERENCES app.staff_user (id),
  changed_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  acknowledged_by uuid        REFERENCES app.staff_user (id),
  acknowledged_at timestamptz,
  FOREIGN KEY (name, environment) REFERENCES app.feature_flag (name, environment),
  CHECK (acknowledged_by IS NULL OR acknowledged_by <> changed_by),
  CHECK ((acknowledged_by IS NULL) = (acknowledged_at IS NULL)),
  CHECK (previous_value <> new_value)
);
CREATE FUNCTION app.feature_flag_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF OLD.protected OR NEW.protected <> OLD.protected OR NEW.name <> OLD.name OR NEW.environment <> OLD.environment THEN
    RAISE EXCEPTION 'flag % is protected or its identity is immutable', OLD.name USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.feature_flag_guard() FROM PUBLIC;
CREATE TRIGGER feature_flag_guard BEFORE UPDATE ON app.feature_flag FOR EACH ROW EXECUTE FUNCTION app.feature_flag_guard();
CREATE FUNCTION app.feature_flag_change_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF NEW.id <> OLD.id OR NEW.name <> OLD.name OR NEW.environment <> OLD.environment
     OR NEW.previous_value <> OLD.previous_value OR NEW.new_value <> OLD.new_value OR NEW.reason <> OLD.reason
     OR NEW.changed_by <> OLD.changed_by OR NEW.changed_at <> OLD.changed_at OR OLD.acknowledged_by IS NOT NULL THEN
    RAISE EXCEPTION 'a flag change is immutable and acknowledged once' USING ERRCODE = 'P0001';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM app.staff_capability_grant g WHERE g.staff_id = NEW.acknowledged_by
                 AND g.capability = 'FEATURE_FLAG_ACK' AND g.revoked_at IS NULL) THEN
    RAISE EXCEPTION 'acknowledgement needs FEATURE_FLAG_ACK' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.feature_flag_change_guard() FROM PUBLIC;
CREATE TRIGGER feature_flag_change_guard BEFORE UPDATE ON app.feature_flag_change
  FOR EACH ROW EXECUTE FUNCTION app.feature_flag_change_guard();
INSERT INTO app.feature_flag (name, environment, value, protected, description) VALUES
  ('documents.extraction', 'default', 'configured', true,
   'Extraction gate (O-15). Security control: set only in deployment configuration, never through this path.'),
  ('release.security-scan-gate', 'default', 'on', true,
   'Release gate (O-16/O-03). Release-gate control: cannot be switched off here.'),
  ('ui.version-comparison', 'default', 'on', false, 'Shows the BRD version comparison on the review page.'),
  ('ui.clarification-inbox', 'default', 'on', false, 'Shows the clarification inbox in the navigation.');
GRANT SELECT ON app.feature_flag, app.feature_flag_change TO sdlc_app;
GRANT UPDATE (value, updated_at) ON app.feature_flag TO sdlc_app;
GRANT INSERT ON app.feature_flag_change TO sdlc_app;
GRANT UPDATE (acknowledged_by, acknowledged_at) ON app.feature_flag_change TO sdlc_app;

--changeset sdlc:0027-events
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
  'ChangeRequestSubmitted', 'ChangeRequestAccepted'));
