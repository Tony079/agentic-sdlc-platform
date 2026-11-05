--liquibase formatted sql

--changeset sdlc:0014-document
-- Requirement attachments (PRD 7A, TDD 15). A document is the logical attachment; its content lives in immutable
-- versions. Binary content is never stored in PostgreSQL (FR-STO-03): only metadata and an opaque storage key.
CREATE TABLE app.document (
  id             uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id      uuid        NOT NULL REFERENCES app.tenant (id),
  requirement_id uuid        NOT NULL,
  created_by     uuid        NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  -- Removal before submission (FR-UPL-09). The row and its versions are kept; deletion of the stored object is a
  -- retention concern (O-02).
  removed_at     timestamptz,
  removed_by     uuid,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, created_by) REFERENCES app.app_user (tenant_id, id),
  FOREIGN KEY (tenant_id, removed_by) REFERENCES app.app_user (tenant_id, id),
  CHECK ((removed_at IS NULL) = (removed_by IS NULL))
);
CREATE INDEX document_requirement_ix ON app.document (tenant_id, requirement_id);

--changeset sdlc:0014-document-version
-- States are exactly PRD section 6C. Fail closed (SEC-DOC-03): no state that leads to extraction or analysis is
-- possible without a recorded CLEAN scan result, and the application role cannot write scan results at all.
CREATE TABLE app.document_version (
  id             uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id      uuid        NOT NULL,
  document_id    uuid        NOT NULL,
  requirement_id uuid        NOT NULL,
  version_no     int         NOT NULL CHECK (version_no >= 1),
  file_name      text        NOT NULL CHECK (length(file_name) BETWEEN 1 AND 255
                                             AND file_name !~ '[/\\[:cntrl:]]' AND file_name NOT IN ('.', '..')),
  declared_mime  text        CHECK (length(declared_mime) <= 255),
  claimed_type   text        NOT NULL CHECK (claimed_type IN ('PDF', 'DOCX', 'TXT', 'MARKDOWN')),
  size_bytes     bigint      NOT NULL CHECK (size_bytes BETWEEN 1 AND 104857600),
  sha256         text        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  storage_key    text        NOT NULL UNIQUE CHECK (storage_key ~ '^[0-9a-f-]{36}/[0-9a-f-]{36}$'),
  state          text        NOT NULL DEFAULT 'QUARANTINED' CHECK (state IN ('UPLOADING', 'QUARANTINED', 'VALIDATING', 'SCANNING',
                               'READY_FOR_EXTRACTION', 'EXTRACTING', 'PROCESSED', 'EXTRACTION_FAILED',
                               'EXTRACTION_FAILED_FINAL', 'UNSUPPORTED_CONTENT', 'REJECTED_INVALID',
                               'REJECTED_MALWARE', 'UPLOAD_FAILED', 'SUPERSEDED', 'DELETED')),
  scan_result    text        CHECK (scan_result IN ('CLEAN', 'INFECTED')),
  scanner        text        CHECK (length(scanner) <= 64),
  scanned_at     timestamptz,
  reason_code    text        CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  uploaded_by    uuid        NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (document_id, version_no),
  FOREIGN KEY (tenant_id, document_id) REFERENCES app.document (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, uploaded_by) REFERENCES app.app_user (tenant_id, id),
  -- IS NOT DISTINCT FROM, not "=": a NULL scan result must fail this CHECK (a NULL CHECK result counts as passed).
  CONSTRAINT document_version_clean_scan_required
    CHECK (state NOT IN ('READY_FOR_EXTRACTION', 'EXTRACTING', 'PROCESSED', 'EXTRACTION_FAILED',
                         'EXTRACTION_FAILED_FINAL', 'UNSUPPORTED_CONTENT') OR scan_result IS NOT DISTINCT FROM 'CLEAN'),
  CHECK ((state = 'REJECTED_MALWARE') = (scan_result IS NOT DISTINCT FROM 'INFECTED')),
  CHECK ((scan_result IS NULL) = (scanned_at IS NULL))
);
-- Duplicate detection within a requirement (FR-UPL-07): one live copy of the same content.
CREATE UNIQUE INDEX document_version_live_hash_uq ON app.document_version (tenant_id, requirement_id, sha256)
  WHERE state NOT IN ('REJECTED_INVALID', 'REJECTED_MALWARE', 'UPLOAD_FAILED', 'SUPERSEDED', 'DELETED');
CREATE INDEX document_version_document_ix ON app.document_version (tenant_id, document_id, version_no DESC);

--changeset sdlc:0014-document-version-guard splitStatements:false
-- Terminal document states never change again (PRD 6C); a scan result, once recorded, is immutable.
CREATE FUNCTION app.document_version_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF OLD.state IN ('PROCESSED', 'EXTRACTION_FAILED_FINAL', 'UNSUPPORTED_CONTENT', 'REJECTED_INVALID',
                   'REJECTED_MALWARE', 'UPLOAD_FAILED', 'DELETED')
     AND NEW.state IS DISTINCT FROM OLD.state AND NEW.state NOT IN ('SUPERSEDED', 'DELETED') THEN
    RAISE EXCEPTION 'document version % is in terminal state %', OLD.id, OLD.state USING ERRCODE = 'P0001';
  END IF;
  IF OLD.scan_result IS NOT NULL AND NEW.scan_result IS DISTINCT FROM OLD.scan_result THEN
    RAISE EXCEPTION 'scan result is immutable' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.sha256 <> OLD.sha256 OR NEW.storage_key <> OLD.storage_key OR NEW.size_bytes <> OLD.size_bytes
     OR NEW.document_id <> OLD.document_id OR NEW.tenant_id <> OLD.tenant_id THEN
    RAISE EXCEPTION 'document version content is immutable' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.document_version_guard() FROM PUBLIC;
CREATE TRIGGER document_version_guard BEFORE UPDATE ON app.document_version
  FOR EACH ROW EXECUTE FUNCTION app.document_version_guard();

--changeset sdlc:0014-document-rls splitStatements:false
DO $rls$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['document', 'document_version'] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON app.%I '
                   'USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant())', t);
  END LOOP;
END
$rls$;

--changeset sdlc:0014-document-grants
-- API: records uploads and removals. Column-level INSERT without state or scan columns: every new version starts
-- QUARANTINED (column default) and the API can never record, forge or skip a scan result. It never changes a
-- version afterwards.
GRANT SELECT ON app.document TO sdlc_app;
GRANT INSERT (id, tenant_id, requirement_id, created_by) ON app.document TO sdlc_app;
GRANT UPDATE (removed_at, removed_by) ON app.document TO sdlc_app;
GRANT SELECT ON app.document_version TO sdlc_app;
GRANT INSERT (id, tenant_id, document_id, requirement_id, version_no, file_name, declared_mime, claimed_type,
              size_bytes, sha256, storage_key, uploaded_by) ON app.document_version TO sdlc_app;
-- Worker: validation and scan results, and the processing states. Reads metadata only; no requirement content.
GRANT SELECT ON app.document TO sdlc_worker;
GRANT SELECT ON app.document_version TO sdlc_worker;
GRANT UPDATE (state, scan_result, scanner, scanned_at, reason_code, updated_at) ON app.document_version TO sdlc_worker;
-- Malware detections are security events (PRD 6C.1 rule 3, AC-EXT-33), recorded by the worker.
GRANT INSERT ON app.platform_security_event TO sdlc_worker;
