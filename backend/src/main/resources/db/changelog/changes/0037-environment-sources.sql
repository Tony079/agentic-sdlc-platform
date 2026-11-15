--liquibase formatted sql

-- Garuda v2 (owner decision GD-23, 2026-11-15): a requirement on an existing product analyses the code of a project
-- environment instead of a repository URL typed by its author. The environment defaults to PROD and can be changed
-- until the BRD quote is accepted. Every repository of the environment is analysed at its branch's head commit, pinned
-- when analysis inputs are prepared (a "round"). The author re-analyses explicitly when branches have moved; nothing is
-- invalidated automatically. Supersedes the per-requirement repository link (C-18) for new requirements.

--changeset sdlc:0037-requirement-sources splitStatements:false
-- requirement_repository becomes the requirement's sources: one row per repository and round. Legacy rows keep their
-- link; environment rows name the environment, the environment branch and the branch.
ALTER TABLE app.requirement_repository ALTER COLUMN link_id DROP NOT NULL;
ALTER TABLE app.requirement_repository
  ADD COLUMN environment_id        uuid,
  ADD COLUMN environment_branch_id uuid,
  ADD COLUMN branch                text CHECK (length(branch) BETWEEN 1 AND 255),
  ADD COLUMN round                 int  NOT NULL DEFAULT 1 CHECK (round >= 1);
ALTER TABLE app.requirement_repository DROP CONSTRAINT requirement_repository_tenant_id_requirement_id_key;
ALTER TABLE app.requirement_repository ADD CONSTRAINT requirement_repository_source_uq
  UNIQUE (requirement_id, round, repository_id);
ALTER TABLE app.requirement_repository ADD CONSTRAINT requirement_repository_origin_check
  CHECK ((link_id IS NOT NULL AND environment_id IS NULL AND round = 1)
         OR (link_id IS NULL AND environment_id IS NOT NULL AND branch IS NOT NULL));
ALTER TABLE app.requirement_repository ADD CONSTRAINT requirement_repository_environment_fk
  FOREIGN KEY (tenant_id, environment_id) REFERENCES app.environment (tenant_id, id);
CREATE INDEX requirement_repository_round_ix ON app.requirement_repository (tenant_id, requirement_id, round);
-- An environment that a requirement was analysed against stays (its history is evidence): the foreign key above
-- refuses its removal.

--changeset sdlc:0037-input-sources splitStatements:false
-- All sources of one analysis: [{repositoryId, fullName, branch, commitSha, snapshotId, prefix}]. The existing
-- repository_id, commit_sha and snapshot_id columns keep the first source.
ALTER TABLE app.analysis_input_set ADD COLUMN sources jsonb NOT NULL DEFAULT '[]'::jsonb
  CHECK (jsonb_typeof(sources) = 'array' AND octet_length(sources::text) <= 65536);

--changeset sdlc:0037-quote-repositories
-- The BRD size counts the repositories analysed (GD-23), not only whether there is one.
ALTER TABLE app.brd_quote ADD COLUMN repositories int NOT NULL DEFAULT 0 CHECK (repositories >= 0);
UPDATE app.brd_quote SET repositories = 1 WHERE has_repository;

--changeset sdlc:0037-target-environment-lock splitStatements:false
-- The target environment is fixed once the BRD quote is accepted (GD-23).
CREATE FUNCTION app.requirement_environment_lock() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF NEW.target_environment_id IS DISTINCT FROM OLD.target_environment_id
     AND EXISTS (SELECT 1 FROM app.brd_quote q WHERE q.requirement_id = NEW.id AND q.status = 'ACCEPTED') THEN
    RAISE EXCEPTION 'the target environment is fixed once the BRD quote is accepted' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.requirement_environment_lock() FROM PUBLIC;
CREATE TRIGGER requirement_environment_lock BEFORE UPDATE OF target_environment_id ON app.requirement
  FOR EACH ROW EXECUTE FUNCTION app.requirement_environment_lock();

--changeset sdlc:0037-env-pin-job splitStatements:false
-- ENV_PIN: pins the branch heads of a new round of sources (environment changed before acceptance, or re-analysis).
ALTER TABLE app.job_type DROP CONSTRAINT job_type_code_check;
ALTER TABLE app.job_type ADD CONSTRAINT job_type_code_check CHECK (code IN ('REPO_VALIDATE', 'DOCUMENT_SCAN',
  'DOCUMENT_EXTRACT', 'BA_ANALYSIS', 'REPO_VERIFY', 'REPO_SNAPSHOT', 'REPO_INDEX', 'BA_COMMIT_RESULT', 'SECURITY_SCAN',
  'ENV_VERIFY', 'ENV_PIN'));
INSERT INTO app.job_type (code, max_attempts, max_infra_failures, backoff_base_seconds, backoff_cap_seconds,
                          lease_seconds, max_runtime_seconds, max_retry_seconds)
  VALUES ('ENV_PIN', 5, 10, 10, 300, 120, 600, 86400);

--changeset sdlc:0037-events splitStatements:false
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
  'SourcesRoundOpened'));

--changeset sdlc:0037-grants
GRANT UPDATE (repositories) ON app.brd_quote TO sdlc_worker;
GRANT INSERT (tenant_id, requirement_id, repository_id, environment_id, environment_branch_id, branch, round)
  ON app.requirement_repository TO sdlc_app;
