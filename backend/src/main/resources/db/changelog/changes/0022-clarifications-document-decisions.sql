--liquibase formatted sql

--changeset sdlc:0022-clarification
-- PRD FR-CLR-01..14, TDD 17. Created only by the engine (from a BA result) or by a Business Reviewer during review.
-- assigned_role: REQUIREMENT_OWNER (the client user who owns the requirement; C-18 keeps requirements owner-only, so
-- client questions go to the owner) or BUSINESS_REVIEWER (internal analyst, FR-CLR-13). The question text is never
-- written to the audit trail (17.7).
CREATE TABLE app.clarification (
  id                    uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id             uuid        NOT NULL,
  requirement_id        uuid        NOT NULL,
  source                text        NOT NULL CHECK (source IN ('AGENT', 'REVIEWER')),
  stage_execution_id    uuid,
  brd_version_id        uuid,
  revision_no           int         NOT NULL CHECK (revision_no >= 1),
  commit_sha            text        NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
  input_hash            text        NOT NULL CHECK (input_hash ~ '^[0-9a-f]{64}$'),
  blocking              boolean     NOT NULL,
  assigned_role         text        NOT NULL CHECK (assigned_role IN ('REQUIREMENT_OWNER', 'BUSINESS_REVIEWER')),
  status                text        NOT NULL DEFAULT 'OPEN'
                                    CHECK (status IN ('OPEN', 'NEEDS_INFO', 'ANSWERED', 'RESOLVED', 'REJECTED', 'WITHDRAWN')),
  question              text        NOT NULL CHECK (length(question) BETWEEN 1 AND 2000),
  why_it_matters        text        CHECK (length(why_it_matters) <= 2000),
  interpretations       jsonb       NOT NULL DEFAULT '[]'::jsonb
                                    CHECK (jsonb_typeof(interpretations) = 'array' AND octet_length(interpretations::text) <= 16384),
  recommended_option    text        CHECK (length(recommended_option) <= 2000),
  claimed_by            uuid,
  created_at            timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at            timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, stage_execution_id) REFERENCES app.stage_execution (tenant_id, id),
  FOREIGN KEY (tenant_id, brd_version_id) REFERENCES app.brd_version (tenant_id, id),
  CHECK ((source = 'AGENT') = (stage_execution_id IS NOT NULL)),
  CHECK ((source = 'REVIEWER') = (brd_version_id IS NOT NULL))
);
CREATE INDEX clarification_requirement_ix ON app.clarification (tenant_id, requirement_id, status);

-- The conversation (append-only). Every answer records the authenticated identity of its author (FR-CLR-14).
CREATE TABLE app.clarification_message (
  id               uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id        uuid        NOT NULL,
  clarification_id uuid        NOT NULL,
  kind             text        NOT NULL CHECK (kind IN ('ANSWER', 'INFO_REQUEST', 'REJECTION', 'REOPEN', 'REASSIGN',
                                                        'WITHDRAWAL')),
  author_type      text        NOT NULL CHECK (author_type IN ('USER', 'STAFF', 'SYSTEM')),
  author_id        uuid,
  text             text        NOT NULL CHECK (length(text) BETWEEN 1 AND 8000),
  created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  FOREIGN KEY (tenant_id, clarification_id) REFERENCES app.clarification (tenant_id, id),
  CHECK ((author_type = 'SYSTEM') = (author_id IS NULL))
);
CREATE INDEX clarification_message_ix ON app.clarification_message (tenant_id, clarification_id, created_at);

--changeset sdlc:0022-document-decision
-- TDD 9.4 (b), AC-EXT-23/31/32: a recorded decision about the exact document version that needs one. EXCLUDE by a
-- Business Reviewer; ACKNOWLEDGE_EXCLUSION by the requirement owner. Immutable.
CREATE TABLE app.document_decision (
  id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id           uuid        NOT NULL,
  requirement_id      uuid        NOT NULL,
  document_version_id uuid        NOT NULL,
  version_state       text        NOT NULL CHECK (version_state IN ('UNSUPPORTED_CONTENT', 'EXTRACTION_FAILED_FINAL',
                                                                    'UPLOAD_FAILED')),
  decision            text        NOT NULL CHECK (decision IN ('EXCLUDE', 'ACKNOWLEDGE_EXCLUSION')),
  actor_type          text        NOT NULL CHECK (actor_type IN ('USER', 'STAFF')),
  actor_id            uuid        NOT NULL,
  reason              text        NOT NULL CHECK (length(reason) BETWEEN 3 AND 1000),
  decided_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, document_version_id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, document_version_id) REFERENCES app.document_version (tenant_id, id),
  CHECK ((decision = 'EXCLUDE') = (actor_type = 'STAFF'))
);

--changeset sdlc:0022-clarification-guard splitStatements:false
-- Identity and question are immutable; terminal states are final except a reviewer reopening RESOLVED/REJECTED.
CREATE FUNCTION app.clarification_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF NEW.question <> OLD.question OR NEW.requirement_id <> OLD.requirement_id OR NEW.input_hash <> OLD.input_hash
     OR NEW.source <> OLD.source OR NEW.created_at <> OLD.created_at THEN
    RAISE EXCEPTION 'clarification % is immutable', OLD.id USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status = 'WITHDRAWN' AND NEW.status <> OLD.status THEN
    RAISE EXCEPTION 'clarification % was withdrawn', OLD.id USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.clarification_guard() FROM PUBLIC;
CREATE TRIGGER clarification_guard BEFORE UPDATE ON app.clarification
  FOR EACH ROW EXECUTE FUNCTION app.clarification_guard();

--changeset sdlc:0022-rls splitStatements:false
DO $rls$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['clarification', 'clarification_message', 'document_decision'] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON app.%I '
                   'USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant())', t);
  END LOOP;
END
$rls$;

--changeset sdlc:0022-pending-paused
-- A pending BA result whose blocking clarifications must be answered first is PAUSED (never committed as a BRD).
ALTER TABLE app.ba_result_pending DROP CONSTRAINT ba_result_pending_status_check;
ALTER TABLE app.ba_result_pending ADD CONSTRAINT ba_result_pending_status_check
  CHECK (status IN ('PENDING', 'COMMITTED', 'REJECTED_STALE', 'REJECTED_INVALID', 'PAUSED'));

--changeset sdlc:0022-transitions-events
-- T10, T12, T13 (document decisions) and T16, T17 (clarifications); T29 from AWAITING_CLARIFICATION.
ALTER TABLE app.workflow_transition DROP CONSTRAINT workflow_transition_transition_check;
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_transition_check
  CHECK (transition IN ('CREATE', 'T01_SUBMIT', 'T02_ROUTE_REPOSITORY', 'T03_ROUTE_MANUAL_INTAKE',
                        'T06_REPO_VALIDATED', 'T07_REPO_VALIDATED_DOCUMENT_DECISION', 'T09_REPO_VALIDATION_FAILED',
                        'T10_DOCUMENT_DECISION_NEEDED', 'T11_START_ANALYSIS', 'T12_DOCUMENT_DECISION_RECORDED',
                        'T13_START_ANALYSIS_AFTER_DECISION', 'T14_ANALYSIS_STALE_WAIT', 'T15_ANALYSIS_RESTART',
                        'T16_AWAIT_CLARIFICATION', 'T17_RESUME_ANALYSIS',
                        'T18_BRD_DRAFTED', 'T19_ANALYSIS_FAILED', 'T20_QUEUE_REVIEW',
                        'T21_APPROVE_INTERNAL', 'T22_REQUEST_REVISION', 'T23_REJECT', 'T24_REANALYSE_REVISION',
                        'T25_RELEASE_TO_CLIENT', 'T27_CLIENT_CHANGES', 'T28_REANALYSE_CLIENT_CHANGES', 'T29_CANCEL'));
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
ALTER TABLE app.workflow_transition DROP CONSTRAINT workflow_transition_engine_actor_check;
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_engine_actor_check
  CHECK (transition NOT IN ('T02_ROUTE_REPOSITORY', 'T03_ROUTE_MANUAL_INTAKE', 'T06_REPO_VALIDATED',
                            'T07_REPO_VALIDATED_DOCUMENT_DECISION', 'T09_REPO_VALIDATION_FAILED',
                            'T10_DOCUMENT_DECISION_NEEDED', 'T11_START_ANALYSIS', 'T12_DOCUMENT_DECISION_RECORDED',
                            'T13_START_ANALYSIS_AFTER_DECISION', 'T14_ANALYSIS_STALE_WAIT', 'T15_ANALYSIS_RESTART',
                            'T16_AWAIT_CLARIFICATION', 'T17_RESUME_ANALYSIS',
                            'T18_BRD_DRAFTED', 'T19_ANALYSIS_FAILED', 'T20_QUEUE_REVIEW', 'T24_REANALYSE_REVISION',
                            'T25_RELEASE_TO_CLIENT', 'T28_REANALYSE_CLIENT_CHANGES')
         OR (actor_type = 'SYSTEM' AND actor_id IS NULL));

UPDATE app.workflow_definition SET transitions = transitions || '[
  {"transition": "T10_DOCUMENT_DECISION_NEEDED", "from": "AWAITING_DOCUMENTS", "to": "AWAITING_DOCUMENT_DECISION", "actor": "ENGINE"},
  {"transition": "T12_DOCUMENT_DECISION_RECORDED", "from": "AWAITING_DOCUMENT_DECISION", "to": "AWAITING_DOCUMENTS", "actor": "ENGINE"},
  {"transition": "T13_START_ANALYSIS_AFTER_DECISION", "from": "AWAITING_DOCUMENT_DECISION", "to": "ANALYSING", "actor": "ENGINE"},
  {"transition": "T16_AWAIT_CLARIFICATION", "from": "ANALYSING", "to": "AWAITING_CLARIFICATION", "actor": "ENGINE"},
  {"transition": "T17_RESUME_ANALYSIS", "from": "AWAITING_CLARIFICATION", "to": "ANALYSING", "actor": "ENGINE"},
  {"transition": "T29_CANCEL", "from": "AWAITING_CLARIFICATION", "to": "CANCELLED", "actor": "OWNER"}
]'::jsonb WHERE version = 'R1-v1';

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
  'RepositorySnapshotIndexed', 'ApprovalInvalidated', 'ClarificationAnswered'));

--changeset sdlc:0022-ba-prompt-v2
-- Phase B v2 adds agent-raised clarifications (FR-CLR-01). v1 stays registered (immutable) and is no longer used.
INSERT INTO app.prompt_version (prompt_id, version, agent, text_sha256, schema_version, status) VALUES
  ('ba.phase-b', 2, 'BA', '3d1842845379b30115d8424499b7cba1963537c2b4ca53049c0ed78eb1b62bb5', 2, 'ACTIVE');

--changeset sdlc:0022-grants
-- API: inbox, answers, reviewer clarifications and decisions; document decisions. Worker: engine-created
-- clarifications, resolution, withdrawal, notifications.
GRANT SELECT, INSERT ON app.clarification, app.clarification_message, app.document_decision TO sdlc_app;
GRANT UPDATE (status, assigned_role, claimed_by, updated_at) ON app.clarification TO sdlc_app;
GRANT INSERT ON app.notification TO sdlc_app;
GRANT SELECT, INSERT ON app.clarification, app.clarification_message TO sdlc_worker;
GRANT UPDATE (status, updated_at) ON app.clarification TO sdlc_worker;
GRANT SELECT ON app.document_decision TO sdlc_worker;
