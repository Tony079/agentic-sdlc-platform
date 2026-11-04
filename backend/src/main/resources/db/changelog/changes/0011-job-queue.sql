--liquibase formatted sql

--changeset sdlc:0011-job-type
-- Job types and their retry policy (TDD 10.2). Global reference data: no tenant_id, no tenant content.
-- No runtime role can write this table (grants in 0013), so a caller cannot raise its own limits.
-- No admin-retry columns exist: there is no administrative retry path in this schema (O-06, O-13; the TDD
-- archive finding S-06 stays open for the archive).
CREATE TABLE app.job_type (
  code                 text NOT NULL PRIMARY KEY
                         CHECK (code IN ('REPO_VALIDATE', 'DOCUMENT_SCAN', 'DOCUMENT_EXTRACT', 'BA_ANALYSIS')),
  max_attempts         int  NOT NULL CHECK (max_attempts BETWEEN 1 AND 20),
  max_infra_failures   int  NOT NULL CHECK (max_infra_failures BETWEEN 1 AND 50),
  backoff_base_seconds int  NOT NULL CHECK (backoff_base_seconds BETWEEN 1 AND 3600),
  backoff_cap_seconds  int  NOT NULL CHECK (backoff_cap_seconds BETWEEN 1 AND 86400),
  lease_seconds        int  NOT NULL CHECK (lease_seconds BETWEEN 30 AND 900),
  max_runtime_seconds  int  NOT NULL CHECK (max_runtime_seconds BETWEEN 30 AND 7200),
  CHECK (backoff_cap_seconds >= backoff_base_seconds),
  CHECK (max_runtime_seconds >= lease_seconds)
);
-- Extraction: three attempts in total (PRD AC-EXT-24, TDD 9.7).
INSERT INTO app.job_type VALUES
  ('REPO_VALIDATE',    3, 5, 10, 300, 120,  600),
  ('DOCUMENT_SCAN',    3, 5, 10, 300, 120,  600),
  ('DOCUMENT_EXTRACT', 3, 5, 10, 300, 300,  900),
  ('BA_ANALYSIS',      3, 5, 30, 600, 300, 1800);

--changeset sdlc:0011-job
-- The PostgreSQL job queue (PRD R1-M1, TDD 10). Lifecycle: QUEUED -> LEASED -> SUCCEEDED, LEASED -> QUEUED
-- (retry with backoff), -> DEAD (budget used) or -> CANCELLED. SUCCEEDED, DEAD and CANCELLED are final.
-- lease_epoch is the fencing token: strictly increasing per job; a worker's commit succeeds only while it
-- still holds the lease with the same epoch and the lease (database clock) has not expired.
CREATE TABLE app.job (
  id                      uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id               uuid        NOT NULL REFERENCES app.tenant (id),
  job_type                text        NOT NULL REFERENCES app.job_type (code),
  idempotency_key         text        NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9:_.-]{1,200}$'),
  aggregate_type          text        NOT NULL CHECK (aggregate_type IN ('REQUIREMENT', 'DOCUMENT')),
  aggregate_id            uuid        NOT NULL,
  stage_execution_id      uuid,
  payload                 jsonb       NOT NULL DEFAULT '{}'::jsonb
                                      CHECK (jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 2048),
  priority                int         NOT NULL DEFAULT 100 CHECK (priority BETWEEN 0 AND 1000),
  status                  text        NOT NULL DEFAULT 'QUEUED'
                                      CHECK (status IN ('QUEUED', 'LEASED', 'SUCCEEDED', 'DEAD', 'CANCELLED')),
  available_at            timestamptz NOT NULL DEFAULT clock_timestamp(),
  -- Policy, copied from job_type when the row is inserted (trigger below). Never updatable.
  max_attempts            int         NOT NULL,
  max_infra_failures      int         NOT NULL,
  backoff_base_seconds    int         NOT NULL,
  backoff_cap_seconds     int         NOT NULL,
  lease_seconds           int         NOT NULL,
  max_runtime_seconds     int         NOT NULL,
  -- Lease.
  lease_owner             text        CHECK (lease_owner ~ '^[A-Za-z0-9._:-]{1,128}$'),
  lease_epoch             bigint      NOT NULL DEFAULT 0 CHECK (lease_epoch >= 0),
  leased_at               timestamptz,
  lease_expires_at        timestamptz,
  -- Counters, one meaning each (TDD 10.2).
  claim_count             int         NOT NULL DEFAULT 0 CHECK (claim_count >= 0),
  attempt_count           int         NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  retryable_failure_count int         NOT NULL DEFAULT 0 CHECK (retryable_failure_count >= 0),
  infra_failure_count     int         NOT NULL DEFAULT 0 CHECK (infra_failure_count >= 0),
  deferral_count          int         NOT NULL DEFAULT 0 CHECK (deferral_count >= 0),
  last_error_code         text        CHECK (last_error_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  dead_reason             text        CHECK (dead_reason IN ('RETRIES_EXHAUSTED', 'FATAL_FAILURE', 'INFRA_FAILURES')),
  created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
  finished_at             timestamptz,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, idempotency_key),
  CHECK ((status = 'LEASED') = (lease_owner IS NOT NULL AND leased_at IS NOT NULL AND lease_expires_at IS NOT NULL)),
  CHECK ((status = 'DEAD') = (dead_reason IS NOT NULL)),
  CHECK ((status IN ('SUCCEEDED', 'DEAD', 'CANCELLED')) = (finished_at IS NOT NULL))
);
CREATE INDEX job_claimable_ix ON app.job (tenant_id, job_type, priority, available_at) WHERE status = 'QUEUED';
CREATE INDEX job_leased_ix ON app.job (tenant_id, lease_expires_at) WHERE status = 'LEASED';
CREATE INDEX job_aggregate_ix ON app.job (tenant_id, aggregate_id);

--changeset sdlc:0011-job-attempt
-- One row per lease that started work (TDD 10.4). Exactly one open attempt per job; an attempt is final once
-- closed.
CREATE TABLE app.job_attempt (
  tenant_id   uuid        NOT NULL,
  job_id      uuid        NOT NULL,
  lease_epoch bigint      NOT NULL CHECK (lease_epoch >= 1),
  worker_id   text        NOT NULL CHECK (worker_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
  started_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  finished_at timestamptz,
  outcome     text        CHECK (outcome IN ('SUCCEEDED', 'RETRYABLE_FAILURE', 'FATAL_FAILURE',
                                             'DEPENDENCY_UNAVAILABLE', 'LEASE_LOST', 'CANCELLED')),
  error_code  text        CHECK (error_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  PRIMARY KEY (job_id, lease_epoch),
  FOREIGN KEY (tenant_id, job_id) REFERENCES app.job (tenant_id, id),
  CHECK ((finished_at IS NULL) = (outcome IS NULL))
);
CREATE UNIQUE INDEX job_attempt_one_open_ix ON app.job_attempt (job_id) WHERE finished_at IS NULL;

--changeset sdlc:0011-job-guards splitStatements:false
-- Invoker-rights trigger functions (no SECURITY DEFINER, fixed search_path).
-- 1. On insert the policy is copied from job_type and the delivery state starts fresh, whatever the caller sent.
CREATE FUNCTION app.job_before_insert() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE p app.job_type%ROWTYPE;
BEGIN
  SELECT * INTO STRICT p FROM app.job_type WHERE code = NEW.job_type;
  NEW.max_attempts := p.max_attempts;
  NEW.max_infra_failures := p.max_infra_failures;
  NEW.backoff_base_seconds := p.backoff_base_seconds;
  NEW.backoff_cap_seconds := p.backoff_cap_seconds;
  NEW.lease_seconds := p.lease_seconds;
  NEW.max_runtime_seconds := p.max_runtime_seconds;
  NEW.status := 'QUEUED';
  NEW.lease_owner := NULL; NEW.lease_epoch := 0; NEW.leased_at := NULL; NEW.lease_expires_at := NULL;
  NEW.claim_count := 0; NEW.attempt_count := 0; NEW.retryable_failure_count := 0;
  NEW.infra_failure_count := 0; NEW.deferral_count := 0;
  NEW.last_error_code := NULL; NEW.dead_reason := NULL; NEW.finished_at := NULL;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.job_before_insert() FROM PUBLIC;
CREATE TRIGGER job_before_insert BEFORE INSERT ON app.job FOR EACH ROW EXECUTE FUNCTION app.job_before_insert();

-- 2. A final job is never modified again (TDD 10.1); the fencing epoch never decreases; identity, policy and
--    counters cannot be rewritten backwards.
CREATE FUNCTION app.job_before_update() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF OLD.status IN ('SUCCEEDED', 'DEAD', 'CANCELLED') THEN
    RAISE EXCEPTION 'job % is final (%)', OLD.id, OLD.status USING ERRCODE = 'P0001';
  END IF;
  IF NEW.lease_epoch < OLD.lease_epoch THEN
    RAISE EXCEPTION 'lease epoch cannot decrease' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.claim_count < OLD.claim_count OR NEW.attempt_count < OLD.attempt_count
     OR NEW.retryable_failure_count < OLD.retryable_failure_count
     OR NEW.infra_failure_count < OLD.infra_failure_count OR NEW.deferral_count < OLD.deferral_count THEN
    RAISE EXCEPTION 'job counters cannot decrease' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status = 'LEASED' AND OLD.status = 'LEASED' AND NEW.lease_epoch = OLD.lease_epoch
     AND NEW.lease_owner IS DISTINCT FROM OLD.lease_owner THEN
    RAISE EXCEPTION 'a live lease cannot change owner without a new epoch' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.job_before_update() FROM PUBLIC;
CREATE TRIGGER job_before_update BEFORE UPDATE ON app.job FOR EACH ROW EXECUTE FUNCTION app.job_before_update();

-- 3. A closed attempt is final.
CREATE FUNCTION app.job_attempt_before_update() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF OLD.finished_at IS NOT NULL THEN
    RAISE EXCEPTION 'job attempt is final' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.job_attempt_before_update() FROM PUBLIC;
CREATE TRIGGER job_attempt_before_update BEFORE UPDATE ON app.job_attempt
  FOR EACH ROW EXECUTE FUNCTION app.job_attempt_before_update();
