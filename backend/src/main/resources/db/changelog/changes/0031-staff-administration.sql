--liquibase formatted sql

-- Staff administration in the Garuda console (owner decision GD-15, 2026-11-13): after the one-time two-person
-- bootstrap of the first administrators (command line, O-13), every staff member is invited, given capabilities and
-- tenant assignments, disabled, reactivated and reset through the web console. Each change is requested by one active
-- staff administrator and approved by a different one, neither of them the person changed, and is executed in the
-- approving transaction. The database enforces the same rules the provisioning path enforced (S-02): the API login can
-- write staff identities, grants and assignments only as the execution of such an approved request.

--changeset sdlc:0031-staff-invited splitStatements:false
ALTER TABLE app.staff_user DROP CONSTRAINT staff_user_status_check;
ALTER TABLE app.staff_user ADD CONSTRAINT staff_user_status_check CHECK (status IN ('INVITED', 'ACTIVE', 'DISABLED'));
ALTER TABLE app.staff_user ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE app.staff_user ADD CONSTRAINT staff_user_password_when_active CHECK (status = 'INVITED' OR password_hash IS NOT NULL);

--changeset sdlc:0031-staff-admin-request splitStatements:false
CREATE TABLE app.staff_admin_request (
  id                  uuid        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  kind                text        NOT NULL CHECK (kind IN ('INVITE', 'GRANT_CAPABILITY', 'REVOKE_CAPABILITY',
                                  'ASSIGN_TENANT', 'REVOKE_ASSIGNMENT', 'DISABLE', 'REACTIVATE', 'RESET_MFA',
                                  'RESET_PASSWORD')),
  subject_staff_id    uuid,
  invite_email        text        CHECK (length(invite_email) BETWEEN 3 AND 320),
  invite_display_name text        CHECK (length(invite_display_name) BETWEEN 1 AND 200),
  invite_capabilities text[]      NOT NULL DEFAULT '{}',
  capability          text,
  target_tenant_id    uuid        REFERENCES app.tenant (id),
  reason              text        NOT NULL CHECK (length(reason) BETWEEN 5 AND 2000),
  status              text        NOT NULL DEFAULT 'REQUESTED'
                                  CHECK (status IN ('REQUESTED', 'EXECUTED', 'REJECTED', 'CANCELLED')),
  requested_by        uuid        NOT NULL REFERENCES app.staff_user (id),
  requested_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  approved_by         uuid        REFERENCES app.staff_user (id),
  executed_at         timestamptz,
  executed_txid       bigint,
  decided_by          uuid        REFERENCES app.staff_user (id),
  decided_at          timestamptz,
  CHECK (approved_by IS NULL OR approved_by <> requested_by),
  CHECK (subject_staff_id IS NULL OR (subject_staff_id <> requested_by AND subject_staff_id IS DISTINCT FROM approved_by)),
  CHECK ((kind = 'INVITE') = (invite_email IS NOT NULL AND invite_display_name IS NOT NULL)),
  CHECK (kind = 'INVITE' OR subject_staff_id IS NOT NULL),
  CHECK ((kind IN ('GRANT_CAPABILITY', 'REVOKE_CAPABILITY')) = (capability IS NOT NULL)),
  CHECK ((kind IN ('ASSIGN_TENANT', 'REVOKE_ASSIGNMENT')) = (target_tenant_id IS NOT NULL)),
  CHECK ((status = 'EXECUTED') = (approved_by IS NOT NULL AND executed_at IS NOT NULL AND subject_staff_id IS NOT NULL)),
  CHECK ((status IN ('REJECTED', 'CANCELLED')) = (decided_by IS NOT NULL AND decided_at IS NOT NULL))
);
-- The invited staff row is inserted after the request becomes EXECUTED, in the same transaction.
ALTER TABLE app.staff_admin_request ADD CONSTRAINT staff_admin_request_subject_fk
  FOREIGN KEY (subject_staff_id) REFERENCES app.staff_user (id) DEFERRABLE INITIALLY DEFERRED;
CREATE UNIQUE INDEX staff_admin_request_open_invite_uq ON app.staff_admin_request (lower(invite_email))
  WHERE status = 'REQUESTED' AND kind = 'INVITE';

-- True when the request of this kind for the subject was approved and executed in the current transaction.
CREATE FUNCTION app.staff_request_executed(p_kind text, p_subject uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN EXISTS (SELECT 1 FROM app.staff_admin_request r WHERE r.kind = p_kind AND r.subject_staff_id = p_subject
                 AND r.status = 'EXECUTED' AND r.executed_txid = txid_current());
REVOKE ALL ON FUNCTION app.staff_request_executed(text, uuid) FROM PUBLIC;

CREATE FUNCTION app.staff_admin_request_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  actor uuid := nullif(current_setting('app.actor_id', true), '')::uuid;
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'REQUESTED' OR NEW.approved_by IS NOT NULL OR NEW.executed_at IS NOT NULL OR NEW.decided_by IS NOT NULL THEN
      RAISE EXCEPTION 'a staff request starts as REQUESTED' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.kind = 'INVITE' AND NEW.subject_staff_id IS NOT NULL THEN
      RAISE EXCEPTION 'an invitation names its staff member only when executed' USING ERRCODE = 'P0001';
    END IF;
    IF NOT app.staff_is_admin(NEW.requested_by) OR (current_user = 'sdlc_app' AND actor IS DISTINCT FROM NEW.requested_by) THEN
      RAISE EXCEPTION 'staff changes are requested by an active staff administrator' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.kind <> OLD.kind OR NEW.requested_by <> OLD.requested_by OR NEW.reason <> OLD.reason
     OR NEW.invite_email IS DISTINCT FROM OLD.invite_email OR NEW.invite_capabilities <> OLD.invite_capabilities
     OR NEW.capability IS DISTINCT FROM OLD.capability OR NEW.target_tenant_id IS DISTINCT FROM OLD.target_tenant_id
     OR (OLD.subject_staff_id IS NOT NULL AND NEW.subject_staff_id IS DISTINCT FROM OLD.subject_staff_id) THEN
    RAISE EXCEPTION 'staff request identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status <> 'REQUESTED' THEN
    RAISE EXCEPTION 'staff request % is final', OLD.id USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status = 'EXECUTED' THEN
    IF NEW.approved_by = OLD.requested_by OR NOT app.staff_is_admin(NEW.approved_by)
       OR (current_user = 'sdlc_app' AND actor IS DISTINCT FROM NEW.approved_by) THEN
      RAISE EXCEPTION 'approval needs a second active staff administrator' USING ERRCODE = 'P0001';
    END IF;
    NEW.executed_txid := txid_current();
  ELSIF NEW.status IN ('REJECTED', 'CANCELLED') THEN
    IF NOT app.staff_is_admin(NEW.decided_by) OR (current_user = 'sdlc_app' AND actor IS DISTINCT FROM NEW.decided_by)
       OR (NEW.status = 'CANCELLED' AND NEW.decided_by <> OLD.requested_by) THEN
      RAISE EXCEPTION 'only a staff administrator may reject; only the requester may cancel' USING ERRCODE = 'P0001';
    END IF;
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.staff_admin_request_guard() FROM PUBLIC;
CREATE TRIGGER staff_admin_request_guard BEFORE INSERT OR UPDATE ON app.staff_admin_request
  FOR EACH ROW EXECUTE FUNCTION app.staff_admin_request_guard();

--changeset sdlc:0031-staff-invitation splitStatements:false
-- Single-use links for invited staff (set password) and approved password resets. Only a SHA-256 of the token is kept.
CREATE TABLE app.staff_invitation (
  id               uuid        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  staff_id         uuid        NOT NULL REFERENCES app.staff_user (id) DEFERRABLE INITIALLY DEFERRED,
  purpose          text        NOT NULL CHECK (purpose IN ('INVITE', 'PASSWORD_RESET')),
  token_sha256     text        NOT NULL UNIQUE CHECK (token_sha256 ~ '^[0-9a-f]{64}$'),
  created_by_staff uuid        NOT NULL REFERENCES app.staff_user (id),
  created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at       timestamptz NOT NULL,
  used_at          timestamptz,
  used_txid        bigint,
  revoked_at       timestamptz,
  email_status     text        NOT NULL DEFAULT 'NOT_SENT' CHECK (email_status IN ('NOT_SENT', 'SENT', 'FAILED')),
  CHECK (expires_at > created_at - interval '1 minute' AND expires_at <= created_at + interval '72 hours'),
  CHECK (NOT (used_at IS NOT NULL AND revoked_at IS NOT NULL)),
  CHECK ((used_at IS NULL) = (used_txid IS NULL))
);
CREATE INDEX staff_invitation_staff_ix ON app.staff_invitation (staff_id);

CREATE FUNCTION app.staff_invitation_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  actor uuid := nullif(current_setting('app.actor_id', true), '')::uuid;
BEGIN
  IF current_user <> 'sdlc_app' THEN
    RETURN NEW;
  END IF;
  IF TG_OP = 'INSERT' THEN
    IF NEW.used_at IS NOT NULL OR NEW.revoked_at IS NOT NULL THEN
      RAISE EXCEPTION 'a staff link starts unused' USING ERRCODE = 'P0001';
    END IF;
    -- Created by the approver of an executed invitation or password reset, or (resend) by any staff administrator for
    -- a staff member who is still INVITED.
    IF NOT (NEW.created_by_staff = actor AND app.staff_is_admin(actor) AND NEW.staff_id <> actor
            AND ((NEW.purpose = 'INVITE' AND (app.staff_request_executed('INVITE', NEW.staff_id)
                    OR EXISTS (SELECT 1 FROM app.staff_user s WHERE s.id = NEW.staff_id AND s.status = 'INVITED')))
                 OR (NEW.purpose = 'PASSWORD_RESET' AND app.staff_request_executed('RESET_PASSWORD', NEW.staff_id)))) THEN
      RAISE EXCEPTION 'staff links are created only through an approved staff request' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.staff_id <> OLD.staff_id OR NEW.purpose <> OLD.purpose OR NEW.token_sha256 <> OLD.token_sha256
     OR NEW.expires_at <> OLD.expires_at OR OLD.used_at IS NOT NULL OR OLD.revoked_at IS NOT NULL THEN
    RAISE EXCEPTION 'a staff link is single-use and immutable' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.used_at IS NOT NULL THEN
    IF OLD.expires_at <= clock_timestamp() THEN
      RAISE EXCEPTION 'the link has expired' USING ERRCODE = 'P0001';
    END IF;
    NEW.used_txid := txid_current();
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.staff_invitation_guard() FROM PUBLIC;
CREATE TRIGGER staff_invitation_guard BEFORE INSERT OR UPDATE ON app.staff_invitation
  FOR EACH ROW EXECUTE FUNCTION app.staff_invitation_guard();

--changeset sdlc:0031-staff-user-guards splitStatements:false
-- Inserts by the API login: only the INVITED staff member of an invitation executed in this transaction.
CREATE FUNCTION app.staff_user_insert_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF current_user = 'sdlc_app' AND (NEW.status <> 'INVITED' OR NEW.password_hash IS NOT NULL OR NEW.mfa_secret_enc IS NOT NULL
      OR NEW.mfa_enrolled_at IS NOT NULL OR NOT EXISTS (
        SELECT 1 FROM app.staff_admin_request r WHERE r.kind = 'INVITE' AND r.subject_staff_id = NEW.id
          AND r.status = 'EXECUTED' AND r.executed_txid = txid_current() AND lower(r.invite_email) = lower(NEW.email))) THEN
    RAISE EXCEPTION 'staff members are created only through an approved invitation' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.staff_user_insert_guard() FROM PUBLIC;
CREATE TRIGGER staff_user_insert_guard BEFORE INSERT ON app.staff_user
  FOR EACH ROW EXECUTE FUNCTION app.staff_user_insert_guard();

-- Replaces the 0017 update guard. The provisioning login keeps its rules; the API login may additionally:
-- set the password, activate and open the MFA enrolment window by consuming a staff link in this transaction;
-- disable, reactivate or reset MFA only as the execution of an approved request in this transaction.
CREATE OR REPLACE FUNCTION app.staff_user_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  link_used boolean := EXISTS (SELECT 1 FROM app.staff_invitation i WHERE i.staff_id = NEW.id AND i.used_txid = txid_current());
  mfa_reset boolean;
BEGIN
  IF NEW.id <> OLD.id OR NEW.email <> OLD.email OR NEW.created_at <> OLD.created_at THEN
    RAISE EXCEPTION 'staff identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  IF current_user <> 'sdlc_provisioner' THEN
    IF NEW.display_name <> OLD.display_name THEN
      RAISE EXCEPTION 'only the provisioning path may change a staff name' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.password_hash IS DISTINCT FROM OLD.password_hash AND NOT link_used THEN
      RAISE EXCEPTION 'a staff password is set only through a staff link' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
         (OLD.status = 'INVITED' AND NEW.status = 'ACTIVE' AND link_used)
      OR (OLD.status = 'ACTIVE' AND NEW.status = 'DISABLED' AND app.staff_request_executed('DISABLE', NEW.id))
      OR (OLD.status = 'DISABLED' AND NEW.status = 'ACTIVE' AND app.staff_request_executed('REACTIVATE', NEW.id))) THEN
      RAISE EXCEPTION 'staff status changes only through an approved staff request' USING ERRCODE = 'P0001';
    END IF;
    mfa_reset := app.staff_request_executed('RESET_MFA', NEW.id);
    IF NEW.mfa_enrol_before IS DISTINCT FROM OLD.mfa_enrol_before AND NOT (link_used OR mfa_reset)
       AND NOT (NEW.mfa_enrol_before IS NULL AND NEW.mfa_enrolled_at IS NOT NULL) THEN
      RAISE EXCEPTION 'the MFA enrolment window opens only through a staff link or an approved MFA reset' USING ERRCODE = 'P0001';
    END IF;
  END IF;
  IF OLD.mfa_enrolled_at IS NOT NULL
     AND (NEW.mfa_secret_enc IS DISTINCT FROM OLD.mfa_secret_enc OR NEW.mfa_enrolled_at IS DISTINCT FROM OLD.mfa_enrolled_at)
     AND NOT (NEW.mfa_secret_enc IS NULL AND NEW.mfa_enrolled_at IS NULL
              AND (current_user = 'sdlc_provisioner' OR app.staff_request_executed('RESET_MFA', NEW.id))) THEN
    RAISE EXCEPTION 'an enrolled MFA secret can only be reset through an approved request' USING ERRCODE = 'P0001';
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

--changeset sdlc:0031-staff-grant-guards splitStatements:false
-- Replaces the 0026 grant guard: the same bootstrap and two-administrator rules; INVITED staff may receive the
-- capabilities of their approved invitation; the API login may grant or revoke only as the execution of an approved
-- request in this transaction, with the same requester and approver.
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
    IF current_user = 'sdlc_app' AND NOT EXISTS (SELECT 1 FROM app.staff_admin_request r
        WHERE r.kind = 'REVOKE_CAPABILITY' AND r.subject_staff_id = OLD.staff_id AND r.capability = OLD.capability
          AND r.status = 'EXECUTED' AND r.executed_txid = txid_current() AND r.approved_by = NEW.revoked_by) THEN
      RAISE EXCEPTION 'capabilities are revoked only through an approved staff request' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.revoked_at IS NOT NULL THEN
    RAISE EXCEPTION 'a grant cannot be created revoked' USING ERRCODE = 'P0001';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM app.staff_user WHERE id = NEW.staff_id AND status IN ('ACTIVE', 'INVITED')) THEN
    RAISE EXCEPTION 'grantee is not an active staff member' USING ERRCODE = 'P0001';
  END IF;
  IF NOT app.staff_ready(NEW.approved_by) THEN
    RAISE EXCEPTION 'approver must be active with MFA enrolled' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.bootstrap THEN
    IF current_user = 'sdlc_app' THEN
      RAISE EXCEPTION 'bootstrap grants are made only by the provisioning path' USING ERRCODE = 'P0001';
    END IF;
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
    IF current_user = 'sdlc_app' AND NOT EXISTS (SELECT 1 FROM app.staff_admin_request r
        WHERE r.subject_staff_id = NEW.staff_id AND r.status = 'EXECUTED' AND r.executed_txid = txid_current()
          AND r.requested_by = NEW.requested_by AND r.approved_by = NEW.approved_by
          AND ((r.kind = 'GRANT_CAPABILITY' AND r.capability = NEW.capability)
               OR (r.kind = 'INVITE' AND NEW.capability = ANY (r.invite_capabilities)))) THEN
      RAISE EXCEPTION 'capabilities are granted only through an approved staff request' USING ERRCODE = 'P0001';
    END IF;
  END IF;
  RETURN NEW;
END
$fn$;

CREATE OR REPLACE FUNCTION app.staff_tenant_assignment_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF OLD.revoked_at IS NOT NULL THEN
      RAISE EXCEPTION 'assignment % is already revoked', OLD.id USING ERRCODE = 'P0001';
    END IF;
    IF NEW.revoked_by IS NOT NULL AND NOT app.staff_is_admin(NEW.revoked_by) THEN
      RAISE EXCEPTION 'only an active staff administrator may revoke an assignment' USING ERRCODE = 'P0001';
    END IF;
    IF current_user = 'sdlc_app' AND NOT EXISTS (SELECT 1 FROM app.staff_admin_request r
        WHERE r.kind = 'REVOKE_ASSIGNMENT' AND r.subject_staff_id = OLD.staff_id
          AND r.target_tenant_id = OLD.assigned_tenant_id AND r.status = 'EXECUTED'
          AND r.executed_txid = txid_current() AND r.approved_by = NEW.revoked_by) THEN
      RAISE EXCEPTION 'assignments are revoked only through an approved staff request' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.revoked_at IS NOT NULL THEN
    RAISE EXCEPTION 'an assignment cannot be created revoked' USING ERRCODE = 'P0001';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM app.staff_user WHERE id = NEW.staff_id AND status IN ('ACTIVE', 'INVITED')) THEN
    RAISE EXCEPTION 'assignee is not an active staff member' USING ERRCODE = 'P0001';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM app.tenant WHERE id = NEW.assigned_tenant_id AND status = 'ACTIVE') THEN
    RAISE EXCEPTION 'tenant is not active' USING ERRCODE = 'P0001';
  END IF;
  IF NOT app.staff_is_admin(NEW.requested_by) OR NOT app.staff_is_admin(NEW.approved_by) THEN
    RAISE EXCEPTION 'requester and approver must both be active staff administrators' USING ERRCODE = 'P0001';
  END IF;
  IF current_user = 'sdlc_app' AND NOT EXISTS (SELECT 1 FROM app.staff_admin_request r
      WHERE r.kind = 'ASSIGN_TENANT' AND r.subject_staff_id = NEW.staff_id AND r.target_tenant_id = NEW.assigned_tenant_id
        AND r.status = 'EXECUTED' AND r.executed_txid = txid_current()
        AND r.requested_by = NEW.requested_by AND r.approved_by = NEW.approved_by) THEN
    RAISE EXCEPTION 'tenants are assigned only through an approved staff request' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;

--changeset sdlc:0031-staff-administration-grants
GRANT EXECUTE ON FUNCTION app.staff_request_executed(text, uuid) TO sdlc_app, sdlc_provisioner;
GRANT SELECT, INSERT ON app.staff_admin_request TO sdlc_app;
GRANT UPDATE (status, subject_staff_id, approved_by, executed_at, decided_by, decided_at) ON app.staff_admin_request TO sdlc_app;
GRANT SELECT ON app.staff_admin_request TO sdlc_provisioner;
GRANT SELECT, INSERT ON app.staff_invitation TO sdlc_app;
GRANT UPDATE (used_at, revoked_at, email_status) ON app.staff_invitation TO sdlc_app;
GRANT SELECT ON app.staff_invitation TO sdlc_provisioner;
GRANT INSERT (id, email, display_name, status) ON app.staff_user TO sdlc_app;
GRANT UPDATE (status, password_hash, mfa_enrol_before) ON app.staff_user TO sdlc_app;
GRANT INSERT ON app.staff_capability_grant, app.staff_tenant_assignment TO sdlc_app;
GRANT UPDATE (revoked_at, revoked_by) ON app.staff_capability_grant, app.staff_tenant_assignment TO sdlc_app;
