--liquibase formatted sql

-- Garuda v2 (owner decision GD-19, 2026-11-15): every project (product) an organisation creates starts PENDING and is
-- activated with a billing plan by two Garuda Admins (one proposes, the other approves). Billing runs from activation
-- to archiving. Billing plans themselves are proposed by one Garuda Admin and approved by another.

--changeset sdlc:0033-billing-plan splitStatements:false
CREATE TABLE app.billing_plan (
  id              uuid          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  name            text          NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
  currency        text          NOT NULL CHECK (currency IN ('INR', 'USD', 'EUR', 'GBP')),
  monthly_fee     numeric(12,2) NOT NULL CHECK (monthly_fee >= 0),
  included_brds   int           NOT NULL CHECK (included_brds >= 0),
  price_small     numeric(12,2) NOT NULL CHECK (price_small >= 0),
  price_medium    numeric(12,2) NOT NULL CHECK (price_medium >= 0),
  price_large     numeric(12,2) NOT NULL CHECK (price_large >= 0),
  status          text          NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED', 'ACTIVE', 'REJECTED', 'RETIRED')),
  proposed_by     uuid          NOT NULL REFERENCES app.staff_user (id),
  proposed_at     timestamptz   NOT NULL DEFAULT clock_timestamp(),
  approved_by     uuid          REFERENCES app.staff_user (id),
  approved_at     timestamptz,
  retired_by      uuid          REFERENCES app.staff_user (id),
  retired_at      timestamptz,
  CHECK (approved_by IS NULL OR approved_by <> proposed_by),
  CHECK ((status IN ('ACTIVE', 'RETIRED')) = (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
  CHECK ((status = 'RETIRED') = (retired_at IS NOT NULL))
);
CREATE UNIQUE INDEX billing_plan_name_uq ON app.billing_plan (lower(name)) WHERE status IN ('PROPOSED', 'ACTIVE');

CREATE FUNCTION app.billing_plan_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  actor uuid := nullif(current_setting('app.actor_id', true), '')::uuid;
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'PROPOSED' OR NEW.approved_by IS NOT NULL OR NOT app.staff_holds(NEW.proposed_by, 'ORG_ONBOARD')
       OR (current_user = 'sdlc_app' AND actor IS DISTINCT FROM NEW.proposed_by) THEN
      RAISE EXCEPTION 'billing plans are proposed by a Garuda Admin acting as themselves' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.name <> OLD.name OR NEW.currency <> OLD.currency OR NEW.monthly_fee <> OLD.monthly_fee
     OR NEW.included_brds <> OLD.included_brds OR NEW.price_small <> OLD.price_small
     OR NEW.price_medium <> OLD.price_medium OR NEW.price_large <> OLD.price_large OR NEW.proposed_by <> OLD.proposed_by THEN
    RAISE EXCEPTION 'a billing plan is immutable; retire it and propose a new one' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status <> OLD.status THEN
    IF OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED') THEN
      IF NEW.status = 'ACTIVE' AND (NEW.approved_by = OLD.proposed_by OR NOT app.staff_holds(NEW.approved_by, 'ORG_ONBOARD')
          OR (current_user = 'sdlc_app' AND actor IS DISTINCT FROM NEW.approved_by)) THEN
        RAISE EXCEPTION 'a billing plan is approved by a second Garuda Admin' USING ERRCODE = 'P0001';
      END IF;
    ELSIF OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED' THEN
      IF NOT app.staff_holds(NEW.retired_by, 'ORG_ONBOARD') OR (current_user = 'sdlc_app' AND actor IS DISTINCT FROM NEW.retired_by) THEN
        RAISE EXCEPTION 'billing plans are retired by a Garuda Admin' USING ERRCODE = 'P0001';
      END IF;
    ELSE
      RAISE EXCEPTION 'billing plan status % to % is not allowed', OLD.status, NEW.status USING ERRCODE = 'P0001';
    END IF;
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.billing_plan_guard() FROM PUBLIC;
CREATE TRIGGER billing_plan_guard BEFORE INSERT OR UPDATE ON app.billing_plan FOR EACH ROW EXECUTE FUNCTION app.billing_plan_guard();

--changeset sdlc:0033-project-billing-columns splitStatements:false
ALTER TABLE app.project DROP CONSTRAINT project_status_check;
ALTER TABLE app.project ADD CONSTRAINT project_status_check CHECK (status IN ('PENDING', 'ACTIVE', 'DECLINED', 'ARCHIVED'));
ALTER TABLE app.project
  ADD COLUMN plan_id      uuid REFERENCES app.billing_plan (id),
  ADD COLUMN activated_at timestamptz,
  ADD COLUMN declined_at  timestamptz;
-- Existing projects were in use before billing: they stay ACTIVE from their creation, without a plan.
UPDATE app.project SET activated_at = created_at WHERE status IN ('ACTIVE', 'ARCHIVED');

--changeset sdlc:0033-project-activation splitStatements:false
-- Garuda's decision on a pending project. Global table (named subject_tenant_id): Garuda acts before any tenant context.
CREATE TABLE app.project_activation (
  id                uuid        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  subject_tenant_id uuid        NOT NULL REFERENCES app.tenant (id),
  project_id        uuid        NOT NULL,
  decision          text        NOT NULL CHECK (decision IN ('ACTIVATE', 'DECLINE')),
  plan_id           uuid        REFERENCES app.billing_plan (id),
  note              text        CHECK (length(note) <= 2000),
  status            text        NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'EXECUTED', 'REJECTED', 'CANCELLED')),
  requested_by      uuid        NOT NULL REFERENCES app.staff_user (id),
  requested_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  approved_by       uuid        REFERENCES app.staff_user (id),
  executed_at       timestamptz,
  executed_txid     bigint,
  decided_by        uuid        REFERENCES app.staff_user (id),
  decided_at        timestamptz,
  FOREIGN KEY (subject_tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  CHECK ((decision = 'ACTIVATE') = (plan_id IS NOT NULL)),
  CHECK (approved_by IS NULL OR approved_by <> requested_by),
  CHECK ((status = 'EXECUTED') = (approved_by IS NOT NULL AND executed_at IS NOT NULL)),
  CHECK ((status IN ('REJECTED', 'CANCELLED')) = (decided_by IS NOT NULL AND decided_at IS NOT NULL))
);
CREATE UNIQUE INDEX project_activation_open_uq ON app.project_activation (project_id) WHERE status = 'REQUESTED';

CREATE FUNCTION app.project_activation_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  actor uuid := nullif(current_setting('app.actor_id', true), '')::uuid;
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'REQUESTED' OR NEW.approved_by IS NOT NULL OR NOT app.staff_holds(NEW.requested_by, 'ORG_ONBOARD')
       OR (current_user = 'sdlc_app' AND actor IS DISTINCT FROM NEW.requested_by) THEN
      RAISE EXCEPTION 'project decisions are proposed by a Garuda Admin acting as themselves' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.decision = 'ACTIVATE' AND NOT EXISTS (SELECT 1 FROM app.billing_plan p WHERE p.id = NEW.plan_id AND p.status = 'ACTIVE') THEN
      RAISE EXCEPTION 'choose an approved billing plan' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.project_id <> OLD.project_id OR NEW.decision <> OLD.decision
     OR NEW.plan_id IS DISTINCT FROM OLD.plan_id OR NEW.requested_by <> OLD.requested_by THEN
    RAISE EXCEPTION 'project decision identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status <> 'REQUESTED' THEN
    RAISE EXCEPTION 'project decision % is final', OLD.id USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status = 'EXECUTED' THEN
    IF NEW.approved_by = OLD.requested_by OR NOT app.staff_holds(NEW.approved_by, 'ORG_ONBOARD')
       OR (current_user = 'sdlc_app' AND actor IS DISTINCT FROM NEW.approved_by) THEN
      RAISE EXCEPTION 'approval needs a second Garuda Admin' USING ERRCODE = 'P0001';
    END IF;
    NEW.executed_txid := txid_current();
  ELSIF NEW.status IN ('REJECTED', 'CANCELLED') THEN
    IF NOT app.staff_holds(NEW.decided_by, 'ORG_ONBOARD') OR (current_user = 'sdlc_app' AND actor IS DISTINCT FROM NEW.decided_by)
       OR (NEW.status = 'CANCELLED' AND NEW.decided_by <> OLD.requested_by) THEN
      RAISE EXCEPTION 'only a Garuda Admin may reject; only the requester may cancel' USING ERRCODE = 'P0001';
    END IF;
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.project_activation_guard() FROM PUBLIC;
CREATE TRIGGER project_activation_guard BEFORE INSERT OR UPDATE ON app.project_activation
  FOR EACH ROW EXECUTE FUNCTION app.project_activation_guard();

--changeset sdlc:0033-project-guard splitStatements:false
-- Replaces the 0030 guard. Organisation Admins create projects (always PENDING), edit and archive them; only an approved
-- Garuda decision executed in this transaction activates (with its plan) or declines a pending project.
CREATE OR REPLACE FUNCTION app.project_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  actor uuid := nullif(current_setting('app.actor_id', true), '')::uuid;
  decision text;
  decided_plan uuid;
BEGIN
  IF current_user <> 'sdlc_app' THEN
    RETURN NEW;
  END IF;
  IF TG_OP = 'INSERT' THEN
    IF actor IS NULL OR NOT app.is_org_admin(actor) THEN
      RAISE EXCEPTION 'projects are managed only by an Organisation Admin' USING ERRCODE = 'P0001';
    END IF;
    NEW.status := 'PENDING';
    NEW.plan_id := NULL;
    NEW.activated_at := NULL;
    NEW.declined_at := NULL;
    NEW.archived_at := NULL;
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.created_by <> OLD.created_by OR NEW.created_at <> OLD.created_at
     OR OLD.status IN ('ARCHIVED', 'DECLINED') THEN
    RAISE EXCEPTION 'project identity is immutable and archived or declined projects are final' USING ERRCODE = 'P0001';
  END IF;
  SELECT a.decision, a.plan_id INTO decision, decided_plan FROM app.project_activation a
  WHERE a.project_id = NEW.id AND a.status = 'EXECUTED' AND a.executed_txid = txid_current();
  IF decision IS NOT NULL THEN
    IF OLD.status <> 'PENDING' OR NEW.status <> (CASE decision WHEN 'ACTIVATE' THEN 'ACTIVE' ELSE 'DECLINED' END)
       OR NEW.plan_id IS DISTINCT FROM decided_plan THEN
      RAISE EXCEPTION 'the project change does not match the approved Garuda decision' USING ERRCODE = 'P0001';
    END IF;
    IF decision = 'ACTIVATE' THEN
      NEW.activated_at := clock_timestamp();
    ELSE
      NEW.declined_at := clock_timestamp();
    END IF;
    RETURN NEW;
  END IF;
  IF actor IS NULL OR NOT app.is_org_admin(actor) THEN
    RAISE EXCEPTION 'projects are managed only by an Organisation Admin' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.plan_id IS DISTINCT FROM OLD.plan_id OR NEW.activated_at IS DISTINCT FROM OLD.activated_at
     OR NEW.declined_at IS DISTINCT FROM OLD.declined_at THEN
    RAISE EXCEPTION 'billing details change only through a Garuda decision' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status <> OLD.status AND NOT (NEW.status = 'ARCHIVED' AND OLD.status IN ('PENDING', 'ACTIVE')) THEN
    RAISE EXCEPTION 'an Organisation Admin may only archive a project' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;

-- Requirements only in ACTIVE projects (the API login; trusted paths are unaffected).
CREATE FUNCTION app.requirement_project_active_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF current_user = 'sdlc_app' AND NOT EXISTS (SELECT 1 FROM app.project p WHERE p.id = NEW.project_id AND p.status = 'ACTIVE') THEN
    RAISE EXCEPTION 'requirements are created only in an active project' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.requirement_project_active_guard() FROM PUBLIC;
CREATE TRIGGER requirement_project_active_guard BEFORE INSERT ON app.requirement
  FOR EACH ROW EXECUTE FUNCTION app.requirement_project_active_guard();

--changeset sdlc:0033-project-member-guard splitStatements:false
-- Replaces the 0030 guard: Organisation Admins may staff a project while it waits for Garuda's approval.
CREATE OR REPLACE FUNCTION app.project_member_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  actor uuid := nullif(current_setting('app.actor_id', true), '')::uuid;
BEGIN
  IF current_user <> 'sdlc_app' THEN
    RETURN NEW;
  END IF;
  IF actor IS NULL OR NOT app.is_org_admin(actor) THEN
    RAISE EXCEPTION 'project membership is managed only by an Organisation Admin' USING ERRCODE = 'P0001';
  END IF;
  IF TG_OP = 'INSERT' THEN
    IF NEW.added_by <> actor OR NEW.removed_at IS NOT NULL THEN
      RAISE EXCEPTION 'a membership is added by the acting admin' USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM app.project p WHERE p.id = NEW.project_id AND p.status IN ('PENDING', 'ACTIVE')) THEN
      RAISE EXCEPTION 'members are added only to pending or active projects' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.project_id <> OLD.project_id OR NEW.user_id <> OLD.user_id OR NEW.added_by <> OLD.added_by
     OR NEW.added_at <> OLD.added_at OR OLD.removed_at IS NOT NULL THEN
    RAISE EXCEPTION 'membership identity is immutable and removed memberships are final' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.removed_at IS NOT NULL AND NEW.removed_by <> actor THEN
    RAISE EXCEPTION 'a membership is removed by the acting admin' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;

--changeset sdlc:0033-grants
GRANT SELECT, INSERT ON app.billing_plan TO sdlc_app;
GRANT UPDATE (status, approved_by, approved_at, retired_by, retired_at) ON app.billing_plan TO sdlc_app;
GRANT SELECT, INSERT ON app.project_activation TO sdlc_app;
GRANT UPDATE (status, approved_by, executed_at, decided_by, decided_at) ON app.project_activation TO sdlc_app;
GRANT UPDATE (plan_id, activated_at, declined_at) ON app.project TO sdlc_app;
