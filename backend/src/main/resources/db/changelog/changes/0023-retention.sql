--liquibase formatted sql

--changeset sdlc:0023-retention-policy
-- PRD FR-RET-01/07: retention is configurable per tenant and per data class. The defaults live in configuration
-- (PRD FR-RET-11..15 development values); a row here overrides one class for one tenant. Durations in days.
CREATE TABLE app.retention_policy (
  tenant_id  uuid        NOT NULL REFERENCES app.tenant (id),
  data_class text        NOT NULL CHECK (data_class IN ('SNAPSHOT', 'DOCUMENT', 'BRD', 'AUDIT')),
  days       int         NOT NULL CHECK (days BETWEEN 1 AND 3650),
  set_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, data_class)
);
ALTER TABLE app.retention_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.retention_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.retention_policy
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());

--changeset sdlc:0023-retention-states
ALTER TABLE app.repository_snapshot DROP CONSTRAINT repository_snapshot_status_check;
ALTER TABLE app.repository_snapshot ADD CONSTRAINT repository_snapshot_status_check
  CHECK (status IN ('STORED', 'INDEXED', 'FAILED', 'DELETED'));
ALTER TABLE app.brd_version DROP CONSTRAINT brd_version_status_check;
ALTER TABLE app.brd_version ADD CONSTRAINT brd_version_status_check
  CHECK (status IN ('IN_REVIEW', 'APPROVED_INTERNAL', 'RELEASED', 'SUPERSEDED', 'WITHDRAWN', 'STALE', 'PURGED'));

--changeset sdlc:0023-retention-guards splitStatements:false
-- Retention may move a snapshot or a BRD version to its final DELETED/PURGED state and blank a decided pending
-- proposal; nothing else changes. Only the retention login has these column grants (below).
CREATE OR REPLACE FUNCTION app.repository_snapshot_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF NEW.commit_sha <> OLD.commit_sha OR NEW.storage_key IS DISTINCT FROM OLD.storage_key
     OR NEW.archive_sha256 IS DISTINCT FROM OLD.archive_sha256 THEN
    RAISE EXCEPTION 'snapshot % content identity is immutable', OLD.id USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status = 'DELETED' OR (OLD.status <> 'STORED' AND NEW.status <> 'DELETED' AND NEW.status <> OLD.status) THEN
    RAISE EXCEPTION 'snapshot % is final', OLD.id USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;

CREATE OR REPLACE FUNCTION app.brd_version_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF NEW.content_hash <> OLD.content_hash OR NEW.input_hash <> OLD.input_hash OR NEW.version_no <> OLD.version_no
     OR NEW.brd_id <> OLD.brd_id OR NEW.pending_result_id <> OLD.pending_result_id THEN
    RAISE EXCEPTION 'BRD version % is immutable', OLD.id USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status = 'PURGED' THEN
    IF OLD.status = 'PURGED' THEN
      RAISE EXCEPTION 'BRD version % is already purged', OLD.id USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.status IN ('RELEASED', 'SUPERSEDED', 'WITHDRAWN', 'STALE', 'PURGED') AND NEW.status <> OLD.status
     AND NOT (OLD.status = 'RELEASED' AND NEW.status = 'SUPERSEDED') THEN
    RAISE EXCEPTION 'BRD version % is final (%)', OLD.id, OLD.status USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status = 'IN_REVIEW' AND NEW.status = 'RELEASED' THEN
    RAISE EXCEPTION 'a BRD version must be approved before release' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;

CREATE OR REPLACE FUNCTION app.ba_result_pending_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF NEW.input_hash <> OLD.input_hash THEN
    RAISE EXCEPTION 'pending result % is immutable', OLD.id USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status <> 'PENDING' THEN
    -- Retention purge of a decided proposal (FR-RET-05): content replaced by a marker, status unchanged.
    IF current_user = 'sdlc_retention' AND NEW.status = OLD.status AND NEW.proposal = '{"purged": true}'::jsonb THEN
      RETURN NEW;
    END IF;
    RAISE EXCEPTION 'pending result % was already decided', OLD.id USING ERRCODE = 'P0001';
  END IF;
  IF NEW.proposal <> OLD.proposal THEN
    RAISE EXCEPTION 'pending result % is immutable', OLD.id USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;

--changeset sdlc:0023-retention-grants
-- FR-RET-14 / S-01: the retention login is the only login with DELETE, and only on derived data and audit rows.
GRANT EXECUTE ON FUNCTION app.current_tenant() TO sdlc_retention;
GRANT SELECT (id, slug, status) ON app.tenant TO sdlc_retention;
GRANT UPDATE (status) ON app.tenant TO sdlc_retention;
GRANT SELECT, INSERT, UPDATE ON app.retention_policy TO sdlc_retention;
GRANT SELECT (id, tenant_id) ON app.requirement TO sdlc_retention;
GRANT SELECT ON app.workflow_instance, app.workflow_transition, app.requirement_repository TO sdlc_retention;
GRANT SELECT ON app.repository_snapshot TO sdlc_retention;
GRANT UPDATE (status) ON app.repository_snapshot TO sdlc_retention;
GRANT SELECT, DELETE ON app.repository_file, app.code_symbol, app.build_dependency TO sdlc_retention;
GRANT SELECT (id, tenant_id, document_id, requirement_id, state, storage_key) ON app.document_version TO sdlc_retention;
GRANT UPDATE (state, updated_at) ON app.document_version TO sdlc_retention;
GRANT SELECT, DELETE ON app.document_chunk TO sdlc_retention;
GRANT SELECT (id, tenant_id, requirement_id, status, pending_result_id) ON app.brd_version TO sdlc_retention;
GRANT UPDATE (status, status_at) ON app.brd_version TO sdlc_retention;
GRANT SELECT, DELETE ON app.brd_item, app.brd_item_evidence, app.brd_flag_ack TO sdlc_retention;
GRANT SELECT (id, tenant_id, status, proposal) ON app.ba_result_pending TO sdlc_retention;
GRANT UPDATE (proposal) ON app.ba_result_pending TO sdlc_retention;
GRANT SELECT, DELETE, INSERT ON app.audit_event TO sdlc_retention;
