--liquibase formatted sql

--changeset sdlc:0021-business-reviewer-capability
-- R1-M6 (PRD section 4, TDD 19.3): the Business Reviewer is an internal staff role, modelled like REPOSITORY_REGISTER
-- as a capability granted through the two-person provisioning path (O-06, O-13, S-02). It gives no tenant access by
-- itself; an active tenant assignment is still required.
ALTER TABLE app.staff_capability_grant DROP CONSTRAINT staff_capability_grant_capability_check;
ALTER TABLE app.staff_capability_grant ADD CONSTRAINT staff_capability_grant_capability_check
  CHECK (capability IN ('STAFF_ADMIN', 'REPOSITORY_REGISTER', 'BUSINESS_REVIEWER'));

--changeset sdlc:0021-approval
-- TDD 19.2: immutable, version-bound approval record (Release 1: BRD_REVIEW only). The approver comes from the session.
CREATE TABLE app.approval (
  id                   uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id            uuid        NOT NULL,
  approval_type        text        NOT NULL CHECK (approval_type = 'BRD_REVIEW'),
  brd_version_id       uuid        NOT NULL,
  content_hash         text        NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
  input_set_hash       text        NOT NULL CHECK (input_set_hash ~ '^[0-9a-f]{64}$'),
  approver_staff_id    uuid        NOT NULL REFERENCES app.staff_user (id),
  decision             text        NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED', 'REVISION_REQUESTED')),
  reason               text        CHECK (length(reason) <= 2000),
  -- T22: structured correction notes [{itemKey, instruction, authorStaffId}] passed to the next analysis (16.6).
  notes                jsonb       CHECK (notes IS NULL OR (jsonb_typeof(notes) = 'array' AND octet_length(notes::text) <= 65536)),
  decided_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
  status               text        NOT NULL DEFAULT 'VALID' CHECK (status IN ('VALID', 'INVALIDATED')),
  invalidated_by_event text        CHECK (invalidated_by_event ~ '^[A-Za-z]{1,64}$'),
  invalidated_at       timestamptz,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, brd_version_id) REFERENCES app.brd_version (tenant_id, id),
  CHECK ((status = 'INVALIDATED') = (invalidated_at IS NOT NULL))
);
CREATE UNIQUE INDEX approval_one_valid_uq ON app.approval (tenant_id, brd_version_id, approval_type)
  WHERE status = 'VALID' AND decision = 'APPROVED';

-- TDD 16.7 (O-08 provisional default): each FLAGGED item is acknowledged individually before approval.
CREATE TABLE app.brd_flag_ack (
  tenant_id       uuid        NOT NULL,
  brd_version_id  uuid        NOT NULL,
  item_key        text        NOT NULL CHECK (item_key ~ '^[a-z0-9-]{1,80}$'),
  staff_id        uuid        NOT NULL REFERENCES app.staff_user (id),
  note            text        CHECK (length(note) <= 1000),
  acknowledged_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, brd_version_id, item_key),
  FOREIGN KEY (tenant_id, brd_version_id) REFERENCES app.brd_version (tenant_id, id)
);

-- T27: the client's change request on a released BRD, input to the next analysis (T28).
CREATE TABLE app.client_change_request (
  id             uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id      uuid        NOT NULL,
  requirement_id uuid        NOT NULL,
  brd_version_id uuid        NOT NULL,
  text           text        NOT NULL CHECK (length(text) BETWEEN 10 AND 5000),
  requested_by   uuid        NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, brd_version_id) REFERENCES app.brd_version (tenant_id, id),
  FOREIGN KEY (tenant_id, requested_by) REFERENCES app.app_user (tenant_id, id)
);

-- In-app notifications only (PRD AC-EXT-40). Content-free: a kind and a reference.
CREATE TABLE app.notification (
  id             uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id      uuid        NOT NULL,
  recipient_id   uuid        NOT NULL,
  kind           text        NOT NULL CHECK (kind ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  requirement_id uuid,
  created_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  read_at        timestamptz,
  PRIMARY KEY (id),
  FOREIGN KEY (tenant_id, recipient_id) REFERENCES app.app_user (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id)
);
CREATE INDEX notification_recipient_ix ON app.notification (tenant_id, recipient_id, created_at DESC);

--changeset sdlc:0021-approval-guards splitStatements:false
-- Approval content is immutable; only VALID -> INVALIDATED. Four-eyes (TDD 16.6, 19.1): a reviewer who authored
-- correction notes for the version's input set cannot approve that version.
CREATE FUNCTION app.approval_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'VALID' THEN
      RAISE EXCEPTION 'an approval is created VALID' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.decision = 'APPROVED' AND EXISTS (
         SELECT 1 FROM app.brd_version v JOIN app.analysis_input_set s ON s.id = v.input_set_id,
                jsonb_array_elements(s.corrections) c
         WHERE v.id = NEW.brd_version_id AND c ->> 'authorStaffId' = NEW.approver_staff_id::text) THEN
      RAISE EXCEPTION 'a reviewer who authored corrections of this version cannot approve it' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.approval_type <> OLD.approval_type OR NEW.brd_version_id <> OLD.brd_version_id
     OR NEW.content_hash <> OLD.content_hash OR NEW.input_set_hash <> OLD.input_set_hash
     OR NEW.approver_staff_id <> OLD.approver_staff_id OR NEW.decision <> OLD.decision
     OR NEW.notes IS DISTINCT FROM OLD.notes OR NEW.reason IS DISTINCT FROM OLD.reason
     OR NEW.decided_at <> OLD.decided_at THEN
    RAISE EXCEPTION 'approval % is immutable', OLD.id USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status = 'INVALIDATED' AND NEW.status <> OLD.status THEN
    RAISE EXCEPTION 'approval % is already invalidated', OLD.id USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.approval_guard() FROM PUBLIC;
CREATE TRIGGER approval_guard BEFORE INSERT OR UPDATE ON app.approval FOR EACH ROW EXECUTE FUNCTION app.approval_guard();

--changeset sdlc:0021-review-rls splitStatements:false
DO $rls$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['approval', 'brd_flag_ack', 'client_change_request', 'notification'] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON app.%I '
                   'USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant())', t);
  END LOOP;
END
$rls$;

--changeset sdlc:0021-review-transitions
-- R1-M6 transitions (TDD 9.3): T21/T22/T23 by a Business Reviewer (staff), T27 by the client, T24/T25/T28 by the
-- engine; T29 (owner cancel) from every non-terminal state now reachable. No T26 (admin retry, O-27) and no T30.
ALTER TABLE app.workflow_transition DROP CONSTRAINT workflow_transition_actor_type_check;
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_actor_type_check
  CHECK (actor_type IN ('USER', 'SYSTEM', 'STAFF'));
ALTER TABLE app.workflow_transition DROP CONSTRAINT workflow_transition_transition_check;
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_transition_check
  CHECK (transition IN ('CREATE', 'T01_SUBMIT', 'T02_ROUTE_REPOSITORY', 'T03_ROUTE_MANUAL_INTAKE',
                        'T06_REPO_VALIDATED', 'T07_REPO_VALIDATED_DOCUMENT_DECISION', 'T09_REPO_VALIDATION_FAILED',
                        'T11_START_ANALYSIS', 'T14_ANALYSIS_STALE_WAIT', 'T15_ANALYSIS_RESTART',
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
                                                ('AWAITING_DOCUMENTS', 'ANALYSING', 'T11_START_ANALYSIS'),
                                                ('ANALYSING', 'AWAITING_DOCUMENTS', 'T14_ANALYSIS_STALE_WAIT'),
                                                ('ANALYSING', 'ANALYSING', 'T15_ANALYSIS_RESTART'),
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
                                                ('BRD_DRAFTED', 'CANCELLED', 'T29_CANCEL'),
                                                ('INTERNAL_REVIEW', 'CANCELLED', 'T29_CANCEL'),
                                                ('REVISION_REQUESTED', 'CANCELLED', 'T29_CANCEL'),
                                                ('INTERNAL_APPROVED', 'CANCELLED', 'T29_CANCEL'),
                                                ('SHARED_WITH_CLIENT', 'CANCELLED', 'T29_CANCEL'),
                                                ('CLIENT_CHANGES_REQUESTED', 'CANCELLED', 'T29_CANCEL')));
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_reviewer_actor_check
  CHECK (transition NOT IN ('T21_APPROVE_INTERNAL', 'T22_REQUEST_REVISION', 'T23_REJECT')
         OR (actor_type = 'STAFF' AND actor_id IS NOT NULL));
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_client_actor_check
  CHECK (transition NOT IN ('T27_CLIENT_CHANGES', 'T01_SUBMIT', 'T29_CANCEL')
         OR (actor_type = 'USER' AND actor_id IS NOT NULL));
ALTER TABLE app.workflow_transition DROP CONSTRAINT workflow_transition_engine_actor_check;
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_engine_actor_check
  CHECK (transition NOT IN ('T02_ROUTE_REPOSITORY', 'T03_ROUTE_MANUAL_INTAKE', 'T06_REPO_VALIDATED',
                            'T07_REPO_VALIDATED_DOCUMENT_DECISION', 'T09_REPO_VALIDATION_FAILED',
                            'T11_START_ANALYSIS', 'T14_ANALYSIS_STALE_WAIT', 'T15_ANALYSIS_RESTART',
                            'T18_BRD_DRAFTED', 'T19_ANALYSIS_FAILED', 'T20_QUEUE_REVIEW', 'T24_REANALYSE_REVISION',
                            'T25_RELEASE_TO_CLIENT', 'T28_REANALYSE_CLIENT_CHANGES')
         OR (actor_type = 'SYSTEM' AND actor_id IS NULL));

UPDATE app.workflow_definition SET transitions = transitions || '[
  {"transition": "T21_APPROVE_INTERNAL", "from": "INTERNAL_REVIEW", "to": "INTERNAL_APPROVED", "actor": "BUSINESS_REVIEWER"},
  {"transition": "T22_REQUEST_REVISION", "from": "INTERNAL_REVIEW", "to": "REVISION_REQUESTED", "actor": "BUSINESS_REVIEWER"},
  {"transition": "T23_REJECT", "from": "INTERNAL_REVIEW", "to": "REJECTED", "actor": "BUSINESS_REVIEWER"},
  {"transition": "T24_REANALYSE_REVISION", "from": "REVISION_REQUESTED", "to": "ANALYSING", "actor": "ENGINE"},
  {"transition": "T25_RELEASE_TO_CLIENT", "from": "INTERNAL_APPROVED", "to": "SHARED_WITH_CLIENT", "actor": "ENGINE"},
  {"transition": "T27_CLIENT_CHANGES", "from": "SHARED_WITH_CLIENT", "to": "CLIENT_CHANGES_REQUESTED", "actor": "OWNER"},
  {"transition": "T28_REANALYSE_CLIENT_CHANGES", "from": "CLIENT_CHANGES_REQUESTED", "to": "ANALYSING", "actor": "ENGINE"},
  {"transition": "T29_CANCEL", "from": "REVISION_REQUESTED", "to": "CANCELLED", "actor": "OWNER"},
  {"transition": "T29_CANCEL", "from": "INTERNAL_APPROVED", "to": "CANCELLED", "actor": "OWNER"},
  {"transition": "T29_CANCEL", "from": "SHARED_WITH_CLIENT", "to": "CANCELLED", "actor": "OWNER"},
  {"transition": "T29_CANCEL", "from": "CLIENT_CHANGES_REQUESTED", "to": "CANCELLED", "actor": "OWNER"}
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
  'RepositorySnapshotIndexed', 'ApprovalInvalidated'));

--changeset sdlc:0021-review-grants
-- API: reviewers record decisions (T21-T23) and flag acknowledgements; clients read released versions and request
-- changes; users read their notifications. Worker: release (T25) re-checks, invalidation, notifications.
GRANT SELECT, INSERT ON app.approval, app.brd_flag_ack, app.client_change_request TO sdlc_app;
GRANT UPDATE (status, status_at) ON app.brd_version TO sdlc_app;
GRANT SELECT ON app.notification TO sdlc_app;
-- The approval's stale-input check (TDD 19.3) recomputes the input set: extraction metadata only, no text.
GRANT SELECT (tenant_id, version_id, extraction_no, outcome, output_sha256) ON app.document_extraction TO sdlc_app;
GRANT UPDATE (read_at) ON app.notification TO sdlc_app;
GRANT SELECT ON app.approval, app.client_change_request, app.brd_flag_ack TO sdlc_worker;
GRANT UPDATE (status, invalidated_by_event, invalidated_at) ON app.approval TO sdlc_worker;
GRANT SELECT, INSERT ON app.notification TO sdlc_worker;
