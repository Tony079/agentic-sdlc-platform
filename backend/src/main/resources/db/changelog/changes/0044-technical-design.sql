--liquibase formatted sql

-- GD-26 (owner, 2026-11-17): an approved BRD moves the requirement to the Technical design stage. The Architect agent
-- writes a technical design (TDD) from the approved BRD and the code it was analysed at; the author confirms it and a
-- BRD Approver other than the author approves it. Design: docs/architecture/garuda-tdd-stage.md.

--changeset sdlc:0044-tdd-tables splitStatements:false
CREATE TABLE app.tdd_version (
  id              uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id       uuid        NOT NULL REFERENCES app.tenant (id),
  project_id      uuid        NOT NULL,
  requirement_id  uuid        NOT NULL,
  version_no      int         NOT NULL CHECK (version_no >= 1),
  brd_version_id  uuid        NOT NULL,
  status          text        NOT NULL CHECK (status IN ('GENERATING', 'IN_REVIEW', 'APPROVED', 'SUPERSEDED',
                                                         'FAILED')),
  summary         text        CHECK (length(summary) <= 4000),
  open_questions  jsonb       NOT NULL DEFAULT '[]'::jsonb
                              CHECK (jsonb_typeof(open_questions) = 'array' AND octet_length(open_questions::text) <= 20000),
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
  FOREIGN KEY (tenant_id, brd_version_id) REFERENCES app.brd_version (tenant_id, id),
  FOREIGN KEY (tenant_id, requested_by) REFERENCES app.app_user (tenant_id, id),
  CHECK (status IN ('GENERATING', 'FAILED') OR content_hash IS NOT NULL)
);
-- One design in progress or in review per requirement.
CREATE UNIQUE INDEX tdd_version_one_open_uq ON app.tdd_version (requirement_id)
  WHERE status IN ('GENERATING', 'IN_REVIEW', 'FAILED');

CREATE TABLE app.tdd_item (
  id              uuid    NOT NULL DEFAULT gen_random_uuid(),
  tenant_id       uuid    NOT NULL REFERENCES app.tenant (id),
  tdd_version_id  uuid    NOT NULL,
  ordinal         int     NOT NULL CHECK (ordinal >= 1),
  item_key        text    NOT NULL CHECK (item_key ~ '^[a-z0-9-]{1,80}$'),
  section         text    NOT NULL CHECK (section IN ('ARCHITECTURE', 'COMPONENTS', 'API_CHANGES', 'DATA_MODEL',
                                                      'INTEGRATIONS', 'SECURITY', 'NON_FUNCTIONAL',
                                                      'MIGRATION_AND_ROLLOUT', 'TEST_PLAN', 'RISKS')),
  kind            text    NOT NULL CHECK (kind IN ('DECISION', 'CHANGE', 'NEW', 'TASK', 'RISK', 'ASSUMPTION')),
  title           text    NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
  detail          text    NOT NULL CHECK (length(detail) BETWEEN 1 AND 3000),
  component       text    CHECK (length(component) <= 200),
  -- Item keys of the approved BRD this item addresses; unknown keys are dropped by the engine before insert.
  brd_refs        jsonb   NOT NULL DEFAULT '[]'::jsonb
                          CHECK (jsonb_typeof(brd_refs) = 'array' AND octet_length(brd_refs::text) <= 8000),
  support_state   text    NOT NULL CHECK (support_state IN ('VERIFIED', 'FLAGGED', 'NOT_TRACED')),
  -- Only citations that passed the trusted checker: [{path, startLine, endLine, rangeSha256, quote, supportPassed}].
  evidence        jsonb   NOT NULL DEFAULT '[]'::jsonb
                          CHECK (jsonb_typeof(evidence) = 'array' AND octet_length(evidence::text) <= 16000),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (tdd_version_id, ordinal),
  UNIQUE (tdd_version_id, item_key),
  FOREIGN KEY (tenant_id, tdd_version_id) REFERENCES app.tdd_version (tenant_id, id)
);

CREATE TABLE app.tdd_decision (
  id              uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id       uuid        NOT NULL REFERENCES app.tenant (id),
  project_id      uuid        NOT NULL,
  requirement_id  uuid        NOT NULL,
  tdd_version_id  uuid        NOT NULL,
  kind            text        NOT NULL CHECK (kind IN ('FLAG_ACCEPTED', 'AUTHOR_CONFIRMED', 'APPROVED',
                                                       'CHANGES_REQUESTED')),
  item_key        text        CHECK (length(item_key) <= 100),
  note            text        CHECK (length(note) <= 5000),
  decided_by      uuid        NOT NULL,
  decided_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, tdd_version_id) REFERENCES app.tdd_version (tenant_id, id),
  FOREIGN KEY (tenant_id, decided_by) REFERENCES app.app_user (tenant_id, id),
  CHECK ((kind = 'FLAG_ACCEPTED') = (item_key IS NOT NULL)),
  CHECK (kind <> 'CHANGES_REQUESTED' OR length(note) >= 10)
);
CREATE INDEX tdd_decision_version_ix ON app.tdd_decision (tenant_id, tdd_version_id, kind);
CREATE UNIQUE INDEX tdd_decision_once_uq ON app.tdd_decision (tdd_version_id, kind, coalesce(item_key, ''))
  WHERE kind <> 'CHANGES_REQUESTED';

--changeset sdlc:0044-tdd-guards splitStatements:false
-- A design is started (and changes are requested) by the requirement's author or a BRD Approver, from a released BRD.
-- Content is written only while GENERATING (by the worker); afterwards only the status moves forward.
CREATE FUNCTION app.tdd_version_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  member uuid := app.current_member();
  owner uuid;
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'design versions are not deleted' USING ERRCODE = 'P0001';
  END IF;
  IF TG_OP = 'INSERT' THEN
    SELECT created_by INTO owner FROM app.requirement WHERE id = NEW.requirement_id;
    IF NEW.status <> 'GENERATING' OR NEW.content_hash IS NOT NULL THEN
      RAISE EXCEPTION 'a design version starts empty, generating' USING ERRCODE = 'P0001';
    END IF;
    IF member IS NULL OR member IS DISTINCT FROM NEW.requested_by
       OR (member <> owner AND NOT app.is_brd_approver(NEW.project_id)) THEN
      RAISE EXCEPTION 'a design is requested by the requirement''s author or a BRD Approver' USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM app.brd_version b WHERE b.id = NEW.brd_version_id
                   AND b.requirement_id = NEW.requirement_id AND b.status = 'RELEASED') THEN
      RAISE EXCEPTION 'a design is made from the approved BRD' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.version_no <> coalesce((SELECT max(version_no) FROM app.tdd_version WHERE requirement_id = NEW.requirement_id), 0) + 1 THEN
      RAISE EXCEPTION 'design versions are numbered in order' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.requirement_id <> OLD.requirement_id
     OR NEW.project_id <> OLD.project_id OR NEW.version_no <> OLD.version_no OR NEW.brd_version_id <> OLD.brd_version_id
     OR NEW.requested_by <> OLD.requested_by OR NEW.change_request IS DISTINCT FROM OLD.change_request
     OR NEW.created_at <> OLD.created_at THEN
    RAISE EXCEPTION 'design version identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status <> 'GENERATING' AND (NEW.summary IS DISTINCT FROM OLD.summary
     OR NEW.open_questions IS DISTINCT FROM OLD.open_questions OR NEW.content_hash IS DISTINCT FROM OLD.content_hash
     OR NEW.model_provider IS DISTINCT FROM OLD.model_provider OR NEW.model_id IS DISTINCT FROM OLD.model_id) THEN
    RAISE EXCEPTION 'design content is immutable once produced' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status <> OLD.status AND NOT (
       (OLD.status = 'GENERATING' AND NEW.status IN ('IN_REVIEW', 'FAILED'))
    OR (OLD.status = 'FAILED' AND NEW.status = 'GENERATING')
    OR (OLD.status = 'IN_REVIEW' AND NEW.status = 'SUPERSEDED')
    OR (OLD.status = 'IN_REVIEW' AND NEW.status = 'APPROVED'
        AND EXISTS (SELECT 1 FROM app.tdd_decision d WHERE d.tdd_version_id = OLD.id AND d.kind = 'APPROVED'))
    OR (OLD.status = 'APPROVED' AND NEW.status = 'SUPERSEDED')) THEN
    RAISE EXCEPTION 'design status % cannot become %', OLD.status, NEW.status USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status <> OLD.status THEN
    NEW.status_at := clock_timestamp();
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.tdd_version_guard() FROM PUBLIC;
CREATE TRIGGER tdd_version_guard BEFORE INSERT OR UPDATE OR DELETE ON app.tdd_version
  FOR EACH ROW EXECUTE FUNCTION app.tdd_version_guard();

CREATE FUNCTION app.tdd_item_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'design items are immutable' USING ERRCODE = 'P0001';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM app.tdd_version v WHERE v.id = NEW.tdd_version_id AND v.status = 'GENERATING') THEN
    RAISE EXCEPTION 'design items are written only while the design is generated' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.tdd_item_guard() FROM PUBLIC;
CREATE TRIGGER tdd_item_guard BEFORE INSERT OR UPDATE OR DELETE ON app.tdd_item
  FOR EACH ROW EXECUTE FUNCTION app.tdd_item_guard();

CREATE FUNCTION app.tdd_decision_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  member uuid := app.current_member();
  owner uuid;
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'design decisions are append-only' USING ERRCODE = 'P0001';
  END IF;
  SELECT created_by INTO owner FROM app.requirement WHERE id = NEW.requirement_id;
  IF NOT EXISTS (SELECT 1 FROM app.tdd_version v WHERE v.id = NEW.tdd_version_id
                 AND v.requirement_id = NEW.requirement_id AND v.status = 'IN_REVIEW') THEN
    RAISE EXCEPTION 'only a design in review can be decided' USING ERRCODE = 'P0001';
  END IF;
  IF member IS NULL OR member IS DISTINCT FROM NEW.decided_by THEN
    RAISE EXCEPTION 'a design decision is made by the signed-in member' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.kind IN ('FLAG_ACCEPTED', 'AUTHOR_CONFIRMED') AND member <> owner THEN
    RAISE EXCEPTION 'only the requirement''s author reviews and confirms its design' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.kind = 'APPROVED' AND (member = owner OR NOT app.is_brd_approver(NEW.project_id)
     OR NOT EXISTS (SELECT 1 FROM app.tdd_decision d WHERE d.tdd_version_id = NEW.tdd_version_id
                    AND d.kind = 'AUTHOR_CONFIRMED')) THEN
    RAISE EXCEPTION 'a BRD Approver other than the author approves a confirmed design' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.kind = 'CHANGES_REQUESTED' AND member <> owner AND NOT app.is_brd_approver(NEW.project_id) THEN
    RAISE EXCEPTION 'changes are requested by the author or a BRD Approver' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.tdd_decision_guard() FROM PUBLIC;
CREATE TRIGGER tdd_decision_guard BEFORE INSERT OR UPDATE OR DELETE ON app.tdd_decision
  FOR EACH ROW EXECUTE FUNCTION app.tdd_decision_guard();

--changeset sdlc:0044-tdd-rls splitStatements:false
ALTER TABLE app.tdd_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.tdd_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.tdd_version
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());
CREATE POLICY project_access ON app.tdd_version AS RESTRICTIVE
  USING (app.current_member() IS NULL OR app.member_sees_project(project_id));
ALTER TABLE app.tdd_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.tdd_item FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.tdd_item
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());
ALTER TABLE app.tdd_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.tdd_decision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.tdd_decision
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());
CREATE POLICY project_access ON app.tdd_decision AS RESTRICTIVE
  USING (app.current_member() IS NULL OR app.member_sees_project(project_id));

--changeset sdlc:0044-tdd-job-event splitStatements:false
ALTER TABLE app.job_type DROP CONSTRAINT job_type_code_check;
ALTER TABLE app.job_type ADD CONSTRAINT job_type_code_check CHECK (code IN ('REPO_VALIDATE', 'DOCUMENT_SCAN',
  'DOCUMENT_EXTRACT', 'BA_ANALYSIS', 'REPO_VERIFY', 'REPO_SNAPSHOT', 'REPO_INDEX', 'BA_COMMIT_RESULT', 'SECURITY_SCAN',
  'ENV_VERIFY', 'ENV_PIN', 'TDD_GENERATION'));
INSERT INTO app.job_type (code, max_attempts, max_infra_failures, backoff_base_seconds, backoff_cap_seconds,
                          lease_seconds, max_runtime_seconds, max_retry_seconds)
  VALUES ('TDD_GENERATION', 3, 10, 30, 900, 300, 1800, 86400);
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
  'SourcesRoundOpened', 'TddRequested'));

--changeset sdlc:0044-architect-prompts
INSERT INTO app.prompt_version (prompt_id, version, agent, text_sha256, schema_version, status) VALUES
  ('arch.files', 1, 'ARCHITECT', '8ce29578225173f23e3f7a18784d32217f56fb4bb60f58940c44d164d0bbba74', 1, 'ACTIVE'),
  ('arch.design', 1, 'ARCHITECT', 'd8ff25c1d0224595b2faed6d11f8d91fc342d1e08ff8d50931fdc7dac49d82b4', 1, 'ACTIVE');

--changeset sdlc:0044-tdd-grants
GRANT SELECT ON app.tdd_version, app.tdd_item, app.tdd_decision TO sdlc_app, sdlc_worker, sdlc_retention;
GRANT INSERT (tenant_id, project_id, requirement_id, version_no, brd_version_id, status, change_request, requested_by)
  ON app.tdd_version TO sdlc_app;
GRANT UPDATE (status, attempts, failure_code) ON app.tdd_version TO sdlc_app;
GRANT INSERT ON app.tdd_decision TO sdlc_app;
GRANT UPDATE (status, summary, open_questions, content_hash, model_provider, model_id, failure_code)
  ON app.tdd_version TO sdlc_worker;
GRANT INSERT ON app.tdd_item TO sdlc_worker;
