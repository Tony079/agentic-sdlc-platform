--liquibase formatted sql

--changeset sdlc:0017-staff-user
-- Internal staff identities (owner decision O-06, decision register section 9): global, separate from tenant users.
-- No tenant data lives here. The MFA secret is AES-256-GCM ciphertext (key outside the database, O-11).
CREATE TABLE app.staff_user (
  id              uuid        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  email           text        NOT NULL CHECK (length(email) BETWEEN 3 AND 320),
  display_name    text        NOT NULL CHECK (length(display_name) BETWEEN 1 AND 200),
  password_hash   text        NOT NULL,
  status          text        NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
  mfa_secret_enc  text        CHECK (length(mfa_secret_enc) <= 512),
  mfa_enrolled_at timestamptz,
  -- Enrolment window opened only by the provisioning login (create-staff, reset-mfa). Without it a stolen password
  -- would let an attacker enrol their own authenticator on an unenrolled account.
  mfa_enrol_before timestamptz,
  totp_last_step  bigint      CHECK (totp_last_step >= 0),
  created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK (mfa_enrolled_at IS NULL OR mfa_secret_enc IS NOT NULL)
);
CREATE UNIQUE INDEX staff_user_email_uq ON app.staff_user (lower(email));

--changeset sdlc:0017-staff-grants
-- Capability grants (C-01/O-05: REPOSITORY_REGISTER is a capability, not a role) and tenant assignments (O-06).
-- Every grant names a requester and an approver who must differ from each other and from the grantee (S-02, O-13).
CREATE TABLE app.staff_capability_grant (
  id           uuid        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  staff_id     uuid        NOT NULL REFERENCES app.staff_user (id),
  capability   text        NOT NULL CHECK (capability IN ('STAFF_ADMIN', 'REPOSITORY_REGISTER')),
  bootstrap    boolean     NOT NULL DEFAULT false,
  requested_by uuid        REFERENCES app.staff_user (id),
  approved_by  uuid        NOT NULL REFERENCES app.staff_user (id),
  operator     text        NOT NULL CHECK (length(operator) BETWEEN 1 AND 128),
  granted_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  revoked_at   timestamptz,
  revoked_by   uuid        REFERENCES app.staff_user (id),
  CHECK (approved_by <> staff_id),
  CHECK (requested_by IS NULL OR (requested_by <> staff_id AND requested_by <> approved_by)),
  CHECK ((requested_by IS NULL) = bootstrap),
  CHECK (NOT bootstrap OR capability = 'STAFF_ADMIN'),
  CHECK ((revoked_at IS NULL) = (revoked_by IS NULL))
);
CREATE UNIQUE INDEX staff_capability_grant_active_uq ON app.staff_capability_grant (staff_id, capability)
  WHERE revoked_at IS NULL;

-- Named assigned_tenant_id (not tenant_id): this is a global staff table read before any tenant context exists.
CREATE TABLE app.staff_tenant_assignment (
  id                 uuid        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  staff_id           uuid        NOT NULL REFERENCES app.staff_user (id),
  assigned_tenant_id uuid        NOT NULL REFERENCES app.tenant (id),
  requested_by       uuid        NOT NULL REFERENCES app.staff_user (id),
  approved_by        uuid        NOT NULL REFERENCES app.staff_user (id),
  operator           text        NOT NULL CHECK (length(operator) BETWEEN 1 AND 128),
  assigned_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  revoked_at         timestamptz,
  revoked_by         uuid        REFERENCES app.staff_user (id),
  CHECK (requested_by <> staff_id AND approved_by <> staff_id AND requested_by <> approved_by),
  CHECK ((revoked_at IS NULL) = (revoked_by IS NULL))
);
CREATE UNIQUE INDEX staff_tenant_assignment_active_uq ON app.staff_tenant_assignment (staff_id, assigned_tenant_id)
  WHERE revoked_at IS NULL;

--changeset sdlc:0017-platform-audit
-- Staff and provisioning audit, separate from tenant audit (it has no tenant). Content-free, append-only.
CREATE TABLE app.platform_audit_event (
  id             uuid        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  occurred_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  actor_staff_id uuid,
  action         text        NOT NULL CHECK (action ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  target_type    text        CHECK (target_type ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  target_ref     text        CHECK (length(target_ref) <= 128),
  tenant_ref     uuid,
  result         text        NOT NULL CHECK (result IN ('SUCCESS', 'DENIED', 'FAILED')),
  correlation_id text        CHECK (length(correlation_id) <= 64)
);
CREATE INDEX platform_audit_event_time_ix ON app.platform_audit_event (occurred_at);

--changeset sdlc:0017-staff-guards splitStatements:false
-- Invoker-rights trigger functions with a fixed search_path (no SECURITY DEFINER). They enforce the S-02 / O-13 rules
-- even against a compromised runtime login: the API login cannot write grants at all (grants below), and these
-- checks also bind the provisioning login.

CREATE FUNCTION app.staff_is_admin(p_staff uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN EXISTS (SELECT 1 FROM app.staff_capability_grant g JOIN app.staff_user u ON u.id = g.staff_id
                 WHERE g.staff_id = p_staff AND g.capability = 'STAFF_ADMIN' AND g.revoked_at IS NULL
                   AND u.status = 'ACTIVE' AND u.mfa_enrolled_at IS NOT NULL);
REVOKE ALL ON FUNCTION app.staff_is_admin(uuid) FROM PUBLIC;

CREATE FUNCTION app.staff_ready(p_staff uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN EXISTS (SELECT 1 FROM app.staff_user WHERE id = p_staff AND status = 'ACTIVE' AND mfa_enrolled_at IS NOT NULL);
REVOKE ALL ON FUNCTION app.staff_is_admin(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.staff_ready(uuid) FROM PUBLIC;

-- Capability grant: two distinct STAFF_ADMIN holders (requester, approver), or the development/test bootstrap pair.
CREATE FUNCTION app.staff_capability_grant_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
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
    -- At most two bootstrap administrators, and only while no administrator was ever created another way.
    IF EXISTS (SELECT 1 FROM app.staff_capability_grant
               WHERE capability = 'STAFF_ADMIN' AND revoked_at IS NULL AND NOT bootstrap)
       OR (SELECT count(*) FROM app.staff_capability_grant
           WHERE capability = 'STAFF_ADMIN' AND bootstrap) >= 2 THEN
      RAISE EXCEPTION 'bootstrap is closed' USING ERRCODE = 'P0001';
    END IF;
    IF NOT app.staff_ready(NEW.staff_id) THEN
      RAISE EXCEPTION 'bootstrap administrator must have MFA enrolled' USING ERRCODE = 'P0001';
    END IF;
  ELSE
    IF NOT app.staff_is_admin(NEW.requested_by) OR NOT app.staff_is_admin(NEW.approved_by) THEN
      RAISE EXCEPTION 'requester and approver must both be active staff administrators' USING ERRCODE = 'P0001';
    END IF;
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.staff_capability_grant_guard() FROM PUBLIC;
CREATE TRIGGER staff_capability_grant_guard BEFORE INSERT OR UPDATE ON app.staff_capability_grant
  FOR EACH ROW EXECUTE FUNCTION app.staff_capability_grant_guard();

CREATE FUNCTION app.staff_tenant_assignment_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF OLD.revoked_at IS NOT NULL THEN
      RAISE EXCEPTION 'assignment % is already revoked', OLD.id USING ERRCODE = 'P0001';
    END IF;
    IF NEW.revoked_by IS NOT NULL AND NOT app.staff_is_admin(NEW.revoked_by) THEN
      RAISE EXCEPTION 'only an active staff administrator may revoke an assignment' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.revoked_at IS NOT NULL THEN
    RAISE EXCEPTION 'an assignment cannot be created revoked' USING ERRCODE = 'P0001';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM app.staff_user WHERE id = NEW.staff_id AND status = 'ACTIVE') THEN
    RAISE EXCEPTION 'assignee is not an active staff member' USING ERRCODE = 'P0001';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM app.tenant WHERE id = NEW.assigned_tenant_id AND status = 'ACTIVE') THEN
    RAISE EXCEPTION 'tenant is not active' USING ERRCODE = 'P0001';
  END IF;
  IF NOT app.staff_is_admin(NEW.requested_by) OR NOT app.staff_is_admin(NEW.approved_by) THEN
    RAISE EXCEPTION 'requester and approver must both be active staff administrators' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.staff_tenant_assignment_guard() FROM PUBLIC;
CREATE TRIGGER staff_tenant_assignment_guard BEFORE INSERT OR UPDATE ON app.staff_tenant_assignment
  FOR EACH ROW EXECUTE FUNCTION app.staff_tenant_assignment_guard();

-- Staff identity: an enrolled MFA secret is immutable except a reset (to NULL) by the provisioning login; the TOTP
-- replay step only increases; identity, password and status change only through the provisioning login.
CREATE FUNCTION app.staff_user_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF NEW.id <> OLD.id OR NEW.email <> OLD.email OR NEW.created_at <> OLD.created_at THEN
    RAISE EXCEPTION 'staff identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  IF (NEW.password_hash <> OLD.password_hash OR NEW.status <> OLD.status OR NEW.display_name <> OLD.display_name
      OR NEW.mfa_enrol_before IS DISTINCT FROM OLD.mfa_enrol_before)
     AND current_user <> 'sdlc_provisioner' THEN
    RAISE EXCEPTION 'only the provisioning path may change staff credentials or status' USING ERRCODE = 'P0001';
  END IF;
  IF OLD.mfa_enrolled_at IS NOT NULL
     AND (NEW.mfa_secret_enc IS DISTINCT FROM OLD.mfa_secret_enc OR NEW.mfa_enrolled_at IS DISTINCT FROM OLD.mfa_enrolled_at)
     AND NOT (current_user = 'sdlc_provisioner' AND NEW.mfa_secret_enc IS NULL AND NEW.mfa_enrolled_at IS NULL) THEN
    RAISE EXCEPTION 'an enrolled MFA secret can only be reset through the provisioning path' USING ERRCODE = 'P0001';
  END IF;
  IF OLD.mfa_enrolled_at IS NULL AND NEW.mfa_enrolled_at IS NOT NULL
     AND (OLD.mfa_enrol_before IS NULL OR clock_timestamp() >= OLD.mfa_enrol_before) THEN
    RAISE EXCEPTION 'the MFA enrolment window is closed' USING ERRCODE = 'P0001';
  END IF;
  IF OLD.mfa_enrolled_at IS NULL AND NEW.mfa_secret_enc IS DISTINCT FROM OLD.mfa_secret_enc
     AND current_user <> 'sdlc_provisioner'
     AND (OLD.mfa_enrol_before IS NULL OR clock_timestamp() >= OLD.mfa_enrol_before) THEN
    RAISE EXCEPTION 'the MFA enrolment window is closed' USING ERRCODE = 'P0001';
  END IF;
  IF OLD.totp_last_step IS NOT NULL AND NEW.totp_last_step IS NOT NULL AND NEW.totp_last_step < OLD.totp_last_step
     AND NEW.mfa_enrolled_at IS NOT NULL THEN
    RAISE EXCEPTION 'TOTP step cannot move backwards' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.staff_user_guard() FROM PUBLIC;
CREATE TRIGGER staff_user_guard BEFORE UPDATE ON app.staff_user FOR EACH ROW EXECUTE FUNCTION app.staff_user_guard();

--changeset sdlc:0017-audit-actor-staff
-- Staff actions inside a tenant (repository registration) are audited with actor type STAFF.
ALTER TABLE app.audit_event DROP CONSTRAINT audit_event_actor_type_check;
ALTER TABLE app.audit_event ADD CONSTRAINT audit_event_actor_type_check CHECK (actor_type IN ('USER', 'SYSTEM', 'STAFF'));

--changeset sdlc:0017-staff-grants-roles
-- S-02: the API login reads staff data and may only record MFA enrolment and the replay step (trigger-bound).
-- It cannot create staff, grant or revoke capabilities or assignments. The worker has no access.
GRANT SELECT ON app.staff_user, app.staff_capability_grant, app.staff_tenant_assignment TO sdlc_app;
GRANT UPDATE (mfa_secret_enc, mfa_enrolled_at, totp_last_step) ON app.staff_user TO sdlc_app;
GRANT INSERT ON app.platform_audit_event TO sdlc_app;
GRANT EXECUTE ON FUNCTION app.staff_is_admin(uuid), app.staff_ready(uuid) TO sdlc_app, sdlc_provisioner;

-- The privileged administrative path (provision process only).
GRANT SELECT, INSERT ON app.staff_user, app.staff_capability_grant, app.staff_tenant_assignment TO sdlc_provisioner;
GRANT UPDATE (status, password_hash, display_name, mfa_secret_enc, mfa_enrolled_at, mfa_enrol_before, totp_last_step)
  ON app.staff_user TO sdlc_provisioner;
GRANT UPDATE (revoked_at, revoked_by) ON app.staff_capability_grant, app.staff_tenant_assignment TO sdlc_provisioner;
GRANT SELECT (id, slug, status) ON app.tenant TO sdlc_provisioner;
GRANT INSERT ON app.platform_audit_event TO sdlc_provisioner;
