--liquibase formatted sql

-- Garuda v2 (owner decision GD-24, 2026-11-16): the BA agent's BRD goes straight to the client. The Requirement Author
-- reviews it (accepting each flagged statement, or requesting changes) and confirms it; a BRD Approver of the project
-- other than the author gives the final approval, after which the BRD is released automatically when the automated
-- gates pass. Garuda staff no longer review or release BRDs in the main flow (an optional spot check remains). Three
-- change requests are included per requirement; each further one is charged at the BRD's size price. A BRD whose
-- feasibility verdict is NOT_FEASIBLE closes the requirement and waives its charge. Reverses AC-CORE-08 and O-16.

--changeset sdlc:0038-transition-actors splitStatements:false
-- T21/T22 may now be taken by a client user (author or BRD Approver); T23 also by the engine (not feasible).
ALTER TABLE app.workflow_transition DROP CONSTRAINT workflow_transition_reviewer_actor_check;
ALTER TABLE app.workflow_transition ADD CONSTRAINT workflow_transition_reviewer_actor_check
  CHECK ((transition NOT IN ('T21_APPROVE_INTERNAL', 'T22_REQUEST_REVISION', 'T23_REJECT', 'T04_RESUME_MANUAL_INTAKE',
                             'T05_CLOSE_MANUAL_INTAKE')
          OR (actor_type = 'STAFF' AND actor_id IS NOT NULL))
      OR (transition IN ('T21_APPROVE_INTERNAL', 'T22_REQUEST_REVISION') AND actor_type = 'USER' AND actor_id IS NOT NULL)
      OR (transition = 'T23_REJECT' AND actor_type = 'SYSTEM'));

--changeset sdlc:0038-brd-template splitStatements:false
-- The GD-24 BRD template: context, current system, impact, scenarios, non-functional points, acceptance criteria.
ALTER TABLE app.brd_item DROP CONSTRAINT brd_item_section_check;
ALTER TABLE app.brd_item ADD CONSTRAINT brd_item_section_check CHECK (section IN ('FACTS', 'ASSUMPTIONS', 'RISKS',
  'IMPACTED_COMPONENTS', 'MISSING_REQUIREMENTS', 'RECOMMENDATIONS', 'QUESTIONS', 'CONTEXT', 'CURRENT_SYSTEM',
  'SCENARIOS', 'NON_FUNCTIONAL', 'ACCEPTANCE_CRITERIA', 'UNSUPPORTED_REQUIREMENTS', 'EXCLUDED_DOCUMENTS'));
-- The agent's feasibility verdict, with its reasons (model output, shown as such).
ALTER TABLE app.brd_version
  ADD COLUMN feasibility         text  CHECK (feasibility IN ('FEASIBLE', 'FEASIBLE_WITH_RISKS', 'NOT_FEASIBLE')),
  ADD COLUMN feasibility_reasons jsonb NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(feasibility_reasons) = 'array' AND octet_length(feasibility_reasons::text) <= 16384);

--changeset sdlc:0038-prompt-v3
-- Phase B v3: the GD-24 template and the feasibility verdict (v1 and v2 stay registered, unused).
INSERT INTO app.prompt_version (prompt_id, version, agent, text_sha256, schema_version, status) VALUES
  ('ba.phase-b', 3, 'BA', 'aeb54eaf44fdee5cbc27403f6587d41db855d4708e9b668d4b0197dffb518619', 3, 'ACTIVE');

--changeset sdlc:0038-client-decisions splitStatements:false
-- Append-only record of the client's review of a BRD version.
CREATE TABLE app.brd_client_decision (
  id              uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id       uuid        NOT NULL REFERENCES app.tenant (id),
  project_id      uuid        NOT NULL,
  requirement_id  uuid        NOT NULL,
  brd_version_id  uuid        NOT NULL,
  kind            text        NOT NULL CHECK (kind IN ('FLAG_ACCEPTED', 'AUTHOR_CONFIRMED', 'APPROVED',
                                                       'CHANGES_REQUESTED')),
  item_key        text        CHECK (length(item_key) <= 100),
  note            text        CHECK (length(note) <= 5000),
  decided_by      uuid        NOT NULL,
  decided_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, brd_version_id) REFERENCES app.brd_version (tenant_id, id),
  FOREIGN KEY (tenant_id, decided_by) REFERENCES app.app_user (tenant_id, id),
  CHECK ((kind = 'FLAG_ACCEPTED') = (item_key IS NOT NULL)),
  CHECK (kind <> 'CHANGES_REQUESTED' OR length(note) >= 10)
);
CREATE INDEX brd_client_decision_version_ix ON app.brd_client_decision (tenant_id, brd_version_id, kind);
CREATE UNIQUE INDEX brd_client_decision_once_uq ON app.brd_client_decision (brd_version_id, kind, coalesce(item_key, ''))
  WHERE kind <> 'CHANGES_REQUESTED';

-- Who may decide what, checked by the database for the signed-in client member.
CREATE FUNCTION app.brd_client_decision_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  member uuid := app.current_member();
  owner uuid;
  version_status text;
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'client decisions are append-only' USING ERRCODE = 'P0001';
  END IF;
  SELECT created_by INTO owner FROM app.requirement WHERE id = NEW.requirement_id;
  SELECT status INTO version_status FROM app.brd_version WHERE id = NEW.brd_version_id AND requirement_id = NEW.requirement_id;
  IF version_status IS DISTINCT FROM 'IN_REVIEW' THEN
    RAISE EXCEPTION 'only a BRD version in review can be decided' USING ERRCODE = 'P0001';
  END IF;
  IF member IS NULL OR member IS DISTINCT FROM NEW.decided_by THEN
    RAISE EXCEPTION 'a client decision is made by the signed-in member' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.kind IN ('FLAG_ACCEPTED', 'AUTHOR_CONFIRMED') AND member <> owner THEN
    RAISE EXCEPTION 'only the requirement''s author reviews and confirms its BRD' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.kind = 'APPROVED' AND (member = owner OR NOT app.is_brd_approver(NEW.project_id)) THEN
    RAISE EXCEPTION 'a BRD Approver of the project other than the author approves the BRD' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.kind = 'CHANGES_REQUESTED' AND member <> owner AND NOT app.is_brd_approver(NEW.project_id) THEN
    RAISE EXCEPTION 'changes are requested by the author or a BRD Approver' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.brd_client_decision_guard() FROM PUBLIC;
CREATE TRIGGER brd_client_decision_guard BEFORE INSERT OR UPDATE OR DELETE ON app.brd_client_decision
  FOR EACH ROW EXECUTE FUNCTION app.brd_client_decision_guard();

ALTER TABLE app.brd_client_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.brd_client_decision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.brd_client_decision
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());
CREATE POLICY project_access ON app.brd_client_decision AS RESTRICTIVE
  USING (app.current_member() IS NULL OR app.member_sees_project(project_id));

--changeset sdlc:0038-revision-charges splitStatements:false
-- Change requests beyond the three included are charged at the BRD's size price, in the month they are requested.
CREATE TABLE app.brd_revision_charge (
  id              uuid          NOT NULL DEFAULT gen_random_uuid(),
  tenant_id       uuid          NOT NULL REFERENCES app.tenant (id),
  project_id      uuid          NOT NULL,
  requirement_id  uuid          NOT NULL,
  quote_id        uuid          NOT NULL,
  revision_no     int           NOT NULL CHECK (revision_no > 3),
  currency        text          NOT NULL CHECK (currency IN ('INR', 'USD', 'EUR', 'GBP')),
  amount          numeric(12,2) NOT NULL CHECK (amount >= 0),
  billing_month   date          NOT NULL CHECK (billing_month = date_trunc('month', billing_month)::date),
  requested_by    uuid          NOT NULL,
  created_at      timestamptz   NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (requirement_id, revision_no),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, quote_id) REFERENCES app.brd_quote (tenant_id, id),
  FOREIGN KEY (tenant_id, requested_by) REFERENCES app.app_user (tenant_id, id)
);
ALTER TABLE app.brd_revision_charge ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.brd_revision_charge FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.brd_revision_charge
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());
CREATE POLICY project_access ON app.brd_revision_charge AS RESTRICTIVE
  USING (app.current_member() IS NULL OR app.member_sees_project(project_id));

--changeset sdlc:0038-quote-waiver splitStatements:false
-- A NOT_FEASIBLE BRD closes the requirement and waives its charge (the engine, in the worker).
ALTER TABLE app.brd_quote ADD COLUMN waived_at timestamptz, ADD COLUMN waive_reason text CHECK (length(waive_reason) <= 200);
ALTER TABLE app.brd_quote ADD CONSTRAINT brd_quote_waiver_check
  CHECK (waived_at IS NULL OR (status = 'ACCEPTED' AND charge = 0 AND included = false AND waive_reason IS NOT NULL));

CREATE OR REPLACE FUNCTION app.brd_quote_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  member uuid := app.current_member();
  owner uuid;
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'PROPOSED' OR NEW.accepted_by IS NOT NULL THEN
      RAISE EXCEPTION 'a BRD quote starts as proposed' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.status = 'ACCEPTED' THEN
    -- The only change to an accepted quote: the engine waives it once (not feasible).
    IF current_user = 'sdlc_worker' AND OLD.waived_at IS NULL AND NEW.waived_at IS NOT NULL AND NEW.charge = 0
       AND NEW.included = false AND NEW.status = 'ACCEPTED' AND NEW.size = OLD.size AND NEW.list_price = OLD.list_price
       AND NEW.billing_month = OLD.billing_month AND NEW.accepted_by = OLD.accepted_by THEN
      RETURN NEW;
    END IF;
    RAISE EXCEPTION 'an accepted BRD quote is final' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.project_id <> OLD.project_id
     OR NEW.requirement_id <> OLD.requirement_id THEN
    RAISE EXCEPTION 'BRD quote identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status IN ('ACCEPTED', 'DECLINED') AND NEW.status <> OLD.status THEN
    SELECT created_by INTO owner FROM app.requirement WHERE id = NEW.requirement_id;
    IF member IS NULL OR member IS DISTINCT FROM coalesce(NEW.accepted_by, NEW.declined_by)
       OR NOT (member = owner OR app.is_brd_approver(NEW.project_id)) THEN
      RAISE EXCEPTION 'a BRD quote is decided by the requirement owner or a BRD Approver' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.size <> OLD.size OR NEW.list_price <> OLD.list_price OR NEW.plan_id <> OLD.plan_id THEN
      RAISE EXCEPTION 'a BRD quote is accepted as quoted' USING ERRCODE = 'P0001';
    END IF;
  ELSIF NEW.status = 'PROPOSED' AND OLD.status = 'DECLINED' THEN
    RAISE EXCEPTION 'a declined quote is reconsidered by accepting it' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;

--changeset sdlc:0038-grants
GRANT SELECT, INSERT ON app.brd_client_decision TO sdlc_app;
GRANT SELECT ON app.brd_client_decision TO sdlc_worker, sdlc_retention;
GRANT SELECT, INSERT ON app.brd_revision_charge TO sdlc_app;
GRANT SELECT ON app.brd_revision_charge TO sdlc_worker, sdlc_retention;
GRANT UPDATE (charge, included, waived_at, waive_reason) ON app.brd_quote TO sdlc_worker;
GRANT UPDATE (feasibility, feasibility_reasons) ON app.brd_version TO sdlc_worker;
