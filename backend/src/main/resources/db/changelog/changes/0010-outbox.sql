--liquibase formatted sql

--changeset sdlc:0010-outbox-event
-- Transactional outbox (TDD 11.1, decision Q-M3-1). A module that changes business state inserts an event in
-- the same transaction, so the event exists exactly when the change commits. Payloads hold identifiers, codes
-- and a version field "v" only (at most 2 KB), never business content.
--
-- Delivery state (published_at, attempts, dead_at, ...) is changed only by the worker login sdlc_worker; the
-- API login may insert the five producer columns and nothing else (grants in 0013).
-- Order: seq is a database identity the producer cannot supply; per aggregate, events are delivered in seq
-- order and an undelivered (pending or dead) event blocks later events of the same aggregate only.
CREATE TABLE app.outbox_event (
  id              uuid        NOT NULL DEFAULT gen_random_uuid(),
  seq             bigint      GENERATED ALWAYS AS IDENTITY,
  tenant_id       uuid        NOT NULL REFERENCES app.tenant (id),
  aggregate_type  text        NOT NULL CHECK (aggregate_type IN ('REQUIREMENT', 'DOCUMENT', 'JOB')),
  aggregate_id    uuid        NOT NULL,
  -- Release 1 event catalogue (TDD 11.5).
  event_type      text        NOT NULL CHECK (event_type IN (
                    'RequirementSubmitted', 'RepoValidationStarted', 'RepoValidated', 'DocumentDecisionRequested',
                    'DocumentDecisionRecorded', 'ManualIntakeEntered', 'ManualIntakeResumed', 'AnalysisStarted',
                    'AnalysisResumed', 'ExecutionStale', 'ClarificationOpened', 'ClarificationResolved',
                    'ClarificationEscalated', 'BrdVersionCreated', 'ReviewQueued', 'BrdApprovedInternal',
                    'BrdReleasedToClient', 'RevisionRequested', 'RequirementRejected', 'ClientChangesRequested',
                    'RequirementCancelled', 'WorkflowFailed', 'JobDead', 'AdminRetryRequested',
                    'AdminRetryCompleted', 'AdminRetryRejected', 'DocumentUploaded', 'DocumentScanned',
                    'DocumentProcessed', 'DocumentFailed', 'RetentionDue', 'TenantProvisioned')),
  payload         jsonb       NOT NULL CHECK (jsonb_typeof(payload) = 'object'
                                              AND jsonb_typeof(payload -> 'v') = 'number'
                                              AND octet_length(payload::text) <= 2048),
  created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  attempts        int         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  last_error_code text        CHECK (last_error_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  published_at    timestamptz,
  dead_at         timestamptz,
  PRIMARY KEY (id),
  CONSTRAINT outbox_event_seq_key UNIQUE (seq),
  UNIQUE (tenant_id, id),
  CHECK (published_at IS NULL OR dead_at IS NULL)
);
CREATE INDEX outbox_event_pending_ix ON app.outbox_event (tenant_id, seq) WHERE published_at IS NULL;
CREATE INDEX outbox_event_aggregate_pending_ix ON app.outbox_event (tenant_id, aggregate_id, seq)
  WHERE published_at IS NULL;

--changeset sdlc:0010-processed-event
-- Idempotent consumers (TDD 11.4): a consumer's effects and its processed_event row commit together, so a
-- redelivered event is recognised and has no second effect. Written only by sdlc_worker.
CREATE TABLE app.processed_event (
  tenant_id    uuid        NOT NULL,
  event_id     uuid        NOT NULL,
  consumer     text        NOT NULL CHECK (consumer ~ '^[a-z][a-z0-9-]{2,63}$'),
  processed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (event_id, consumer),
  FOREIGN KEY (tenant_id, event_id) REFERENCES app.outbox_event (tenant_id, id)
);

--changeset sdlc:0010-outbox-terminal-guard splitStatements:false
-- A delivered or dead event is final: no role may change it afterwards (a replay or recovery would be a
-- reviewed migration, TDD 11.6; no recovery path exists until O-06/O-13, decision Q-M3-6).
-- Plain invoker-rights trigger function: no SECURITY DEFINER, fixed search_path.
CREATE FUNCTION app.outbox_event_terminal_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF OLD.published_at IS NOT NULL OR OLD.dead_at IS NOT NULL THEN
    RAISE EXCEPTION 'outbox event % is final', OLD.id USING ERRCODE = 'P0001';
  END IF;
  IF NEW.id <> OLD.id OR NEW.seq <> OLD.seq OR NEW.tenant_id <> OLD.tenant_id
     OR NEW.aggregate_id <> OLD.aggregate_id OR NEW.event_type <> OLD.event_type OR NEW.payload <> OLD.payload THEN
    RAISE EXCEPTION 'outbox event identity and payload are immutable' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.outbox_event_terminal_guard() FROM PUBLIC;
CREATE TRIGGER outbox_event_terminal_guard BEFORE UPDATE ON app.outbox_event
  FOR EACH ROW EXECUTE FUNCTION app.outbox_event_terminal_guard();
