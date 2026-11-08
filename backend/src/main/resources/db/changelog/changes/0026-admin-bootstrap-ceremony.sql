--liquibase formatted sql

--changeset sdlc:0026-bootstrap-ceremony splitStatements:false
-- O-13 (owner decision 2026-11-08): two-person, time-limited, single-use administrator bootstrap. A ceremony names two
-- distinct, MFA-enrolled participants; each approves (the other's administrator grant) in a separate, authenticated
-- step; when both have approved before expiry the two STAFF_ADMIN grants are written in that transaction and the
-- ceremony is COMPLETED (final). Purposes:
--   DEVELOPMENT  development/test only (both participants present in one provisioning call)
--   INITIAL      production: allowed only while no STAFF_ADMIN grant has ever existed
--   RECOVERY     controlled recovery/replacement: allowed only while fewer than two active administrators exist
-- Every bootstrap grant must reference a completed-in-this-transaction ceremony; there is no other bootstrap path.
CREATE TABLE app.staff_bootstrap_ceremony (
  id            uuid        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  purpose       text        NOT NULL CHECK (purpose IN ('DEVELOPMENT', 'INITIAL', 'RECOVERY')),
  participant_a uuid        NOT NULL REFERENCES app.staff_user (id),
  participant_b uuid        NOT NULL REFERENCES app.staff_user (id),
  opened_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at    timestamptz NOT NULL,
  approved_a_at timestamptz,
  approved_b_at timestamptz,
  status        text        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'COMPLETED', 'EXPIRED', 'CANCELLED')),
  completed_at  timestamptz,
  operator      text        NOT NULL CHECK (length(operator) BETWEEN 1 AND 128),
  CHECK (participant_a <> participant_b),
  CHECK (expires_at > opened_at AND expires_at <= opened_at + interval '24 hours'),
  CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL AND approved_a_at IS NOT NULL AND approved_b_at IS NOT NULL))
);
CREATE UNIQUE INDEX staff_bootstrap_ceremony_open_uq ON app.staff_bootstrap_ceremony ((true)) WHERE status = 'OPEN';

ALTER TABLE app.staff_capability_grant ADD COLUMN ceremony_id uuid REFERENCES app.staff_bootstrap_ceremony (id);

CREATE FUNCTION app.staff_bootstrap_ceremony_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'OPEN' OR NEW.approved_a_at IS NOT NULL OR NEW.approved_b_at IS NOT NULL THEN
      RAISE EXCEPTION 'a ceremony starts OPEN without approvals' USING ERRCODE = 'P0001';
    END IF;
    IF NOT app.staff_ready(NEW.participant_a) OR NOT app.staff_ready(NEW.participant_b) THEN
      RAISE EXCEPTION 'ceremony participants must be active with MFA enrolled' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.purpose IN ('DEVELOPMENT', 'INITIAL')
       AND EXISTS (SELECT 1 FROM app.staff_capability_grant WHERE capability = 'STAFF_ADMIN') THEN
      RAISE EXCEPTION 'bootstrap is closed: an administrator already exists' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.purpose = 'RECOVERY' AND (SELECT count(*) FROM app.staff_user u WHERE app.staff_is_admin(u.id)) >= 2 THEN
      RAISE EXCEPTION 'recovery is only for fewer than two active administrators' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.purpose <> OLD.purpose OR NEW.participant_a <> OLD.participant_a
     OR NEW.participant_b <> OLD.participant_b OR NEW.opened_at <> OLD.opened_at OR NEW.expires_at <> OLD.expires_at THEN
    RAISE EXCEPTION 'ceremony identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status <> 'OPEN' THEN
    RAISE EXCEPTION 'ceremony % is final (%)', OLD.id, OLD.status USING ERRCODE = 'P0001';
  END IF;
  IF (OLD.approved_a_at IS NOT NULL AND NEW.approved_a_at IS DISTINCT FROM OLD.approved_a_at)
     OR (OLD.approved_b_at IS NOT NULL AND NEW.approved_b_at IS DISTINCT FROM OLD.approved_b_at) THEN
    RAISE EXCEPTION 'an approval cannot be changed or repeated' USING ERRCODE = 'P0001';
  END IF;
  IF (NEW.approved_a_at IS DISTINCT FROM OLD.approved_a_at OR NEW.approved_b_at IS DISTINCT FROM OLD.approved_b_at
      OR NEW.status = 'COMPLETED') AND clock_timestamp() >= OLD.expires_at THEN
    RAISE EXCEPTION 'ceremony % has expired', OLD.id USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.staff_bootstrap_ceremony_guard() FROM PUBLIC;
CREATE TRIGGER staff_bootstrap_ceremony_guard BEFORE INSERT OR UPDATE ON app.staff_bootstrap_ceremony
  FOR EACH ROW EXECUTE FUNCTION app.staff_bootstrap_ceremony_guard();

-- Bootstrap grants: only from a ceremony with both approvals, not expired, to a participant, approved by the other.
CREATE OR REPLACE FUNCTION app.staff_capability_grant_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE c app.staff_bootstrap_ceremony%ROWTYPE;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF OLD.revoked_at IS NOT NULL THEN
      RAISE EXCEPTION 'grant % is already revoked', OLD.id USING ERRCODE = 'P0001';
    END IF;
    IF NEW.revoked_by IS NOT NULL AND NOT app.staff_is_admin(NEW.revoked_by) THEN
      RAISE EXCEPTION 'only an active staff administrator may revoke a grant' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.revoked_at IS NOT NULL THEN
    RAISE EXCEPTION 'a grant cannot be created revoked' USING ERRCODE = 'P0001';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM app.staff_user WHERE id = NEW.staff_id AND status = 'ACTIVE') THEN
    RAISE EXCEPTION 'grantee is not an active staff member' USING ERRCODE = 'P0001';
  END IF;
  IF NOT app.staff_ready(NEW.approved_by) THEN
    RAISE EXCEPTION 'approver must be active with MFA enrolled' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.bootstrap THEN
    SELECT * INTO c FROM app.staff_bootstrap_ceremony WHERE id = NEW.ceremony_id;
    IF NOT FOUND OR c.approved_a_at IS NULL OR c.approved_b_at IS NULL OR c.status NOT IN ('OPEN', 'COMPLETED')
       OR clock_timestamp() >= c.expires_at
       OR NOT ((NEW.staff_id = c.participant_a AND NEW.approved_by = c.participant_b)
               OR (NEW.staff_id = c.participant_b AND NEW.approved_by = c.participant_a)) THEN
      RAISE EXCEPTION 'bootstrap is closed: a bootstrap grant needs a completed two-person ceremony' USING ERRCODE = 'P0001';
    END IF;
    IF c.purpose IN ('DEVELOPMENT', 'INITIAL') AND EXISTS (SELECT 1 FROM app.staff_capability_grant
        WHERE capability = 'STAFF_ADMIN' AND (ceremony_id IS DISTINCT FROM c.id)) THEN
      RAISE EXCEPTION 'bootstrap is closed: an administrator already exists' USING ERRCODE = 'P0001';
    END IF;
    IF NOT app.staff_ready(NEW.staff_id) THEN
      RAISE EXCEPTION 'bootstrap administrator must have MFA enrolled' USING ERRCODE = 'P0001';
    END IF;
  ELSE
    IF NEW.ceremony_id IS NOT NULL THEN
      RAISE EXCEPTION 'only bootstrap grants reference a ceremony' USING ERRCODE = 'P0001';
    END IF;
    IF NOT app.staff_is_admin(NEW.requested_by) OR NOT app.staff_is_admin(NEW.approved_by) THEN
      RAISE EXCEPTION 'requester and approver must both be active staff administrators' USING ERRCODE = 'P0001';
    END IF;
  END IF;
  RETURN NEW;
END
$fn$;

GRANT SELECT, INSERT ON app.staff_bootstrap_ceremony TO sdlc_provisioner;
GRANT UPDATE (approved_a_at, approved_b_at, status, completed_at) ON app.staff_bootstrap_ceremony TO sdlc_provisioner;
GRANT SELECT ON app.staff_bootstrap_ceremony TO sdlc_app;
-- C-17: privilege changes and bootstrap activity are security events, written by the provisioning path itself.
GRANT INSERT ON app.platform_security_event TO sdlc_provisioner;
