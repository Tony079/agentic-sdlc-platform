--liquibase formatted sql

--changeset sdlc:0020-analysis-input-set
-- R1-M5 (TDD 9.4, 9.6, 12.3): the frozen input set of one BA execution. Its SHA-256 is stored on the stage execution
-- and on the pending result; the commit step (Tx C) recomputes it from the current data and rejects a stale result.
CREATE TABLE app.analysis_input_set (
  id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id           uuid        NOT NULL,
  requirement_id      uuid        NOT NULL,
  revision_no         int         NOT NULL CHECK (revision_no >= 1),
  revision_hash       text        NOT NULL CHECK (revision_hash ~ '^[0-9a-f]{64}$'),
  repository_id       uuid        NOT NULL,
  commit_sha          text        NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
  snapshot_id         uuid        NOT NULL,
  documents           jsonb       NOT NULL CHECK (jsonb_typeof(documents) = 'array' AND octet_length(documents::text) <= 65536),
  corrections         jsonb       NOT NULL DEFAULT '[]'::jsonb
                                  CHECK (jsonb_typeof(corrections) = 'array' AND octet_length(corrections::text) <= 65536),
  input_hash          text        NOT NULL CHECK (input_hash ~ '^[0-9a-f]{64}$'),
  created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, repository_id) REFERENCES app.repository (tenant_id, id),
  FOREIGN KEY (tenant_id, snapshot_id) REFERENCES app.repository_snapshot (tenant_id, id)
);
CREATE INDEX analysis_input_set_requirement_ix ON app.analysis_input_set (tenant_id, requirement_id, created_at);

--changeset sdlc:0020-ba-result-pending
-- Tx P (TDD 12.4): the validated proposal of one BA run. Never approvable, never visible to reviewers or clients.
CREATE TABLE app.ba_result_pending (
  id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id          uuid        NOT NULL,
  requirement_id     uuid        NOT NULL,
  stage_execution_id uuid        NOT NULL,
  input_set_id       uuid        NOT NULL,
  input_hash         text        NOT NULL CHECK (input_hash ~ '^[0-9a-f]{64}$'),
  lease_epoch        bigint      NOT NULL CHECK (lease_epoch >= 0),
  proposal           jsonb       NOT NULL CHECK (jsonb_typeof(proposal) = 'object' AND octet_length(proposal::text) <= 524288),
  understanding      text        CHECK (length(understanding) <= 4000),
  model_provider     text        NOT NULL CHECK (length(model_provider) <= 64),
  model_id           text        NOT NULL CHECK (length(model_id) <= 128),
  prompt_phase_a     text        NOT NULL CHECK (length(prompt_phase_a) <= 80),
  prompt_phase_b     text        NOT NULL CHECK (length(prompt_phase_b) <= 80),
  status             text        NOT NULL DEFAULT 'PENDING'
                                 CHECK (status IN ('PENDING', 'COMMITTED', 'REJECTED_STALE', 'REJECTED_INVALID')),
  reason_code        text        CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  created_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
  decided_at         timestamptz,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (stage_execution_id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, stage_execution_id) REFERENCES app.stage_execution (tenant_id, id),
  FOREIGN KEY (tenant_id, input_set_id) REFERENCES app.analysis_input_set (tenant_id, id),
  CHECK ((status = 'PENDING') = (decided_at IS NULL))
);

--changeset sdlc:0020-brd
-- TDD 16.1/16.2: one BRD per requirement; versions are immutable once created (only status moves). Created only by the
-- trusted commit step (Tx C).
CREATE TABLE app.brd (
  id             uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id      uuid        NOT NULL,
  requirement_id uuid        NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, requirement_id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id)
);

CREATE TABLE app.brd_version (
  id                uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id         uuid        NOT NULL,
  brd_id            uuid        NOT NULL,
  requirement_id    uuid        NOT NULL,
  version_no        int         NOT NULL CHECK (version_no >= 1),
  pending_result_id uuid        NOT NULL,
  input_set_id      uuid        NOT NULL,
  input_hash        text        NOT NULL CHECK (input_hash ~ '^[0-9a-f]{64}$'),
  content_hash      text        NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
  understanding     text        CHECK (length(understanding) <= 4000),
  -- Documents not analysed, with their state (AC-DOC-12, AC-CORE-05); written by trusted code, not the model.
  excluded_documents jsonb      NOT NULL DEFAULT '[]'::jsonb
                                CHECK (jsonb_typeof(excluded_documents) = 'array'
                                       AND octet_length(excluded_documents::text) <= 65536),
  status            text        NOT NULL CHECK (status IN ('IN_REVIEW', 'APPROVED_INTERNAL', 'RELEASED', 'SUPERSEDED',
                                                            'WITHDRAWN', 'STALE')),
  model_provider    text        NOT NULL,
  model_id          text        NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  status_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (brd_id, version_no),
  UNIQUE (pending_result_id),
  FOREIGN KEY (tenant_id, brd_id) REFERENCES app.brd (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, pending_result_id) REFERENCES app.ba_result_pending (tenant_id, id),
  FOREIGN KEY (tenant_id, input_set_id) REFERENCES app.analysis_input_set (tenant_id, id)
);
CREATE UNIQUE INDEX brd_version_one_open_uq ON app.brd_version (tenant_id, requirement_id)
  WHERE status IN ('IN_REVIEW', 'APPROVED_INTERNAL');

CREATE TABLE app.brd_item (
  id                 uuid    NOT NULL DEFAULT gen_random_uuid(),
  tenant_id          uuid    NOT NULL,
  brd_version_id     uuid    NOT NULL,
  ordinal            int     NOT NULL CHECK (ordinal >= 1),
  item_key           text    NOT NULL CHECK (item_key ~ '^[a-z0-9-]{1,80}$'),
  kind               text    NOT NULL CHECK (kind IN ('FACT', 'ASSUMPTION', 'RECOMMENDATION', 'QUESTION')),
  proposed_kind      text    NOT NULL CHECK (proposed_kind IN ('FACT', 'ASSUMPTION', 'RECOMMENDATION', 'QUESTION')),
  section            text    NOT NULL CHECK (section IN ('FACTS', 'ASSUMPTIONS', 'RISKS', 'IMPACTED_COMPONENTS',
                                                         'MISSING_REQUIREMENTS', 'RECOMMENDATIONS', 'QUESTIONS',
                                                         'UNSUPPORTED_REQUIREMENTS', 'EXCLUDED_DOCUMENTS')),
  statement          text    NOT NULL CHECK (length(statement) BETWEEN 1 AND 2000),
  risk               text    CHECK (length(risk) <= 500),
  component          text    CHECK (length(component) <= 200),
  support_state      text    NOT NULL CHECK (support_state IN ('VERIFIED', 'FLAGGED', 'UNSUPPORTED')),
  rejected_citations int     NOT NULL DEFAULT 0 CHECK (rejected_citations >= 0),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (brd_version_id, ordinal),
  UNIQUE (brd_version_id, item_key),
  FOREIGN KEY (tenant_id, brd_version_id) REFERENCES app.brd_version (tenant_id, id),
  -- A FACT is shown as a fact only with verified or flagged evidence (TDD 16.3); an unverifiable FACT is stored as an
  -- ASSUMPTION by the engine, never by the model.
  CHECK (kind <> 'FACT' OR support_state IN ('VERIFIED', 'FLAGGED'))
);

-- Only citations that passed the trusted checker are stored (TDD 16.3).
CREATE TABLE app.brd_item_evidence (
  id                  uuid    NOT NULL DEFAULT gen_random_uuid(),
  tenant_id           uuid    NOT NULL,
  brd_item_id         uuid    NOT NULL,
  type                text    NOT NULL CHECK (type IN ('REPOSITORY', 'DOCUMENT')),
  repository_id       uuid,
  commit_sha          text    CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
  path                text    CHECK (length(path) <= 1024),
  start_line          int     CHECK (start_line >= 1),
  end_line            int,
  range_sha256        text    CHECK (range_sha256 ~ '^[0-9a-f]{64}$'),
  document_version_id uuid,
  extraction_no       int,
  chunk_ordinal       int     CHECK (chunk_ordinal >= 1),
  chunk_label         text    CHECK (length(chunk_label) <= 200),
  quote               text    NOT NULL CHECK (length(quote) BETWEEN 1 AND 300),
  support_passed      boolean NOT NULL,
  PRIMARY KEY (id),
  FOREIGN KEY (tenant_id, brd_item_id) REFERENCES app.brd_item (tenant_id, id),
  FOREIGN KEY (tenant_id, repository_id) REFERENCES app.repository (tenant_id, id),
  FOREIGN KEY (tenant_id, document_version_id) REFERENCES app.document_version (tenant_id, id),
  CHECK ((type = 'REPOSITORY') = (repository_id IS NOT NULL AND commit_sha IS NOT NULL AND path IS NOT NULL
                                  AND start_line IS NOT NULL AND end_line >= start_line AND range_sha256 IS NOT NULL)),
  CHECK ((type = 'DOCUMENT') = (document_version_id IS NOT NULL AND extraction_no IS NOT NULL
                                AND chunk_ordinal IS NOT NULL))
);
CREATE INDEX brd_item_evidence_item_ix ON app.brd_item_evidence (tenant_id, brd_item_id);

--changeset sdlc:0020-brd-guards splitStatements:false
-- Immutability: version content, items and evidence never change after Tx C; a version's status only moves forward.
CREATE FUNCTION app.brd_version_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF NEW.content_hash <> OLD.content_hash OR NEW.input_hash <> OLD.input_hash OR NEW.version_no <> OLD.version_no
     OR NEW.brd_id <> OLD.brd_id OR NEW.pending_result_id <> OLD.pending_result_id THEN
    RAISE EXCEPTION 'BRD version % is immutable', OLD.id USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status IN ('RELEASED', 'SUPERSEDED', 'WITHDRAWN', 'STALE') AND NEW.status <> OLD.status
     AND NOT (OLD.status = 'RELEASED' AND NEW.status = 'SUPERSEDED') THEN
    RAISE EXCEPTION 'BRD version % is final (%)', OLD.id, OLD.status USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status = 'IN_REVIEW' AND NEW.status = 'RELEASED' THEN
    RAISE EXCEPTION 'a BRD version must be approved before release' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.brd_version_guard() FROM PUBLIC;
CREATE TRIGGER brd_version_guard BEFORE UPDATE ON app.brd_version
  FOR EACH ROW EXECUTE FUNCTION app.brd_version_guard();

CREATE FUNCTION app.ba_result_pending_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF OLD.status <> 'PENDING' THEN
    RAISE EXCEPTION 'pending result % was already decided', OLD.id USING ERRCODE = 'P0001';
  END IF;
  IF NEW.proposal <> OLD.proposal OR NEW.input_hash <> OLD.input_hash THEN
    RAISE EXCEPTION 'pending result % is immutable', OLD.id USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.ba_result_pending_guard() FROM PUBLIC;
CREATE TRIGGER ba_result_pending_guard BEFORE UPDATE ON app.ba_result_pending
  FOR EACH ROW EXECUTE FUNCTION app.ba_result_pending_guard();

--changeset sdlc:0020-analysis-rls splitStatements:false
DO $rls$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['analysis_input_set', 'ba_result_pending', 'brd', 'brd_version', 'brd_item',
                           'brd_item_evidence'] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON app.%I '
                   'USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant())', t);
  END LOOP;
END
$rls$;

--changeset sdlc:0020-analysis-jobs-events-transitions
ALTER TABLE app.job_type DROP CONSTRAINT job_type_code_check;
ALTER TABLE app.job_type ADD CONSTRAINT job_type_code_check CHECK (code IN ('REPO_VALIDATE', 'DOCUMENT_SCAN',
  'DOCUMENT_EXTRACT', 'BA_ANALYSIS', 'REPO_VERIFY', 'REPO_SNAPSHOT', 'REPO_INDEX', 'BA_COMMIT_RESULT'));
INSERT INTO app.job_type VALUES ('BA_COMMIT_RESULT', 3, 5, 10, 300, 120, 600);

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
  'RepositorySnapshotIndexed'));

-- R1-M5 transitions (TDD 9.3): T11, T14, T15, T18, T19, T20, and T29 from the newly reachable non-terminal states.
ALTER TABLE app.workflow_transition DROP CONSTRAINT workflow_transition_transition_check;
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_transition_check
  CHECK (transition IN ('CREATE', 'T01_SUBMIT', 'T02_ROUTE_REPOSITORY', 'T03_ROUTE_MANUAL_INTAKE',
                        'T06_REPO_VALIDATED', 'T07_REPO_VALIDATED_DOCUMENT_DECISION', 'T09_REPO_VALIDATION_FAILED',
                        'T11_START_ANALYSIS', 'T14_ANALYSIS_STALE_WAIT', 'T15_ANALYSIS_RESTART',
                        'T18_BRD_DRAFTED', 'T19_ANALYSIS_FAILED', 'T20_QUEUE_REVIEW', 'T29_CANCEL'));
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
                                                ('AWAITING_DOCUMENTS', 'ANALYSING', 'T11_START_ANALYSIS'),
                                                ('ANALYSING', 'AWAITING_DOCUMENTS', 'T14_ANALYSIS_STALE_WAIT'),
                                                ('ANALYSING', 'ANALYSING', 'T15_ANALYSIS_RESTART'),
                                                ('ANALYSING', 'BRD_DRAFTED', 'T18_BRD_DRAFTED'),
                                                ('ANALYSING', 'FAILED', 'T19_ANALYSIS_FAILED'),
                                                ('BRD_DRAFTED', 'INTERNAL_REVIEW', 'T20_QUEUE_REVIEW'),
                                                ('DRAFT', 'CANCELLED', 'T29_CANCEL'),
                                                ('SUBMITTED', 'CANCELLED', 'T29_CANCEL'),
                                                ('MANUAL_INTAKE', 'CANCELLED', 'T29_CANCEL'),
                                                ('REPO_VALIDATING', 'CANCELLED', 'T29_CANCEL'),
                                                ('AWAITING_DOCUMENTS', 'CANCELLED', 'T29_CANCEL'),
                                                ('AWAITING_DOCUMENT_DECISION', 'CANCELLED', 'T29_CANCEL'),
                                                ('FAILED', 'CANCELLED', 'T29_CANCEL'),
                                                ('ANALYSING', 'CANCELLED', 'T29_CANCEL'),
                                                ('BRD_DRAFTED', 'CANCELLED', 'T29_CANCEL'),
                                                ('INTERNAL_REVIEW', 'CANCELLED', 'T29_CANCEL')));
ALTER TABLE app.workflow_transition DROP CONSTRAINT workflow_transition_engine_actor_check;
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_engine_actor_check
  CHECK (transition NOT IN ('T02_ROUTE_REPOSITORY', 'T03_ROUTE_MANUAL_INTAKE', 'T06_REPO_VALIDATED',
                            'T07_REPO_VALIDATED_DOCUMENT_DECISION', 'T09_REPO_VALIDATION_FAILED',
                            'T11_START_ANALYSIS', 'T14_ANALYSIS_STALE_WAIT', 'T15_ANALYSIS_RESTART',
                            'T18_BRD_DRAFTED', 'T19_ANALYSIS_FAILED', 'T20_QUEUE_REVIEW')
         OR (actor_type = 'SYSTEM' AND actor_id IS NULL));

UPDATE app.workflow_definition SET transitions = transitions || '[
  {"transition": "T11_START_ANALYSIS", "from": "AWAITING_DOCUMENTS", "to": "ANALYSING", "actor": "ENGINE"},
  {"transition": "T14_ANALYSIS_STALE_WAIT", "from": "ANALYSING", "to": "AWAITING_DOCUMENTS", "actor": "ENGINE"},
  {"transition": "T15_ANALYSIS_RESTART", "from": "ANALYSING", "to": "ANALYSING", "actor": "ENGINE"},
  {"transition": "T18_BRD_DRAFTED", "from": "ANALYSING", "to": "BRD_DRAFTED", "actor": "ENGINE"},
  {"transition": "T19_ANALYSIS_FAILED", "from": "ANALYSING", "to": "FAILED", "actor": "ENGINE"},
  {"transition": "T20_QUEUE_REVIEW", "from": "BRD_DRAFTED", "to": "INTERNAL_REVIEW", "actor": "ENGINE"},
  {"transition": "T29_CANCEL", "from": "ANALYSING", "to": "CANCELLED", "actor": "OWNER"},
  {"transition": "T29_CANCEL", "from": "BRD_DRAFTED", "to": "CANCELLED", "actor": "OWNER"},
  {"transition": "T29_CANCEL", "from": "INTERNAL_REVIEW", "to": "CANCELLED", "actor": "OWNER"}
]'::jsonb WHERE version = 'R1-v1';

--changeset sdlc:0020-ba-prompts
-- Versioned BA prompts (TDD 13.4). The SHA-256 is of the exact resource file text (prompts/*.txt); the gateway refuses
-- a prompt whose text does not match. A changed prompt needs a new version row.
INSERT INTO app.prompt_version (prompt_id, version, agent, text_sha256, schema_version, status) VALUES
  ('ba.phase-a', 1, 'BA', '6b8667c8b9825ecd53bda1e18046cc37b591ca64bea837c41206305b700604d6', 1, 'ACTIVE'),
  ('ba.phase-b', 1, 'BA', 'ac849da5971c0f04dddfe24d68efeac10c5d217a990c078a7dea68c699ad8644', 1, 'ACTIVE');

--changeset sdlc:0020-analysis-grants
-- Worker: builds input sets, runs the BA agent (reads the requirement revision it analyses: TDD 12.3 input), records
-- pending results and, as the trusted engine (Tx C), the BRD. API: reads committed BRD data only (not pending results).
GRANT SELECT (id, tenant_id, project_id, created_by, current_revision_no) ON app.requirement TO sdlc_worker;
GRANT SELECT ON app.requirement_revision TO sdlc_worker;
GRANT SELECT, INSERT ON app.analysis_input_set, app.ba_result_pending, app.brd, app.brd_version, app.brd_item,
                        app.brd_item_evidence TO sdlc_worker;
GRANT UPDATE (status, reason_code, decided_at) ON app.ba_result_pending TO sdlc_worker;
GRANT UPDATE (status, status_at) ON app.brd_version TO sdlc_worker;
GRANT SELECT ON app.analysis_input_set, app.brd, app.brd_version, app.brd_item, app.brd_item_evidence TO sdlc_app;
