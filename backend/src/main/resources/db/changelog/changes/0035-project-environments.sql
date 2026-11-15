--liquibase formatted sql

-- Garuda v2 (owner decisions GD-20, GD-21, 2026-11-15): each project defines its own environments (at most one
-- PROD), each with repository + branch pairs. The project's Environment Manager registers repositories (through the
-- Garuda GitHub App the client installed) and environments; any change touching the PROD environment is a request
-- approved by a BRD Approver of the project (not the requester). The worker verifies repositories and branches through
-- the GitHub broker. Reverses C-01: Garuda staff no longer need to register repositories.

--changeset sdlc:0035-repository-registrant splitStatements:false
ALTER TABLE app.repository ALTER COLUMN registered_by DROP NOT NULL;
ALTER TABLE app.repository ADD COLUMN registered_by_user uuid;
ALTER TABLE app.repository ADD CONSTRAINT repository_registered_by_user_fk
  FOREIGN KEY (tenant_id, registered_by_user) REFERENCES app.app_user (tenant_id, id);
ALTER TABLE app.repository ADD CONSTRAINT repository_one_registrant
  CHECK ((registered_by IS NULL) <> (registered_by_user IS NULL));

-- True when the signed-in client user is the Environment Manager of the active project.
CREATE FUNCTION app.is_environment_manager(p_project uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN app.current_member() IS NOT NULL AND EXISTS (SELECT 1 FROM app.project p WHERE p.id = p_project
         AND p.status = 'ACTIVE' AND p.environment_manager_id = app.current_member());
REVOKE ALL ON FUNCTION app.is_environment_manager(uuid) FROM PUBLIC;

-- Client registrations: the registrant is the acting Environment Manager of a project of the organisation.
CREATE FUNCTION app.repository_client_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF current_user = 'sdlc_app' AND NEW.registered_by_user IS NOT NULL AND (NEW.registered_by_user <> app.current_member()
     OR NOT EXISTS (SELECT 1 FROM app.project p WHERE p.environment_manager_id = NEW.registered_by_user AND p.status = 'ACTIVE')) THEN
    RAISE EXCEPTION 'repositories are registered by an Environment Manager' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.repository_client_guard() FROM PUBLIC;
CREATE TRIGGER repository_client_guard BEFORE INSERT ON app.repository FOR EACH ROW EXECUTE FUNCTION app.repository_client_guard();

CREATE FUNCTION app.project_repository_client_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF current_user = 'sdlc_app' AND app.current_member() IS NOT NULL AND NOT app.is_environment_manager(NEW.project_id) THEN
    RAISE EXCEPTION 'only the Environment Manager links repositories to the project' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.project_repository_client_guard() FROM PUBLIC;
CREATE TRIGGER project_repository_client_guard BEFORE INSERT ON app.project_repository
  FOR EACH ROW EXECUTE FUNCTION app.project_repository_client_guard();

--changeset sdlc:0035-environments splitStatements:false
CREATE TABLE app.environment (
  id            uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id     uuid        NOT NULL REFERENCES app.tenant (id),
  project_id    uuid        NOT NULL,
  name          text        NOT NULL CHECK (length(name) BETWEEN 1 AND 60),
  description   text        CHECK (length(description) <= 500),
  is_production boolean     NOT NULL DEFAULT false,
  created_by    uuid        NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, created_by) REFERENCES app.app_user (tenant_id, id)
);
CREATE UNIQUE INDEX environment_project_name_uq ON app.environment (project_id, lower(name));
CREATE UNIQUE INDEX environment_one_production_uq ON app.environment (project_id) WHERE is_production;

CREATE TABLE app.environment_branch (
  id             uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id      uuid        NOT NULL,
  project_id     uuid        NOT NULL,
  environment_id uuid        NOT NULL,
  repository_id  uuid        NOT NULL,
  branch         text        NOT NULL CHECK (length(branch) BETWEEN 1 AND 255 AND branch !~ '[[:cntrl:] ~^:?*\[\\]'
                                             AND branch !~ '\.\.' AND branch !~ '^/|/$'),
  status         text        NOT NULL DEFAULT 'PENDING_VERIFICATION'
                             CHECK (status IN ('PENDING_VERIFICATION', 'VERIFIED', 'BRANCH_MISSING', 'ACCESS_DENIED', 'UNAVAILABLE')),
  head_sha       text        CHECK (head_sha ~ '^[0-9a-f]{40}$'),
  failure_code   text        CHECK (failure_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  verified_at    timestamptz,
  added_by       uuid        NOT NULL,
  added_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (environment_id, repository_id),
  FOREIGN KEY (tenant_id, environment_id) REFERENCES app.environment (tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, repository_id) REFERENCES app.repository (tenant_id, id),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, added_by) REFERENCES app.app_user (tenant_id, id),
  CHECK ((status = 'VERIFIED') = (head_sha IS NOT NULL AND verified_at IS NOT NULL))
);

-- A change that touches the PROD environment: requested by the Environment Manager, approved by a BRD Approver of the
-- project who is not the requester, executed in the approving transaction.
CREATE TABLE app.environment_change_request (
  id             uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id      uuid        NOT NULL REFERENCES app.tenant (id),
  project_id     uuid        NOT NULL,
  environment_id uuid,
  kind           text        NOT NULL CHECK (kind IN ('MARK_PRODUCTION', 'ADD_BRANCH', 'CHANGE_BRANCH', 'REMOVE_BRANCH',
                                                      'RENAME_ENVIRONMENT', 'REMOVE_ENVIRONMENT')),
  repository_id  uuid,
  branch         text        CHECK (length(branch) BETWEEN 1 AND 255),
  new_name       text        CHECK (length(new_name) BETWEEN 1 AND 60),
  reason         text        NOT NULL CHECK (length(reason) BETWEEN 5 AND 2000),
  status         text        NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'EXECUTED', 'REJECTED', 'CANCELLED')),
  requested_by   uuid        NOT NULL,
  requested_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  approved_by    uuid,
  executed_at    timestamptz,
  executed_txid  bigint,
  decided_by     uuid,
  decided_at     timestamptz,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, requested_by) REFERENCES app.app_user (tenant_id, id),
  CHECK (approved_by IS NULL OR approved_by <> requested_by),
  CHECK ((status = 'EXECUTED') = (approved_by IS NOT NULL AND executed_at IS NOT NULL)),
  CHECK ((status IN ('REJECTED', 'CANCELLED')) = (decided_by IS NOT NULL AND decided_at IS NOT NULL))
);

CREATE FUNCTION app.is_brd_approver(p_project uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN app.current_member() IS NOT NULL AND EXISTS (SELECT 1 FROM app.project_member m JOIN app.app_user u ON u.id = m.user_id
         WHERE m.project_id = p_project AND m.user_id = app.current_member() AND m.removed_at IS NULL
           AND m.role = 'BRD_APPROVER' AND u.status = 'ACTIVE');
REVOKE ALL ON FUNCTION app.is_brd_approver(uuid) FROM PUBLIC;

CREATE FUNCTION app.environment_change_executed(p_project uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN EXISTS (SELECT 1 FROM app.environment_change_request r WHERE r.project_id = p_project
                 AND r.status = 'EXECUTED' AND r.executed_txid = txid_current());
REVOKE ALL ON FUNCTION app.environment_change_executed(uuid) FROM PUBLIC;

CREATE FUNCTION app.environment_change_request_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF current_user <> 'sdlc_app' THEN
    RETURN NEW;
  END IF;
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'REQUESTED' OR NEW.approved_by IS NOT NULL OR NEW.requested_by <> app.current_member()
       OR NOT app.is_environment_manager(NEW.project_id) THEN
      RAISE EXCEPTION 'PROD changes are requested by the Environment Manager' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.kind <> OLD.kind OR NEW.project_id <> OLD.project_id OR NEW.requested_by <> OLD.requested_by
     OR NEW.environment_id IS DISTINCT FROM OLD.environment_id OR NEW.repository_id IS DISTINCT FROM OLD.repository_id
     OR NEW.branch IS DISTINCT FROM OLD.branch OR NEW.new_name IS DISTINCT FROM OLD.new_name OR OLD.status <> 'REQUESTED' THEN
    RAISE EXCEPTION 'environment change requests are immutable once decided' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status = 'EXECUTED' THEN
    IF NEW.approved_by <> app.current_member() OR NEW.approved_by = OLD.requested_by OR NOT app.is_brd_approver(NEW.project_id) THEN
      RAISE EXCEPTION 'a PROD change is approved by a BRD Approver of the project other than the requester' USING ERRCODE = 'P0001';
    END IF;
    NEW.executed_txid := txid_current();
  ELSIF NEW.status = 'REJECTED' THEN
    IF NEW.decided_by <> app.current_member() OR NOT app.is_brd_approver(NEW.project_id) THEN
      RAISE EXCEPTION 'only a BRD Approver of the project may reject' USING ERRCODE = 'P0001';
    END IF;
  ELSIF NEW.status = 'CANCELLED' THEN
    IF NEW.decided_by <> OLD.requested_by OR NEW.decided_by <> app.current_member() THEN
      RAISE EXCEPTION 'only the requester may cancel' USING ERRCODE = 'P0001';
    END IF;
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.environment_change_request_guard() FROM PUBLIC;
CREATE TRIGGER environment_change_request_guard BEFORE INSERT OR UPDATE ON app.environment_change_request
  FOR EACH ROW EXECUTE FUNCTION app.environment_change_request_guard();

-- Environments: written by the Environment Manager of the active project; anything touching PROD only as the execution
-- of an approved change request in this transaction.
CREATE FUNCTION app.environment_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  project uuid := CASE WHEN TG_OP = 'DELETE' THEN OLD.project_id ELSE NEW.project_id END;
  touches_prod boolean := (TG_OP <> 'INSERT' AND OLD.is_production) OR (TG_OP <> 'DELETE' AND NEW.is_production);
BEGIN
  IF current_user = 'sdlc_app' THEN
    IF NOT app.is_environment_manager(project) AND NOT (touches_prod AND app.environment_change_executed(project)) THEN
      RAISE EXCEPTION 'environments are managed by the project''s Environment Manager' USING ERRCODE = 'P0001';
    END IF;
    IF touches_prod AND NOT app.environment_change_executed(project) THEN
      RAISE EXCEPTION 'changes to the PROD environment need an approved request' USING ERRCODE = 'P0001';
    END IF;
  END IF;
  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  IF TG_OP = 'UPDATE' AND (NEW.id <> OLD.id OR NEW.project_id <> OLD.project_id OR NEW.created_by <> OLD.created_by) THEN
    RAISE EXCEPTION 'environment identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  NEW.updated_at := clock_timestamp();
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.environment_guard() FROM PUBLIC;
CREATE TRIGGER environment_guard BEFORE INSERT OR UPDATE OR DELETE ON app.environment
  FOR EACH ROW EXECUTE FUNCTION app.environment_guard();

-- Branches: as environments; the verification columns are written by the worker (or the API) without a request.
CREATE FUNCTION app.environment_branch_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  row_env uuid := CASE WHEN TG_OP = 'DELETE' THEN OLD.environment_id ELSE NEW.environment_id END;
  project uuid := CASE WHEN TG_OP = 'DELETE' THEN OLD.project_id ELSE NEW.project_id END;
  prod boolean;
BEGIN
  IF TG_OP = 'INSERT' AND NOT EXISTS (SELECT 1 FROM app.environment e WHERE e.id = NEW.environment_id AND e.project_id = NEW.project_id)
     OR TG_OP = 'INSERT' AND NOT EXISTS (SELECT 1 FROM app.project_repository r WHERE r.project_id = NEW.project_id
                                         AND r.repository_id = NEW.repository_id) THEN
    RAISE EXCEPTION 'the repository must be linked to the environment''s project' USING ERRCODE = 'P0001';
  END IF;
  IF current_user <> 'sdlc_app' THEN
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  END IF;
  IF TG_OP = 'UPDATE' AND NEW.branch = OLD.branch AND NEW.repository_id = OLD.repository_id
     AND NEW.environment_id = OLD.environment_id AND NEW.added_by = OLD.added_by THEN
    RETURN NEW;  -- verification result only
  END IF;
  SELECT e.is_production INTO prod FROM app.environment e WHERE e.id = row_env;
  IF NOT app.is_environment_manager(project) AND NOT (coalesce(prod, false) AND app.environment_change_executed(project)) THEN
    RAISE EXCEPTION 'branches are managed by the project''s Environment Manager' USING ERRCODE = 'P0001';
  END IF;
  IF coalesce(prod, false) AND NOT app.environment_change_executed(project) THEN
    RAISE EXCEPTION 'changes to the PROD environment need an approved request' USING ERRCODE = 'P0001';
  END IF;
  IF TG_OP = 'UPDATE' AND NEW.branch <> OLD.branch THEN
    NEW.status := 'PENDING_VERIFICATION';
    NEW.head_sha := NULL;
    NEW.verified_at := NULL;
    NEW.failure_code := NULL;
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$fn$;
REVOKE ALL ON FUNCTION app.environment_branch_guard() FROM PUBLIC;
CREATE TRIGGER environment_branch_guard BEFORE INSERT OR UPDATE OR DELETE ON app.environment_branch
  FOR EACH ROW EXECUTE FUNCTION app.environment_branch_guard();

-- Row-level security: tenant isolation, plus project membership for signed-in client users.
ALTER TABLE app.environment ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.environment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.environment
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());
CREATE POLICY project_access ON app.environment AS RESTRICTIVE
  USING (app.current_member() IS NULL OR app.member_sees_project(project_id));
ALTER TABLE app.environment_branch ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.environment_branch FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.environment_branch
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());
CREATE POLICY project_access ON app.environment_branch AS RESTRICTIVE
  USING (app.current_member() IS NULL OR app.member_sees_project(project_id));
ALTER TABLE app.environment_change_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.environment_change_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.environment_change_request
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());
CREATE POLICY project_access ON app.environment_change_request AS RESTRICTIVE
  USING (app.current_member() IS NULL OR app.member_sees_project(project_id));

-- Requirements name the environment they target (existing products; future agents work from its branches).
ALTER TABLE app.requirement ADD COLUMN target_environment_id uuid;
ALTER TABLE app.requirement ADD CONSTRAINT requirement_target_environment_fk
  FOREIGN KEY (tenant_id, target_environment_id) REFERENCES app.environment (tenant_id, id);

--changeset sdlc:0035-verification-job splitStatements:false
-- ENV_VERIFY: the worker checks the GitHub App installation, the repository and each pending branch's head commit.
ALTER TABLE app.job_type DROP CONSTRAINT job_type_code_check;
ALTER TABLE app.job_type ADD CONSTRAINT job_type_code_check CHECK (code IN ('REPO_VALIDATE', 'DOCUMENT_SCAN',
  'DOCUMENT_EXTRACT', 'BA_ANALYSIS', 'REPO_VERIFY', 'REPO_SNAPSHOT', 'REPO_INDEX', 'BA_COMMIT_RESULT', 'SECURITY_SCAN',
  'ENV_VERIFY'));
INSERT INTO app.job_type (code, max_attempts, max_infra_failures, backoff_base_seconds, backoff_cap_seconds,
                          lease_seconds, max_runtime_seconds, max_retry_seconds)
  VALUES ('ENV_VERIFY', 3, 5, 10, 300, 120, 600, 21600);
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
  'ChangeRequestSubmitted', 'ChangeRequestAccepted', 'EnvironmentBranchesChanged'));

--changeset sdlc:0035-grants
GRANT EXECUTE ON FUNCTION app.is_environment_manager(uuid), app.is_brd_approver(uuid),
  app.environment_change_executed(uuid) TO sdlc_app, sdlc_worker, sdlc_retention;
GRANT INSERT (registered_by_user) ON app.repository TO sdlc_app;
GRANT SELECT, INSERT, UPDATE (name, description, is_production), DELETE ON app.environment TO sdlc_app;
GRANT SELECT, INSERT, UPDATE (branch, status, head_sha, failure_code, verified_at), DELETE ON app.environment_branch TO sdlc_app;
GRANT SELECT, INSERT ON app.environment_change_request TO sdlc_app;
GRANT UPDATE (status, approved_by, executed_at, decided_by, decided_at) ON app.environment_change_request TO sdlc_app;
GRANT UPDATE (target_environment_id) ON app.requirement TO sdlc_app;
GRANT SELECT ON app.environment, app.environment_branch TO sdlc_worker, sdlc_retention;
GRANT UPDATE (status, head_sha, failure_code, verified_at) ON app.environment_branch TO sdlc_worker;
