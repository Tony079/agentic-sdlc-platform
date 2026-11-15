--liquibase formatted sql

-- Garuda v2 (owner decisions GD-16, GD-17, 2026-11-15; docs/architecture/garuda-platform-model-v2.md):
-- one Garuda Admin role, the organisation record with allowed email domains, and Organisation Admins managed by
-- Garuda through two-person requests. Garuda never manages the organisation's other users.

--changeset sdlc:0032-garuda-admin-implies splitStatements:false
-- GD-16: STAFF_ADMIN is the Garuda Admin role; it includes onboarding and the dashboard.
CREATE OR REPLACE FUNCTION app.staff_holds(p_staff uuid, p_capability text) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN app.staff_ready(p_staff)
     AND EXISTS (SELECT 1 FROM app.staff_capability_grant g WHERE g.staff_id = p_staff AND g.revoked_at IS NULL
                 AND (g.capability = p_capability
                      OR (g.capability = 'STAFF_ADMIN' AND p_capability IN ('ORG_ONBOARD', 'AUDITOR'))));

--changeset sdlc:0032-tenant-profile splitStatements:false
-- GD-17: the organisation record, kept by Garuda. Readable by Garuda staff (no tenant context) and by the
-- organisation itself; written only by the execution of an onboarding or profile request in the same transaction.
CREATE TABLE app.tenant_profile (
  -- Named profile_tenant_id (not tenant_id): a global Garuda record read before any tenant context exists.
  profile_tenant_id       uuid        NOT NULL PRIMARY KEY REFERENCES app.tenant (id) DEFERRABLE INITIALLY DEFERRED,
  legal_name              text        NOT NULL CHECK (length(legal_name) BETWEEN 1 AND 300),
  display_name            text        NOT NULL CHECK (length(display_name) BETWEEN 1 AND 200),
  industry                text        CHECK (length(industry) <= 100),
  country                 text        CHECK (length(country) <= 100),
  address                 text        CHECK (length(address) <= 1000),
  website                 text        CHECK (length(website) <= 300),
  billing_contact_name    text        CHECK (length(billing_contact_name) <= 200),
  billing_contact_email   text        CHECK (length(billing_contact_email) <= 320),
  technical_contact_name  text        CHECK (length(technical_contact_name) <= 200),
  technical_contact_email text        CHECK (length(technical_contact_email) <= 320),
  support_email           text        CHECK (length(support_email) <= 320),
  contract_ref            text        CHECK (length(contract_ref) <= 100),
  contract_start          date,
  renewal_date            date,
  user_limit              int         CHECK (user_limit > 0),
  allowed_email_domains   text[]      NOT NULL CHECK (cardinality(allowed_email_domains) BETWEEN 1 AND 20),
  data_region             text        CHECK (length(data_region) <= 50),
  mfa_policy              text        NOT NULL DEFAULT 'ADMINS' CHECK (mfa_policy IN ('ADMINS', 'ALL_USERS')),
  updated_at              timestamptz NOT NULL DEFAULT clock_timestamp()
);
ALTER TABLE app.tenant_profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.tenant_profile FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_profile_access ON app.tenant_profile
  USING (app.current_tenant() IS NULL OR profile_tenant_id = app.current_tenant())
  WITH CHECK (app.current_tenant() IS NULL OR profile_tenant_id = app.current_tenant());

-- The onboarding request carries the requested record (copied to tenant_profile on approval).
ALTER TABLE app.organisation_onboarding_request ADD COLUMN profile jsonb NOT NULL DEFAULT '{}'::jsonb;

--changeset sdlc:0032-organisation-admin-request splitStatements:false
-- GD-16: Garuda's two-person actions on an organisation: its Organisation Admins and its record.
CREATE TABLE app.organisation_admin_request (
  id                  uuid        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  subject_tenant_id   uuid        NOT NULL REFERENCES app.tenant (id),
  kind                text        NOT NULL CHECK (kind IN ('INVITE_ADMIN', 'DISABLE_ADMIN', 'REACTIVATE_ADMIN',
                                  'RESET_ADMIN_MFA', 'UPDATE_PROFILE')),
  subject_user_id     uuid,
  invite_email        text        CHECK (length(invite_email) BETWEEN 3 AND 320),
  invite_display_name text        CHECK (length(invite_display_name) BETWEEN 1 AND 200),
  profile             jsonb,
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
  CHECK ((kind = 'INVITE_ADMIN') = (invite_email IS NOT NULL AND invite_display_name IS NOT NULL)),
  CHECK ((kind IN ('DISABLE_ADMIN', 'REACTIVATE_ADMIN', 'RESET_ADMIN_MFA')) = (subject_user_id IS NOT NULL)),
  CHECK ((kind = 'UPDATE_PROFILE') = (profile IS NOT NULL)),
  CHECK ((status = 'EXECUTED') = (approved_by IS NOT NULL AND executed_at IS NOT NULL)),
  CHECK ((status IN ('REJECTED', 'CANCELLED')) = (decided_by IS NOT NULL AND decided_at IS NOT NULL))
);
CREATE INDEX organisation_admin_request_tenant_ix ON app.organisation_admin_request (subject_tenant_id, requested_at DESC);

CREATE FUNCTION app.organisation_admin_request_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  actor uuid := nullif(current_setting('app.actor_id', true), '')::uuid;
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'REQUESTED' OR NEW.approved_by IS NOT NULL OR NEW.decided_by IS NOT NULL THEN
      RAISE EXCEPTION 'an organisation admin request starts as REQUESTED' USING ERRCODE = 'P0001';
    END IF;
    IF NOT app.staff_holds(NEW.requested_by, 'ORG_ONBOARD')
       OR (current_user = 'sdlc_app' AND actor IS DISTINCT FROM NEW.requested_by) THEN
      RAISE EXCEPTION 'requests are made by a Garuda Admin acting as themselves' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.kind <> OLD.kind OR NEW.subject_tenant_id <> OLD.subject_tenant_id
     OR NEW.subject_user_id IS DISTINCT FROM OLD.subject_user_id OR NEW.invite_email IS DISTINCT FROM OLD.invite_email
     OR NEW.profile IS DISTINCT FROM OLD.profile OR NEW.requested_by <> OLD.requested_by OR NEW.reason <> OLD.reason THEN
    RAISE EXCEPTION 'organisation admin request identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status <> 'REQUESTED' THEN
    RAISE EXCEPTION 'organisation admin request % is final', OLD.id USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status = 'EXECUTED' THEN
    IF NEW.approved_by = OLD.requested_by OR NOT app.staff_holds(NEW.approved_by, 'ORG_ONBOARD')
       OR (current_user = 'sdlc_app' AND actor IS DISTINCT FROM NEW.approved_by) THEN
      RAISE EXCEPTION 'approval needs a second Garuda Admin' USING ERRCODE = 'P0001';
    END IF;
    NEW.executed_txid := txid_current();
  ELSIF NEW.status IN ('REJECTED', 'CANCELLED') THEN
    IF NOT app.staff_holds(NEW.decided_by, 'ORG_ONBOARD')
       OR (current_user = 'sdlc_app' AND actor IS DISTINCT FROM NEW.decided_by)
       OR (NEW.status = 'CANCELLED' AND NEW.decided_by <> OLD.requested_by) THEN
      RAISE EXCEPTION 'only a Garuda Admin may reject; only the requester may cancel' USING ERRCODE = 'P0001';
    END IF;
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.organisation_admin_request_guard() FROM PUBLIC;
CREATE TRIGGER organisation_admin_request_guard BEFORE INSERT OR UPDATE ON app.organisation_admin_request
  FOR EACH ROW EXECUTE FUNCTION app.organisation_admin_request_guard();

-- True when a request of this kind for the tenant (and user, when given) was executed in this transaction.
CREATE FUNCTION app.org_admin_request_executed(p_kind text, p_tenant uuid, p_user uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN EXISTS (SELECT 1 FROM app.organisation_admin_request r WHERE r.kind = p_kind AND r.subject_tenant_id = p_tenant
                 AND (p_user IS NULL OR r.subject_user_id = p_user)
                 AND r.status = 'EXECUTED' AND r.executed_txid = txid_current());
REVOKE ALL ON FUNCTION app.org_admin_request_executed(text, uuid, uuid) FROM PUBLIC;

CREATE FUNCTION app.tenant_profile_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF current_user = 'sdlc_app' AND NOT (
       EXISTS (SELECT 1 FROM app.organisation_onboarding_request r WHERE r.created_tenant_id = NEW.profile_tenant_id
               AND r.status = 'EXECUTED' AND r.executed_txid = txid_current())
       OR app.org_admin_request_executed('UPDATE_PROFILE', NEW.profile_tenant_id, NULL)) THEN
    RAISE EXCEPTION 'the organisation record changes only through an approved Garuda request' USING ERRCODE = 'P0001';
  END IF;
  IF TG_OP = 'UPDATE' AND NEW.profile_tenant_id <> OLD.profile_tenant_id THEN
    RAISE EXCEPTION 'organisation record identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  NEW.allowed_email_domains := ARRAY(SELECT DISTINCT lower(trim(d)) FROM unnest(NEW.allowed_email_domains) d);
  NEW.updated_at := clock_timestamp();
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.tenant_profile_guard() FROM PUBLIC;
CREATE TRIGGER tenant_profile_guard BEFORE INSERT OR UPDATE ON app.tenant_profile
  FOR EACH ROW EXECUTE FUNCTION app.tenant_profile_guard();

--changeset sdlc:0032-user-guards splitStatements:false
-- GD-17: every new user of an organisation with a record must have an email in an allowed domain.
CREATE FUNCTION app.app_user_domain_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  domains text[];
BEGIN
  SELECT p.allowed_email_domains INTO domains FROM app.tenant_profile p WHERE p.profile_tenant_id = NEW.tenant_id;
  IF domains IS NOT NULL AND NOT (lower(split_part(NEW.email, '@', 2)) = ANY (domains)) THEN
    RAISE EXCEPTION 'the email domain is not allowed for this organisation' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.app_user_domain_guard() FROM PUBLIC;
CREATE TRIGGER app_user_domain_guard BEFORE INSERT ON app.app_user
  FOR EACH ROW EXECUTE FUNCTION app.app_user_domain_guard();

-- Replaces the 0030 guard: same rules, plus Garuda's two-person actions on Organisation Admins (GD-16): invite an
-- admin, disable or reactivate an admin, reset an admin's MFA, each only as an approved request in this transaction.
CREATE OR REPLACE FUNCTION app.app_user_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  actor uuid := nullif(current_setting('app.actor_id', true), '')::uuid;
  consumed boolean;
  garuda boolean;
BEGIN
  IF current_user <> 'sdlc_app' THEN
    RETURN NEW;
  END IF;
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'INVITED' OR NEW.password_hash IS NOT NULL OR NEW.mfa_secret_enc IS NOT NULL THEN
      RAISE EXCEPTION 'users are created INVITED, without credentials' USING ERRCODE = 'P0001';
    END IF;
    IF NOT ((actor IS NOT NULL AND app.is_org_admin(actor))
            OR (NEW.role = 'ORG_ADMIN' AND EXISTS (SELECT 1 FROM app.organisation_onboarding_request r
                WHERE r.created_tenant_id = NEW.tenant_id AND r.executed_txid = txid_current()
                  AND lower(r.admin_email) = lower(NEW.email)))
            OR (NEW.role = 'ORG_ADMIN' AND EXISTS (SELECT 1 FROM app.organisation_admin_request r
                WHERE r.kind = 'INVITE_ADMIN' AND r.subject_tenant_id = NEW.tenant_id AND r.status = 'EXECUTED'
                  AND r.executed_txid = txid_current() AND lower(r.invite_email) = lower(NEW.email)))) THEN
      RAISE EXCEPTION 'users are created only by an Organisation Admin' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.email <> OLD.email OR NEW.created_at <> OLD.created_at THEN
    RAISE EXCEPTION 'user identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  consumed := EXISTS (SELECT 1 FROM app.user_invitation i WHERE i.user_id = NEW.id AND i.used_txid = txid_current());
  -- Garuda may change only an Organisation Admin's status, never a role, and only through an approved request.
  garuda := OLD.role = 'ORG_ADMIN' AND NEW.role = OLD.role AND (
       (OLD.status = 'ACTIVE' AND NEW.status = 'DISABLED' AND app.org_admin_request_executed('DISABLE_ADMIN', NEW.tenant_id, NEW.id))
    OR (OLD.status = 'DISABLED' AND NEW.status = 'ACTIVE' AND app.org_admin_request_executed('REACTIVATE_ADMIN', NEW.tenant_id, NEW.id)));
  IF NEW.role <> OLD.role OR (NEW.status <> OLD.status AND NOT (OLD.status = 'INVITED' AND NEW.status = 'ACTIVE')) THEN
    IF NOT garuda AND (actor IS NULL OR actor = NEW.id OR NOT app.is_org_admin(actor)) THEN
      RAISE EXCEPTION 'roles and account status are changed only by another Organisation Admin' USING ERRCODE = 'P0001';
    END IF;
  END IF;
  IF OLD.status = 'INVITED' AND NEW.status = 'ACTIVE' AND NOT consumed THEN
    RAISE EXCEPTION 'an invited user becomes active only by accepting the invitation' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.password_hash IS DISTINCT FROM OLD.password_hash AND NOT consumed AND actor IS DISTINCT FROM NEW.id THEN
    RAISE EXCEPTION 'a password is set only by its user' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.mfa_secret_enc IS DISTINCT FROM OLD.mfa_secret_enc THEN
    IF NEW.mfa_secret_enc IS NULL THEN
      IF NOT (OLD.role = 'ORG_ADMIN' AND app.org_admin_request_executed('RESET_ADMIN_MFA', NEW.tenant_id, NEW.id))
         AND (actor IS NULL OR actor = NEW.id OR NOT app.is_org_admin(actor)) THEN
        RAISE EXCEPTION 'MFA is reset only by another Organisation Admin' USING ERRCODE = 'P0001';
      END IF;
      NEW.mfa_enrolled_at := NULL;
      NEW.totp_last_step := NULL;
    ELSIF OLD.mfa_secret_enc IS NOT NULL AND OLD.mfa_enrolled_at IS NOT NULL THEN
      RAISE EXCEPTION 'an enrolled MFA secret is not replaced' USING ERRCODE = 'P0001';
    END IF;
  END IF;
  IF NEW.totp_last_step < OLD.totp_last_step THEN
    RAISE EXCEPTION 'the TOTP replay counter only moves forward' USING ERRCODE = 'P0001';
  END IF;
  IF OLD.role = 'ORG_ADMIN' AND OLD.status = 'ACTIVE' AND (NEW.role <> 'ORG_ADMIN' OR NEW.status <> 'ACTIVE')
     AND NOT EXISTS (SELECT 1 FROM app.app_user u WHERE u.id <> NEW.id AND u.role = 'ORG_ADMIN' AND u.status = 'ACTIVE') THEN
    RAISE EXCEPTION 'the last active Organisation Admin cannot be removed' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    NEW.status_changed_at := clock_timestamp();
    IF NEW.status = 'ACTIVE' AND OLD.status = 'INVITED' THEN
      NEW.activated_at := clock_timestamp();
    END IF;
  END IF;
  RETURN NEW;
END
$fn$;

-- Replaces the 0030 invitation guard: adds invitations issued by the approver of a Garuda INVITE_ADMIN request.
CREATE OR REPLACE FUNCTION app.user_invitation_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  actor uuid := nullif(current_setting('app.actor_id', true), '')::uuid;
BEGIN
  IF current_user <> 'sdlc_app' THEN
    RETURN NEW;
  END IF;
  IF TG_OP = 'INSERT' THEN
    IF NEW.used_at IS NOT NULL OR NEW.revoked_at IS NOT NULL THEN
      RAISE EXCEPTION 'an invitation starts unused' USING ERRCODE = 'P0001';
    END IF;
    IF NOT ((NEW.created_by_user IS NOT NULL AND NEW.created_by_user = actor
             AND (app.is_org_admin(actor) OR (NEW.purpose = 'PASSWORD_RESET' AND NEW.user_id = actor)))
            OR (NEW.created_by_staff IS NOT NULL AND NEW.created_by_staff = actor AND NEW.purpose = 'INVITE'
                AND (EXISTS (SELECT 1 FROM app.organisation_onboarding_request r
                             WHERE r.created_tenant_id = NEW.tenant_id AND r.executed_txid = txid_current())
                     OR (app.org_admin_request_executed('INVITE_ADMIN', NEW.tenant_id, NULL)
                         AND EXISTS (SELECT 1 FROM app.app_user u WHERE u.id = NEW.user_id AND u.role = 'ORG_ADMIN'))))
            OR (NEW.purpose = 'PASSWORD_RESET' AND actor IS NULL AND NEW.created_by_user IS NULL
                AND NEW.created_by_staff IS NULL)) THEN
      RAISE EXCEPTION 'invitations are created by an Organisation Admin' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.user_id <> OLD.user_id OR NEW.purpose <> OLD.purpose OR NEW.token_sha256 <> OLD.token_sha256
     OR NEW.expires_at <> OLD.expires_at OR OLD.used_at IS NOT NULL OR OLD.revoked_at IS NOT NULL THEN
    -- Revoking an open link is allowed (resend); everything else about a link is immutable.
    IF NOT (NEW.revoked_at IS NOT NULL AND OLD.revoked_at IS NULL AND OLD.used_at IS NULL
            AND NEW.id = OLD.id AND NEW.token_sha256 = OLD.token_sha256 AND NEW.expires_at = OLD.expires_at) THEN
      RAISE EXCEPTION 'an invitation is single-use and immutable' USING ERRCODE = 'P0001';
    END IF;
  END IF;
  IF NEW.used_at IS NOT NULL AND OLD.used_at IS NULL THEN
    IF OLD.expires_at <= clock_timestamp() THEN
      RAISE EXCEPTION 'the invitation has expired' USING ERRCODE = 'P0001';
    END IF;
    NEW.used_txid := txid_current();
  END IF;
  RETURN NEW;
END
$fn$;

--changeset sdlc:0032-grants
GRANT EXECUTE ON FUNCTION app.org_admin_request_executed(text, uuid, uuid) TO sdlc_app;
GRANT SELECT, INSERT ON app.organisation_admin_request TO sdlc_app;
GRANT UPDATE (status, approved_by, executed_at, decided_by, decided_at) ON app.organisation_admin_request TO sdlc_app;
GRANT SELECT, INSERT, UPDATE ON app.tenant_profile TO sdlc_app;
GRANT SELECT ON app.tenant_profile TO sdlc_worker, sdlc_retention;
