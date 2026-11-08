--liquibase formatted sql

--changeset sdlc:0024-staff-capabilities
-- Owner decisions 2026-11-08: dedicated capabilities, never implied by another capability or by STAFF_ADMIN.
--   TENANT_DELETE (O-25 tenant soft deletion), RELEASE_APPROVE (O-16 release), FEATURE_FLAG_ACK (O-08 feature flags),
--   JOB_RETRY (O-27 operator retry), BUSINESS_ANALYST (C-09 change-request assessment).
ALTER TABLE app.staff_capability_grant DROP CONSTRAINT staff_capability_grant_capability_check;
ALTER TABLE app.staff_capability_grant ADD CONSTRAINT staff_capability_grant_capability_check
  CHECK (capability IN ('STAFF_ADMIN', 'REPOSITORY_REGISTER', 'BUSINESS_REVIEWER', 'TENANT_DELETE', 'RELEASE_APPROVE',
                        'FEATURE_FLAG_ACK', 'JOB_RETRY', 'BUSINESS_ANALYST'));

--changeset sdlc:0024-manual-intake-transitions
-- C-06/C-07 (owner decision 2026-11-08): a Business Reviewer resumes (T04, MANUAL_INTAKE -> REPO_VALIDATING) or closes
-- (T05, MANUAL_INTAKE -> CANCELLED) a manual-intake requirement. Both are staff-only transitions.
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
                        'T25_RELEASE_TO_CLIENT', 'T27_CLIENT_CHANGES', 'T28_REANALYSE_CLIENT_CHANGES', 'T29_CANCEL'));
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
ALTER TABLE app.workflow_transition DROP CONSTRAINT workflow_transition_reviewer_actor_check;
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_reviewer_actor_check
  CHECK (transition NOT IN ('T21_APPROVE_INTERNAL', 'T22_REQUEST_REVISION', 'T23_REJECT', 'T04_RESUME_MANUAL_INTAKE',
                            'T05_CLOSE_MANUAL_INTAKE')
         OR (actor_type = 'STAFF' AND actor_id IS NOT NULL));
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_manual_intake_reason_check
  CHECK (transition NOT IN ('T04_RESUME_MANUAL_INTAKE', 'T05_CLOSE_MANUAL_INTAKE') OR reason IS NOT NULL);

UPDATE app.workflow_definition SET transitions = transitions || '[
  {"transition": "T04_RESUME_MANUAL_INTAKE", "from": "MANUAL_INTAKE", "to": "REPO_VALIDATING", "actor": "BUSINESS_REVIEWER"},
  {"transition": "T05_CLOSE_MANUAL_INTAKE", "from": "MANUAL_INTAKE", "to": "CANCELLED", "actor": "BUSINESS_REVIEWER"}
]'::jsonb WHERE version = 'R1-v1';

-- FR-REQ-11: the resume creates a new revision (kind EXISTING_REPOSITORY, the authorised repository URL). The content is
-- the owner's (created_by stays the owner, an app_user); the staff member who performed the conversion is recorded here.
ALTER TABLE app.requirement_revision ADD COLUMN converted_by_staff uuid REFERENCES app.staff_user (id);
GRANT INSERT (converted_by_staff) ON app.requirement_revision TO sdlc_app;

--changeset sdlc:0024-requirement-closure
-- O-25 closure definition (owner decision 2026-11-08): a requirement is closed only when it is in a terminal state
-- (CANCELLED or REJECTED in Release 1) AND no job, open clarification, open BRD version or pending approval remains.
-- The worker records the closure once, immutably, with the actor of the terminal transition. Retention counts from
-- closed_at. There is no reopening transition in Release 1; the row can never be updated or deleted.
CREATE TABLE app.requirement_closure (
  tenant_id       uuid        NOT NULL,
  requirement_id  uuid        NOT NULL,
  terminal_state  text        NOT NULL CHECK (terminal_state IN ('CANCELLED', 'REJECTED')),
  transition      text        NOT NULL CHECK (transition ~ '^T[0-9]{2}_[A-Z_]+$'),
  terminal_at     timestamptz NOT NULL,
  closed_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  closed_by_type  text        NOT NULL CHECK (closed_by_type IN ('USER', 'STAFF', 'SYSTEM')),
  closed_by_id    uuid,
  PRIMARY KEY (requirement_id),
  UNIQUE (tenant_id, requirement_id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  CHECK ((closed_by_type = 'SYSTEM') = (closed_by_id IS NULL)),
  CHECK (closed_at >= terminal_at)
);
ALTER TABLE app.requirement_closure ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.requirement_closure FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.requirement_closure
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());
GRANT SELECT, INSERT ON app.requirement_closure TO sdlc_worker;
GRANT SELECT ON app.requirement_closure TO sdlc_app, sdlc_retention;
