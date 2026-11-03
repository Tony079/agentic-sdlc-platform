--liquibase formatted sql

--changeset sdlc:0007-workflow-instance
-- One workflow instance per requirement (TDD 9.1), pinned to a definition version. projected_state is the
-- PRD section 6 state shown to users. It can only change together with a matching transition row: the
-- deferred foreign key below requires (id, last_seq, projected_state) to equal the latest transition's
-- (instance_id, seq, to_state) at commit.
CREATE TABLE app.workflow_instance (
  id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id          uuid        NOT NULL REFERENCES app.tenant (id),
  requirement_id     uuid        NOT NULL,
  definition_version text        NOT NULL CHECK (definition_version = 'R1-v1'),
  projected_state    text        NOT NULL CHECK (projected_state IN (
                       'DRAFT', 'SUBMITTED', 'MANUAL_INTAKE', 'REPO_VALIDATING', 'AWAITING_DOCUMENTS',
                       'AWAITING_DOCUMENT_DECISION', 'ANALYSING', 'AWAITING_CLARIFICATION', 'BRD_DRAFTED',
                       'INTERNAL_REVIEW', 'REVISION_REQUESTED', 'INTERNAL_APPROVED', 'SHARED_WITH_CLIENT',
                       'CLIENT_CHANGES_REQUESTED', 'REJECTED', 'FAILED', 'CANCELLED')),
  last_seq           int         NOT NULL CHECK (last_seq >= 1),
  version            bigint      NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (requirement_id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id)
);

--changeset sdlc:0007-workflow-transition
-- Append-only transition history (PRD 6B "Transition event": every state change with actor and reason).
-- Each row links to its predecessor's (seq, to_state), so history is a single unbroken chain whose from_state
-- always equals the previous to_state. The CHECK lists the legal transitions enabled so far; each later
-- milestone extends it in a reviewed migration (Milestone 2: T01 submit, T29 cancel).
CREATE TABLE app.workflow_transition (
  tenant_id     uuid        NOT NULL,
  instance_id   uuid        NOT NULL,
  seq           int         NOT NULL CHECK (seq >= 1),
  prev_seq      int,
  from_state    text,
  to_state      text        NOT NULL,
  transition    text        NOT NULL CHECK (transition IN ('CREATE', 'T01_SUBMIT', 'T29_CANCEL')),
  actor_type    text        NOT NULL CHECK (actor_type IN ('USER', 'SYSTEM')),
  actor_id      uuid,
  reason        text        CHECK (length(reason) BETWEEN 1 AND 500),
  occurred_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (instance_id, seq),
  UNIQUE (tenant_id, instance_id, seq, to_state),
  CHECK ((seq = 1 AND prev_seq IS NULL AND from_state IS NULL AND to_state = 'DRAFT' AND transition = 'CREATE')
      OR (seq > 1 AND prev_seq = seq - 1 AND from_state IS NOT NULL)),
  CHECK (from_state IS NULL
      OR (from_state, to_state, transition) IN (('DRAFT', 'SUBMITTED', 'T01_SUBMIT'),
                                                ('DRAFT', 'CANCELLED', 'T29_CANCEL'),
                                                ('SUBMITTED', 'CANCELLED', 'T29_CANCEL'))),
  FOREIGN KEY (tenant_id, instance_id) REFERENCES app.workflow_instance (tenant_id, id),
  FOREIGN KEY (tenant_id, instance_id, prev_seq, from_state)
    REFERENCES app.workflow_transition (tenant_id, instance_id, seq, to_state)
);

--changeset sdlc:0007-workflow-instance-matches-history
ALTER TABLE app.workflow_instance ADD CONSTRAINT workflow_instance_matches_latest_transition
  FOREIGN KEY (tenant_id, id, last_seq, projected_state)
  REFERENCES app.workflow_transition (tenant_id, instance_id, seq, to_state)
  DEFERRABLE INITIALLY DEFERRED;
