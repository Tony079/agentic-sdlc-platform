--liquibase formatted sql

--changeset sdlc:0019-repository
-- R1-M4 (C-01, Q-A, TDD 14): a client GitHub repository registered by staff from a Client Approver's authorised link.
-- github_repo_id and default_branch come only from GitHub's API response (trusted data), never from user input.
CREATE TABLE app.repository (
  id              uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id       uuid        NOT NULL REFERENCES app.tenant (id),
  github_owner    text        NOT NULL CHECK (github_owner ~ '^[A-Za-z0-9-]{1,39}$'),
  github_name     text        NOT NULL CHECK (github_name ~ '^[A-Za-z0-9._-]{1,100}$'),
  repository_url  text        NOT NULL CHECK (length(repository_url) <= 300),
  status          text        NOT NULL DEFAULT 'PENDING_VERIFICATION'
                              CHECK (status IN ('PENDING_VERIFICATION', 'VERIFIED', 'UNAVAILABLE', 'ACCESS_DENIED')),
  status_reason   text        CHECK (status_reason ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  github_repo_id  bigint      CHECK (github_repo_id > 0),
  default_branch  text        CHECK (length(default_branch) BETWEEN 1 AND 255),
  registered_by   uuid        NOT NULL REFERENCES app.staff_user (id),
  registered_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  verified_at     timestamptz,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  CHECK (status <> 'VERIFIED' OR (github_repo_id IS NOT NULL AND default_branch IS NOT NULL AND verified_at IS NOT NULL))
);
CREATE UNIQUE INDEX repository_tenant_name_uq ON app.repository (tenant_id, lower(github_owner), lower(github_name));
-- One GitHub repository belongs to at most one tenant. A clash is reported to nobody: the later registration becomes
-- UNAVAILABLE with a generic reason.
CREATE UNIQUE INDEX repository_github_id_uq ON app.repository (github_repo_id) WHERE github_repo_id IS NOT NULL;

CREATE TABLE app.project_repository (
  tenant_id     uuid        NOT NULL,
  project_id    uuid        NOT NULL,
  repository_id uuid        NOT NULL,
  linked_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, project_id, repository_id),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, repository_id) REFERENCES app.repository (tenant_id, id)
);

-- Validation of a repository for one requirement: the pinned commit SHA is what later analysis reads.
CREATE TABLE app.requirement_repository (
  id             uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id      uuid        NOT NULL,
  requirement_id uuid        NOT NULL,
  repository_id  uuid        NOT NULL,
  link_id        uuid        NOT NULL,
  status         text        NOT NULL DEFAULT 'PENDING_VALIDATION'
                             CHECK (status IN ('PENDING_VALIDATION', 'VALIDATED', 'FAILED')),
  pinned_sha     text        CHECK (pinned_sha ~ '^[0-9a-f]{40}$'),
  default_branch text        CHECK (length(default_branch) BETWEEN 1 AND 255),
  failure_code   text        CHECK (failure_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  registered_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  validated_at   timestamptz,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, requirement_id),
  UNIQUE (link_id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, repository_id) REFERENCES app.repository (tenant_id, id),
  FOREIGN KEY (tenant_id, link_id) REFERENCES app.requirement_repository_link (tenant_id, id),
  CHECK ((status = 'VALIDATED') = (pinned_sha IS NOT NULL AND validated_at IS NOT NULL))
);

--changeset sdlc:0019-repository-snapshot
-- One stored archive of the pinned commit (TDD 14.5). Kept encrypted in the object store; never extracted to disk.
-- Retention is open (O-25): there is no deletion job.
CREATE TABLE app.repository_snapshot (
  id                        uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id                 uuid        NOT NULL,
  requirement_repository_id uuid        NOT NULL,
  repository_id             uuid        NOT NULL,
  commit_sha                text        NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
  status                    text        NOT NULL CHECK (status IN ('STORED', 'INDEXED', 'FAILED')),
  storage_key               text        CHECK (length(storage_key) <= 200),
  archive_sha256            text        CHECK (archive_sha256 ~ '^[0-9a-f]{64}$'),
  archive_bytes             bigint      CHECK (archive_bytes >= 0),
  file_count                int         CHECK (file_count >= 0),
  skipped_count             int         CHECK (skipped_count >= 0),
  expanded_bytes            bigint      CHECK (expanded_bytes >= 0),
  failure_code              text        CHECK (failure_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  created_at                timestamptz NOT NULL DEFAULT clock_timestamp(),
  indexed_at                timestamptz,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (requirement_repository_id),
  FOREIGN KEY (tenant_id, requirement_repository_id) REFERENCES app.requirement_repository (tenant_id, id),
  FOREIGN KEY (tenant_id, repository_id) REFERENCES app.repository (tenant_id, id),
  CHECK (status = 'FAILED' OR (storage_key IS NOT NULL AND archive_sha256 IS NOT NULL))
);

-- File inventory of a snapshot: path, language, size, hash and why a file is not indexed. No file content.
CREATE TABLE app.repository_file (
  tenant_id    uuid    NOT NULL,
  snapshot_id  uuid    NOT NULL,
  path         text    NOT NULL CHECK (length(path) BETWEEN 1 AND 1024),
  language     text    CHECK (language ~ '^[A-Z][A-Z0-9_]{1,31}$'),
  size_bytes   bigint  NOT NULL CHECK (size_bytes >= 0),
  sha256       text    CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  indexable    boolean NOT NULL,
  skip_reason  text    CHECK (skip_reason ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  PRIMARY KEY (tenant_id, snapshot_id, path),
  FOREIGN KEY (tenant_id, snapshot_id) REFERENCES app.repository_snapshot (tenant_id, id),
  CHECK (indexable = (skip_reason IS NULL))
);

CREATE TABLE app.code_symbol (
  id           uuid    NOT NULL DEFAULT gen_random_uuid(),
  tenant_id    uuid    NOT NULL,
  snapshot_id  uuid    NOT NULL,
  file_path    text    NOT NULL CHECK (length(file_path) BETWEEN 1 AND 1024),
  kind         text    NOT NULL CHECK (kind IN ('CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'ANNOTATION', 'METHOD',
                                                 'CONSTRUCTOR')),
  package_name text    CHECK (length(package_name) <= 500),
  type_name    text    NOT NULL CHECK (length(type_name) BETWEEN 1 AND 500),
  member_name  text    CHECK (length(member_name) <= 500),
  stereotype   text    CHECK (stereotype IN ('CONTROLLER', 'REST_CONTROLLER', 'SERVICE', 'REPOSITORY', 'COMPONENT',
                                             'CONFIGURATION', 'ENTITY')),
  http_method  text    CHECK (http_method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'ANY')),
  http_path    text    CHECK (length(http_path) <= 500),
  start_line   int     NOT NULL CHECK (start_line >= 1),
  end_line     int     NOT NULL,
  PRIMARY KEY (id),
  FOREIGN KEY (tenant_id, snapshot_id) REFERENCES app.repository_snapshot (tenant_id, id),
  CHECK (end_line >= start_line)
);
CREATE INDEX code_symbol_snapshot_ix ON app.code_symbol (tenant_id, snapshot_id, type_name);

CREATE TABLE app.build_dependency (
  id           uuid NOT NULL DEFAULT gen_random_uuid(),
  tenant_id    uuid NOT NULL,
  snapshot_id  uuid NOT NULL,
  file_path    text NOT NULL CHECK (length(file_path) BETWEEN 1 AND 1024),
  build_tool   text NOT NULL CHECK (build_tool IN ('MAVEN', 'GRADLE')),
  group_id     text CHECK (length(group_id) <= 300),
  artifact_id  text NOT NULL CHECK (length(artifact_id) BETWEEN 1 AND 300),
  version      text CHECK (length(version) <= 200),
  scope        text CHECK (length(scope) <= 100),
  PRIMARY KEY (id),
  FOREIGN KEY (tenant_id, snapshot_id) REFERENCES app.repository_snapshot (tenant_id, id)
);
CREATE INDEX build_dependency_snapshot_ix ON app.build_dependency (tenant_id, snapshot_id);

--changeset sdlc:0019-repository-guards splitStatements:false
-- Status moves forward only (invoker rights, fixed search_path). A verified repository may be re-verified; a pinned SHA
-- never changes.
CREATE FUNCTION app.requirement_repository_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF OLD.status <> 'PENDING_VALIDATION' THEN
    RAISE EXCEPTION 'requirement repository % is final', OLD.id USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.requirement_repository_guard() FROM PUBLIC;
CREATE TRIGGER requirement_repository_guard BEFORE UPDATE ON app.requirement_repository
  FOR EACH ROW EXECUTE FUNCTION app.requirement_repository_guard();

CREATE FUNCTION app.repository_snapshot_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF NEW.commit_sha <> OLD.commit_sha OR NEW.storage_key IS DISTINCT FROM OLD.storage_key
     OR NEW.archive_sha256 IS DISTINCT FROM OLD.archive_sha256 THEN
    RAISE EXCEPTION 'snapshot % content identity is immutable', OLD.id USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status <> 'STORED' THEN
    RAISE EXCEPTION 'snapshot % is final', OLD.id USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.repository_snapshot_guard() FROM PUBLIC;
CREATE TRIGGER repository_snapshot_guard BEFORE UPDATE ON app.repository_snapshot
  FOR EACH ROW EXECUTE FUNCTION app.repository_snapshot_guard();

--changeset sdlc:0019-repository-rls splitStatements:false
DO $rls$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['repository', 'project_repository', 'requirement_repository', 'repository_snapshot',
                           'repository_file', 'code_symbol', 'build_dependency'] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON app.%I '
                   'USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant())', t);
  END LOOP;
END
$rls$;

--changeset sdlc:0019-repository-jobs-events
-- New job types (D-M4R-01): REPO_VERIFY (broker check of the App installation), REPO_SNAPSHOT, REPO_INDEX. The
-- REPOSITORY aggregate and the RepositoryRegistered event (D-M4R-10) let the API, which cannot touch the job queue,
-- hand registration to the worker through the outbox.
ALTER TABLE app.job_type DROP CONSTRAINT job_type_code_check;
ALTER TABLE app.job_type ADD CONSTRAINT job_type_code_check CHECK (code IN ('REPO_VALIDATE', 'DOCUMENT_SCAN',
  'DOCUMENT_EXTRACT', 'BA_ANALYSIS', 'REPO_VERIFY', 'REPO_SNAPSHOT', 'REPO_INDEX'));
INSERT INTO app.job_type VALUES
  ('REPO_VERIFY',   3, 5, 10, 300, 120,  600),
  ('REPO_SNAPSHOT', 3, 5, 30, 600, 300, 1800),
  ('REPO_INDEX',    3, 5, 30, 600, 300, 1800);

ALTER TABLE app.job DROP CONSTRAINT job_aggregate_type_check;
ALTER TABLE app.job ADD CONSTRAINT job_aggregate_type_check
  CHECK (aggregate_type IN ('REQUIREMENT', 'DOCUMENT', 'REPOSITORY'));
ALTER TABLE app.outbox_event DROP CONSTRAINT outbox_event_aggregate_type_check;
ALTER TABLE app.outbox_event ADD CONSTRAINT outbox_event_aggregate_type_check
  CHECK (aggregate_type IN ('REQUIREMENT', 'DOCUMENT', 'JOB', 'REPOSITORY'));
ALTER TABLE app.outbox_event DROP CONSTRAINT outbox_event_event_type_check;
ALTER TABLE app.outbox_event ADD CONSTRAINT outbox_event_event_type_check CHECK (event_type IN (
  'RequirementSubmitted', 'RepoValidationStarted', 'RepoValidated', 'DocumentDecisionRequested',
  'DocumentDecisionRecorded', 'ManualIntakeEntered', 'ManualIntakeResumed', 'AnalysisStarted',
  'AnalysisResumed', 'ExecutionStale', 'ClarificationOpened', 'ClarificationResolved',
  'ClarificationEscalated', 'BrdVersionCreated', 'ReviewQueued', 'BrdApprovedInternal',
  'BrdReleasedToClient', 'RevisionRequested', 'RequirementRejected', 'ClientChangesRequested',
  'RequirementCancelled', 'WorkflowFailed', 'JobDead', 'AdminRetryRequested',
  'AdminRetryCompleted', 'AdminRetryRejected', 'DocumentUploaded', 'DocumentScanned',
  'DocumentProcessed', 'DocumentFailed', 'RetentionDue', 'TenantProvisioned', 'RepositoryRegistered'));

--changeset sdlc:0019-workflow-repository-transitions
-- Q-B Option A: T06 and T07 only (no T08, no ANALYSING, no BA job); T09 on a dead validation job (no retry path, O-27,
-- no T30). T29 (owner cancellation, PRD section 6) from the newly reachable non-terminal states (D-M4R-05).
ALTER TABLE app.workflow_transition DROP CONSTRAINT workflow_transition_transition_check;
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_transition_check
  CHECK (transition IN ('CREATE', 'T01_SUBMIT', 'T02_ROUTE_REPOSITORY', 'T03_ROUTE_MANUAL_INTAKE',
                        'T06_REPO_VALIDATED', 'T07_REPO_VALIDATED_DOCUMENT_DECISION', 'T09_REPO_VALIDATION_FAILED',
                        'T29_CANCEL'));
ALTER TABLE app.workflow_transition DROP CONSTRAINT workflow_transition_legal_pair_check;
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_legal_pair_check
  CHECK (from_state IS NULL
      OR (from_state, to_state, transition) IN (('DRAFT', 'SUBMITTED', 'T01_SUBMIT'),
                                                ('SUBMITTED', 'REPO_VALIDATING', 'T02_ROUTE_REPOSITORY'),
                                                ('SUBMITTED', 'MANUAL_INTAKE', 'T03_ROUTE_MANUAL_INTAKE'),
                                                ('REPO_VALIDATING', 'AWAITING_DOCUMENTS', 'T06_REPO_VALIDATED'),
                                                ('REPO_VALIDATING', 'AWAITING_DOCUMENT_DECISION',
                                                 'T07_REPO_VALIDATED_DOCUMENT_DECISION'),
                                                ('REPO_VALIDATING', 'FAILED', 'T09_REPO_VALIDATION_FAILED'),
                                                ('DRAFT', 'CANCELLED', 'T29_CANCEL'),
                                                ('SUBMITTED', 'CANCELLED', 'T29_CANCEL'),
                                                ('MANUAL_INTAKE', 'CANCELLED', 'T29_CANCEL'),
                                                ('REPO_VALIDATING', 'CANCELLED', 'T29_CANCEL'),
                                                ('AWAITING_DOCUMENTS', 'CANCELLED', 'T29_CANCEL'),
                                                ('AWAITING_DOCUMENT_DECISION', 'CANCELLED', 'T29_CANCEL'),
                                                ('FAILED', 'CANCELLED', 'T29_CANCEL')));
ALTER TABLE app.workflow_transition DROP CONSTRAINT workflow_transition_engine_actor_check;
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_engine_actor_check
  CHECK (transition NOT IN ('T02_ROUTE_REPOSITORY', 'T03_ROUTE_MANUAL_INTAKE', 'T06_REPO_VALIDATED',
                            'T07_REPO_VALIDATED_DOCUMENT_DECISION', 'T09_REPO_VALIDATION_FAILED')
         OR (actor_type = 'SYSTEM' AND actor_id IS NULL));

UPDATE app.workflow_definition SET transitions = transitions || '[
  {"transition": "T06_REPO_VALIDATED", "from": "REPO_VALIDATING", "to": "AWAITING_DOCUMENTS", "actor": "ENGINE"},
  {"transition": "T07_REPO_VALIDATED_DOCUMENT_DECISION", "from": "REPO_VALIDATING", "to": "AWAITING_DOCUMENT_DECISION", "actor": "ENGINE"},
  {"transition": "T09_REPO_VALIDATION_FAILED", "from": "REPO_VALIDATING", "to": "FAILED", "actor": "ENGINE"},
  {"transition": "T29_CANCEL", "from": "AWAITING_DOCUMENTS", "to": "CANCELLED", "actor": "OWNER"},
  {"transition": "T29_CANCEL", "from": "AWAITING_DOCUMENT_DECISION", "to": "CANCELLED", "actor": "OWNER"},
  {"transition": "T29_CANCEL", "from": "FAILED", "to": "CANCELLED", "actor": "OWNER"}
]'::jsonb WHERE version = 'R1-v1';

--changeset sdlc:0019-repository-grants
-- API (staff registration; client and staff status reads). No GitHub credentials anywhere in the API.
GRANT SELECT ON app.repository, app.project_repository, app.requirement_repository, app.repository_snapshot TO sdlc_app;
GRANT INSERT (tenant_id, github_owner, github_name, repository_url, registered_by) ON app.repository TO sdlc_app;
GRANT INSERT ON app.project_repository TO sdlc_app;
GRANT INSERT (tenant_id, requirement_id, repository_id, link_id) ON app.requirement_repository TO sdlc_app;

-- Worker: verification, validation, snapshot and inventory.
GRANT SELECT ON app.repository, app.requirement_repository, app.repository_snapshot, app.repository_file,
                app.code_symbol, app.build_dependency TO sdlc_worker;
GRANT UPDATE (status, status_reason, github_repo_id, default_branch, verified_at) ON app.repository TO sdlc_worker;
GRANT UPDATE (status, pinned_sha, default_branch, failure_code, validated_at) ON app.requirement_repository TO sdlc_worker;
GRANT INSERT ON app.repository_snapshot, app.repository_file, app.code_symbol, app.build_dependency TO sdlc_worker;
GRANT UPDATE (status, file_count, skipped_count, expanded_bytes, failure_code, indexed_at)
  ON app.repository_snapshot TO sdlc_worker;
