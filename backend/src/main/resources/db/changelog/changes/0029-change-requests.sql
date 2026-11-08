--liquibase formatted sql

--changeset sdlc:0029-change-requests splitStatements:false
-- C-09 (owner decision 2026-11-08): formal client change requests. Submitted by the requirement owner against the
-- released (approved) scope; assessed by a Business Analyst (BUSINESS_ANALYST); reviewed by a different Business
-- Reviewer; accepted or declined by a Client Approver who is not the submitter. Acceptance creates a new immutable
-- approved-scope version and a new requirement revision; submission alone authorises nothing.
-- C-16: the structured diff (per field: addition/modification/removal, area, priority CRITICAL..LOW) is stored with the
-- request; a Business Analyst may change a priority only with a justification. Both source versions are kept.
CREATE TABLE app.approved_scope_version (
  id             uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id      uuid        NOT NULL,
  requirement_id uuid        NOT NULL,
  version_no     int         NOT NULL CHECK (version_no >= 1),
  content        jsonb       NOT NULL CHECK (jsonb_typeof(content) = 'object' AND octet_length(content::text) <= 131072),
  content_hash   text        NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
  revision_no    int         NOT NULL CHECK (revision_no >= 1),
  source         text        NOT NULL CHECK (source IN ('RELEASED_BASELINE', 'CHANGE_REQUEST')),
  change_request_id uuid,
  accepted_by    uuid,
  created_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (requirement_id, version_no),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, accepted_by) REFERENCES app.app_user (tenant_id, id),
  CHECK ((source = 'CHANGE_REQUEST') = (change_request_id IS NOT NULL AND accepted_by IS NOT NULL))
);

CREATE TABLE app.change_request (
  id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id           uuid        NOT NULL,
  requirement_id      uuid        NOT NULL,
  baseline_scope_id   uuid        NOT NULL,
  requested_change    text        NOT NULL CHECK (length(requested_change) BETWEEN 10 AND 5000),
  justification       text        NOT NULL CHECK (length(justification) BETWEEN 10 AND 5000),
  expected_impact     text        NOT NULL CHECK (length(expected_impact) BETWEEN 1 AND 5000),
  proposed_content    jsonb       NOT NULL CHECK (jsonb_typeof(proposed_content) = 'object'
                                                  AND octet_length(proposed_content::text) <= 131072),
  proposed_hash       text        NOT NULL CHECK (proposed_hash ~ '^[0-9a-f]{64}$'),
  status              text        NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED', 'ASSESSED',
                                  'AWAITING_CLIENT_DECISION', 'ACCEPTED', 'DECLINED', 'WITHDRAWN')),
  submitted_by        uuid        NOT NULL,
  submitted_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  assessed_by         uuid        REFERENCES app.staff_user (id),
  assessed_at         timestamptz,
  scope_assessment    text        CHECK (length(scope_assessment) <= 5000),
  effort_estimate     text        CHECK (length(effort_estimate) <= 1000),
  timeline_impact     text        CHECK (length(timeline_impact) <= 1000),
  technical_impact    text        CHECK (length(technical_impact) <= 5000),
  reviewed_by         uuid        REFERENCES app.staff_user (id),
  reviewed_at         timestamptz,
  review_note         text        CHECK (length(review_note) <= 2000),
  decided_by          uuid,
  decided_at          timestamptz,
  decision_note       text        CHECK (length(decision_note) <= 2000),
  accepted_scope_id   uuid,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, baseline_scope_id) REFERENCES app.approved_scope_version (tenant_id, id),
  FOREIGN KEY (tenant_id, submitted_by) REFERENCES app.app_user (tenant_id, id),
  FOREIGN KEY (tenant_id, decided_by) REFERENCES app.app_user (tenant_id, id),
  CHECK (reviewed_by IS NULL OR reviewed_by <> assessed_by),
  CHECK (decided_by IS NULL OR decided_by <> submitted_by),
  CHECK ((status = 'ACCEPTED') = (accepted_scope_id IS NOT NULL))
);
CREATE UNIQUE INDEX change_request_open_uq ON app.change_request (tenant_id, requirement_id)
  WHERE status IN ('SUBMITTED', 'ASSESSED', 'AWAITING_CLIENT_DECISION');
ALTER TABLE app.approved_scope_version ADD CONSTRAINT approved_scope_version_change_request_fk
  FOREIGN KEY (tenant_id, change_request_id) REFERENCES app.change_request (tenant_id, id);

CREATE TABLE app.change_request_diff (
  id                 uuid    NOT NULL DEFAULT gen_random_uuid(),
  tenant_id          uuid    NOT NULL,
  change_request_id  uuid    NOT NULL,
  field              text    NOT NULL CHECK (field ~ '^[a-zA-Z]{1,40}$'),
  area               text    NOT NULL CHECK (area IN ('BUSINESS_OBJECTIVE', 'FUNCTIONAL', 'ACCEPTANCE_CRITERIA',
                                                      'INTEGRATIONS', 'SECURITY', 'NON_FUNCTIONAL', 'GENERAL')),
  change_type        text    NOT NULL CHECK (change_type IN ('ADDITION', 'MODIFICATION', 'REMOVAL')),
  recommended_priority text  NOT NULL CHECK (recommended_priority IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
  priority           text    NOT NULL CHECK (priority IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
  before_value       text    CHECK (length(before_value) <= 20000),
  after_value        text    CHECK (length(after_value) <= 20000),
  adjusted_by        uuid    REFERENCES app.staff_user (id),
  adjustment_reason  text    CHECK (length(adjustment_reason) BETWEEN 10 AND 2000),
  PRIMARY KEY (id),
  UNIQUE (change_request_id, field),
  FOREIGN KEY (tenant_id, change_request_id) REFERENCES app.change_request (tenant_id, id),
  CHECK ((priority = recommended_priority AND adjusted_by IS NULL) OR (adjusted_by IS NOT NULL AND adjustment_reason IS NOT NULL))
);

-- Immutability of decided requests and accepted scope; separation of duties for the reviewer.
CREATE FUNCTION app.change_request_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF OLD.status IN ('ACCEPTED', 'DECLINED', 'WITHDRAWN') THEN
    RAISE EXCEPTION 'change request % is final', OLD.id USING ERRCODE = 'P0001';
  END IF;
  IF NEW.requirement_id <> OLD.requirement_id OR NEW.baseline_scope_id <> OLD.baseline_scope_id
     OR NEW.proposed_content <> OLD.proposed_content OR NEW.proposed_hash <> OLD.proposed_hash
     OR NEW.submitted_by <> OLD.submitted_by OR NEW.requested_change <> OLD.requested_change THEN
    RAISE EXCEPTION 'change request content is immutable' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status = 'ACCEPTED' AND OLD.status <> 'AWAITING_CLIENT_DECISION' THEN
    RAISE EXCEPTION 'only a reviewed change request can be accepted' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status = 'AWAITING_CLIENT_DECISION' AND (OLD.status <> 'ASSESSED' OR NEW.reviewed_by IS NULL) THEN
    RAISE EXCEPTION 'a change request needs an assessment and a review first' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.change_request_guard() FROM PUBLIC;
CREATE TRIGGER change_request_guard BEFORE UPDATE ON app.change_request
  FOR EACH ROW EXECUTE FUNCTION app.change_request_guard();

DO $rls$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['approved_scope_version', 'change_request', 'change_request_diff'] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON app.%I USING (tenant_id = app.current_tenant()) '
                   'WITH CHECK (tenant_id = app.current_tenant())', t);
  END LOOP;
END
$rls$;
GRANT SELECT, INSERT ON app.approved_scope_version, app.change_request, app.change_request_diff TO sdlc_app;
GRANT UPDATE (status, assessed_by, assessed_at, scope_assessment, effort_estimate, timeline_impact, technical_impact,
              reviewed_by, reviewed_at, review_note, decided_by, decided_at, decision_note, accepted_scope_id)
  ON app.change_request TO sdlc_app;
GRANT UPDATE (priority, adjusted_by, adjustment_reason) ON app.change_request_diff TO sdlc_app;
GRANT SELECT ON app.approved_scope_version, app.change_request, app.change_request_diff TO sdlc_worker, sdlc_retention;
