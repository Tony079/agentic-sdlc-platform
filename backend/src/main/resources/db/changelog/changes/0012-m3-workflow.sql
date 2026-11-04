--liquibase formatted sql

--changeset sdlc:0012-workflow-definition
-- Versioned workflow definitions (PRD 6A "Workflow definition (versioned)", TDD 9.1). Global reference data.
-- Release 1 ships one definition, R1-v1, containing only the Release 1 states (scope guard, TDD 9.10). The
-- engine refuses to start if its compiled transition table differs from the definition stored here.
-- Each milestone that enables transitions updates the stored list in a reviewed changeset.
CREATE TABLE app.workflow_definition (
  version     text        NOT NULL PRIMARY KEY CHECK (version ~ '^R[0-9]+-v[0-9]+$'),
  status      text        NOT NULL CHECK (status IN ('ACTIVE', 'RETIRED')),
  transitions jsonb       NOT NULL CHECK (jsonb_typeof(transitions) = 'array'),
  created_at  timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO app.workflow_definition (version, status, transitions) VALUES ('R1-v1', 'ACTIVE', '[
  {"transition": "T01_SUBMIT", "from": "DRAFT", "to": "SUBMITTED", "actor": "OWNER"},
  {"transition": "T02_ROUTE_REPOSITORY", "from": "SUBMITTED", "to": "REPO_VALIDATING", "actor": "ENGINE"},
  {"transition": "T03_ROUTE_MANUAL_INTAKE", "from": "SUBMITTED", "to": "MANUAL_INTAKE", "actor": "ENGINE"},
  {"transition": "T29_CANCEL", "from": "DRAFT", "to": "CANCELLED", "actor": "OWNER"},
  {"transition": "T29_CANCEL", "from": "SUBMITTED", "to": "CANCELLED", "actor": "OWNER"},
  {"transition": "T29_CANCEL", "from": "MANUAL_INTAKE", "to": "CANCELLED", "actor": "OWNER"},
  {"transition": "T29_CANCEL", "from": "REPO_VALIDATING", "to": "CANCELLED", "actor": "OWNER"}
]'::jsonb);

ALTER TABLE app.workflow_instance ADD CONSTRAINT workflow_instance_definition_fk
  FOREIGN KEY (definition_version) REFERENCES app.workflow_definition (version);

--changeset sdlc:0012-workflow-transition-routing
-- Milestone 3 enables routing after submission (TDD 9.3 T02/T03) and owner cancellation from the routed states.
-- T02/T03 are engine transitions: the database refuses them with a user actor. The 0007 changeset is not edited;
-- its CHECK constraints (named by PostgreSQL) are replaced here.
ALTER TABLE app.workflow_transition DROP CONSTRAINT workflow_transition_transition_check;
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_transition_check
  CHECK (transition IN ('CREATE', 'T01_SUBMIT', 'T02_ROUTE_REPOSITORY', 'T03_ROUTE_MANUAL_INTAKE', 'T29_CANCEL'));
ALTER TABLE app.workflow_transition DROP CONSTRAINT workflow_transition_check1;
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_legal_pair_check
  CHECK (from_state IS NULL
      OR (from_state, to_state, transition) IN (('DRAFT', 'SUBMITTED', 'T01_SUBMIT'),
                                                ('SUBMITTED', 'REPO_VALIDATING', 'T02_ROUTE_REPOSITORY'),
                                                ('SUBMITTED', 'MANUAL_INTAKE', 'T03_ROUTE_MANUAL_INTAKE'),
                                                ('DRAFT', 'CANCELLED', 'T29_CANCEL'),
                                                ('SUBMITTED', 'CANCELLED', 'T29_CANCEL'),
                                                ('MANUAL_INTAKE', 'CANCELLED', 'T29_CANCEL'),
                                                ('REPO_VALIDATING', 'CANCELLED', 'T29_CANCEL')));
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_engine_actor_check
  CHECK (transition NOT IN ('T02_ROUTE_REPOSITORY', 'T03_ROUTE_MANUAL_INTAKE')
         OR (actor_type = 'SYSTEM' AND actor_id IS NULL));

--changeset sdlc:0012-stage-execution
-- One try of a workflow stage (TDD 9.1, 9.2). A retry is a new row with attempt_no + 1, never a reset.
-- Written only by the worker (engine side); the API may read it.
CREATE TABLE app.stage_execution (
  id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id           uuid        NOT NULL,
  instance_id         uuid        NOT NULL,
  stage_key           text        NOT NULL CHECK (stage_key IN ('REPO_VALIDATION', 'DOCUMENT_PROCESSING', 'BA_ANALYSIS')),
  attempt_no          int         NOT NULL CHECK (attempt_no >= 1),
  status              text        NOT NULL CHECK (status IN ('PENDING', 'BLOCKED', 'READY', 'RUNNING', 'WAITING_HUMAN',
                                    'SUCCEEDED', 'FAILED', 'STALE', 'SUPERSEDED', 'CANCELLED', 'SKIPPED')),
  input_hash          text        CHECK (input_hash ~ '^[0-9a-f]{64}$'),
  retry_of            uuid,
  created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (instance_id, stage_key, attempt_no),
  FOREIGN KEY (tenant_id, instance_id) REFERENCES app.workflow_instance (tenant_id, id),
  FOREIGN KEY (tenant_id, retry_of) REFERENCES app.stage_execution (tenant_id, id)
);

ALTER TABLE app.job ADD CONSTRAINT job_stage_execution_fk
  FOREIGN KEY (tenant_id, stage_execution_id) REFERENCES app.stage_execution (tenant_id, id);
