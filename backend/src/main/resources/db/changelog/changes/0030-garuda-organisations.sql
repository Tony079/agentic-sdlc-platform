--liquibase formatted sql

-- Garuda platform model (owner decisions GD-01 to GD-14, 2026-11-13; docs/architecture/garuda-platform-model.md):
-- organisation onboarding and suspension by Garuda staff (two-person), Organisation Admins, per-project roles,
-- invitations and password resets, client MFA columns, restricted requirements, and project membership enforced by
-- row-level security for client users. Forward-only; earlier changesets are unchanged.

--changeset sdlc:0030-staff-capabilities
-- GD-03: ORG_ONBOARD creates, suspends and reactivates organisations (two people). AUDITOR is the PRD section 4 role.
ALTER TABLE app.staff_capability_grant DROP CONSTRAINT staff_capability_grant_capability_check;
ALTER TABLE app.staff_capability_grant ADD CONSTRAINT staff_capability_grant_capability_check
  CHECK (capability IN ('STAFF_ADMIN', 'REPOSITORY_REGISTER', 'BUSINESS_REVIEWER', 'TENANT_DELETE', 'RELEASE_APPROVE',
                        'FEATURE_FLAG_ACK', 'JOB_RETRY', 'BUSINESS_ANALYST', 'ORG_ONBOARD', 'AUDITOR'));

--changeset sdlc:0030-staff-capability-function splitStatements:false
-- True when the staff member is active, MFA-enrolled and holds the capability (not tenant-bound).
CREATE FUNCTION app.staff_holds(p_staff uuid, p_capability text) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN app.staff_ready(p_staff)
     AND EXISTS (SELECT 1 FROM app.staff_capability_grant g WHERE g.staff_id = p_staff AND g.capability = p_capability
                 AND g.revoked_at IS NULL);
REVOKE ALL ON FUNCTION app.staff_holds(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.staff_holds(uuid, text) TO sdlc_app;

--changeset sdlc:0030-organisation-onboarding splitStatements:false
-- GD-03 / FR-ORG-01: a staff member requests a new organisation; a different staff member, also holding ORG_ONBOARD,
-- approves it. Approval executes in one transaction: the request becomes EXECUTED, the tenant row and its first
-- Organisation Admin (INVITED) are inserted. Global table: the organisation does not exist yet when it is requested.
CREATE TABLE app.organisation_onboarding_request (
  id                  uuid        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  organisation_name   text        NOT NULL CHECK (length(organisation_name) BETWEEN 1 AND 200),
  organisation_slug   text        NOT NULL CHECK (organisation_slug ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
  admin_email         text        NOT NULL CHECK (length(admin_email) BETWEEN 3 AND 320),
  admin_display_name  text        NOT NULL CHECK (length(admin_display_name) BETWEEN 1 AND 200),
  status              text        NOT NULL DEFAULT 'REQUESTED'
                                  CHECK (status IN ('REQUESTED', 'EXECUTED', 'REJECTED', 'CANCELLED')),
  requested_by        uuid        NOT NULL REFERENCES app.staff_user (id),
  requested_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  approved_by         uuid        REFERENCES app.staff_user (id),
  executed_at         timestamptz,
  executed_txid       bigint,
  created_tenant_id   uuid,
  decided_by          uuid        REFERENCES app.staff_user (id),
  decided_at          timestamptz,
  decision_note       text        CHECK (length(decision_note) <= 2000),
  CHECK (approved_by IS NULL OR approved_by <> requested_by),
  CHECK ((status = 'EXECUTED') = (approved_by IS NOT NULL AND executed_at IS NOT NULL AND created_tenant_id IS NOT NULL)),
  CHECK ((status IN ('REJECTED', 'CANCELLED')) = (decided_by IS NOT NULL AND decided_at IS NOT NULL))
);
-- The tenant row is inserted after the request becomes EXECUTED, in the same transaction.
ALTER TABLE app.organisation_onboarding_request ADD CONSTRAINT organisation_onboarding_request_tenant_fk
  FOREIGN KEY (created_tenant_id) REFERENCES app.tenant (id) DEFERRABLE INITIALLY DEFERRED;
CREATE UNIQUE INDEX organisation_onboarding_open_slug_uq ON app.organisation_onboarding_request (lower(organisation_slug))
  WHERE status = 'REQUESTED';

CREATE FUNCTION app.organisation_onboarding_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'REQUESTED' OR NEW.approved_by IS NOT NULL OR NEW.executed_at IS NOT NULL
       OR NEW.created_tenant_id IS NOT NULL OR NEW.decided_by IS NOT NULL THEN
      RAISE EXCEPTION 'an onboarding request starts as REQUESTED' USING ERRCODE = 'P0001';
    END IF;
    IF NOT app.staff_holds(NEW.requested_by, 'ORG_ONBOARD') THEN
      RAISE EXCEPTION 'the requester needs ORG_ONBOARD' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.organisation_name <> OLD.organisation_name OR NEW.organisation_slug <> OLD.organisation_slug
     OR NEW.admin_email <> OLD.admin_email OR NEW.admin_display_name <> OLD.admin_display_name
     OR NEW.requested_by <> OLD.requested_by OR NEW.requested_at <> OLD.requested_at THEN
    RAISE EXCEPTION 'onboarding request identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status <> 'REQUESTED' THEN
    RAISE EXCEPTION 'onboarding request % is final', OLD.id USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status = 'EXECUTED' THEN
    IF NEW.approved_by = OLD.requested_by OR NOT app.staff_holds(NEW.approved_by, 'ORG_ONBOARD') THEN
      RAISE EXCEPTION 'approval needs a second staff member with ORG_ONBOARD' USING ERRCODE = 'P0001';
    END IF;
    NEW.executed_txid := txid_current();
  ELSIF NEW.status IN ('REJECTED', 'CANCELLED') THEN
    IF NOT app.staff_holds(NEW.decided_by, 'ORG_ONBOARD')
       OR (NEW.status = 'CANCELLED' AND NEW.decided_by <> OLD.requested_by) THEN
      RAISE EXCEPTION 'only staff with ORG_ONBOARD may reject; only the requester may cancel' USING ERRCODE = 'P0001';
    END IF;
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.organisation_onboarding_guard() FROM PUBLIC;
CREATE TRIGGER organisation_onboarding_guard BEFORE INSERT OR UPDATE ON app.organisation_onboarding_request
  FOR EACH ROW EXECUTE FUNCTION app.organisation_onboarding_guard();

-- The API login may insert a tenant only as the execution of an onboarding request approved in the same transaction.
CREATE FUNCTION app.tenant_insert_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF current_user = 'sdlc_app' AND (NEW.status <> 'ACTIVE' OR NOT EXISTS (
       SELECT 1 FROM app.organisation_onboarding_request r
       WHERE r.created_tenant_id = NEW.id AND r.status = 'EXECUTED' AND r.executed_txid = txid_current()
         AND lower(r.organisation_slug) = lower(NEW.slug) AND r.organisation_name = NEW.name)) THEN
    RAISE EXCEPTION 'organisations are created only through an approved onboarding request' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.tenant_insert_guard() FROM PUBLIC;
CREATE TRIGGER tenant_insert_guard BEFORE INSERT ON app.tenant FOR EACH ROW EXECUTE FUNCTION app.tenant_insert_guard();

GRANT SELECT, INSERT ON app.organisation_onboarding_request TO sdlc_app;
GRANT UPDATE (status, approved_by, executed_at, created_tenant_id, decided_by, decided_at, decision_note)
  ON app.organisation_onboarding_request TO sdlc_app;
GRANT INSERT (id, slug, name, status) ON app.tenant TO sdlc_app;

--changeset sdlc:0030-organisation-status splitStatements:false
-- FR-ORG-03: suspension and reactivation, requested by one ORG_ONBOARD holder and approved by another.
CREATE TABLE app.organisation_status_request (
  id                 uuid        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  subject_tenant_id  uuid        NOT NULL REFERENCES app.tenant (id),
  kind               text        NOT NULL CHECK (kind IN ('SUSPEND', 'REACTIVATE')),
  status             text        NOT NULL DEFAULT 'REQUESTED'
                                 CHECK (status IN ('REQUESTED', 'EXECUTED', 'REJECTED', 'CANCELLED')),
  reason             text        NOT NULL CHECK (length(reason) BETWEEN 10 AND 2000),
  requested_by       uuid        NOT NULL REFERENCES app.staff_user (id),
  requested_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  approved_by        uuid        REFERENCES app.staff_user (id),
  executed_at        timestamptz,
  executed_txid      bigint,
  decided_by         uuid        REFERENCES app.staff_user (id),
  decided_at         timestamptz,
  CHECK (approved_by IS NULL OR approved_by <> requested_by),
  CHECK ((status = 'EXECUTED') = (approved_by IS NOT NULL AND executed_at IS NOT NULL)),
  CHECK ((status IN ('REJECTED', 'CANCELLED')) = (decided_by IS NOT NULL AND decided_at IS NOT NULL))
);
CREATE UNIQUE INDEX organisation_status_request_open_uq ON app.organisation_status_request (subject_tenant_id)
  WHERE status = 'REQUESTED';

CREATE FUNCTION app.organisation_status_request_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'REQUESTED' OR NEW.approved_by IS NOT NULL OR NEW.decided_by IS NOT NULL THEN
      RAISE EXCEPTION 'a status request starts as REQUESTED' USING ERRCODE = 'P0001';
    END IF;
    IF NOT app.staff_holds(NEW.requested_by, 'ORG_ONBOARD') THEN
      RAISE EXCEPTION 'the requester needs ORG_ONBOARD' USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM app.tenant t WHERE t.id = NEW.subject_tenant_id
                   AND t.status = CASE NEW.kind WHEN 'SUSPEND' THEN 'ACTIVE' ELSE 'SUSPENDED' END) THEN
      RAISE EXCEPTION 'only an active organisation can be suspended, and only a suspended one reactivated'
        USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.subject_tenant_id <> OLD.subject_tenant_id OR NEW.kind <> OLD.kind
     OR NEW.reason <> OLD.reason OR NEW.requested_by <> OLD.requested_by THEN
    RAISE EXCEPTION 'status request identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status <> 'REQUESTED' THEN
    RAISE EXCEPTION 'status request % is final', OLD.id USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status = 'EXECUTED' THEN
    IF NEW.approved_by = OLD.requested_by OR NOT app.staff_holds(NEW.approved_by, 'ORG_ONBOARD') THEN
      RAISE EXCEPTION 'approval needs a second staff member with ORG_ONBOARD' USING ERRCODE = 'P0001';
    END IF;
    NEW.executed_txid := txid_current();
  ELSIF NEW.status IN ('REJECTED', 'CANCELLED') THEN
    IF NOT app.staff_holds(NEW.decided_by, 'ORG_ONBOARD')
       OR (NEW.status = 'CANCELLED' AND NEW.decided_by <> OLD.requested_by) THEN
      RAISE EXCEPTION 'only staff with ORG_ONBOARD may reject; only the requester may cancel' USING ERRCODE = 'P0001';
    END IF;
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.organisation_status_request_guard() FROM PUBLIC;
CREATE TRIGGER organisation_status_request_guard BEFORE INSERT OR UPDATE ON app.organisation_status_request
  FOR EACH ROW EXECUTE FUNCTION app.organisation_status_request_guard();

-- Replaces the 0025 guard (same rules for deletion and restoration) and adds ACTIVE <-> SUSPENDED through an
-- organisation status request executed in the same transaction.
CREATE OR REPLACE FUNCTION app.tenant_status_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status AND current_user = 'sdlc_app' THEN
    IF (OLD.status = 'ACTIVE' AND NEW.status = 'DELETED') OR (OLD.status = 'DELETED' AND NEW.status = 'ACTIVE') THEN
      IF NOT EXISTS (SELECT 1 FROM app.tenant_lifecycle_request r WHERE r.subject_tenant_id = NEW.id
                     AND r.status = 'EXECUTED' AND r.executed_txid = txid_current()
                     AND r.kind = CASE WHEN NEW.status = 'DELETED' THEN 'DELETE' ELSE 'RESTORE' END) THEN
        RAISE EXCEPTION 'tenant status changes only through an approved lifecycle request' USING ERRCODE = 'P0001';
      END IF;
    ELSIF (OLD.status = 'ACTIVE' AND NEW.status = 'SUSPENDED') OR (OLD.status = 'SUSPENDED' AND NEW.status = 'ACTIVE') THEN
      IF NOT EXISTS (SELECT 1 FROM app.organisation_status_request r WHERE r.subject_tenant_id = NEW.id
                     AND r.status = 'EXECUTED' AND r.executed_txid = txid_current()
                     AND r.kind = CASE WHEN NEW.status = 'SUSPENDED' THEN 'SUSPEND' ELSE 'REACTIVATE' END) THEN
        RAISE EXCEPTION 'organisation suspension changes only through an approved status request' USING ERRCODE = 'P0001';
      END IF;
    ELSE
      RAISE EXCEPTION 'tenant status change % to % is not allowed', OLD.status, NEW.status USING ERRCODE = 'P0001';
    END IF;
  END IF;
  RETURN NEW;
END
$fn$;

GRANT SELECT, INSERT ON app.organisation_status_request TO sdlc_app;
GRANT UPDATE (status, approved_by, executed_at, decided_by, decided_at) ON app.organisation_status_request TO sdlc_app;

--changeset sdlc:0030-organisation-users splitStatements:false
-- GD-02: organisation users are ORG_ADMIN or MEMBER; what a member may do comes from project roles. Existing users
-- are migrated: every CLIENT_APPROVER (an organisation-wide approver until now) becomes ORG_ADMIN; CLIENT_USER becomes
-- MEMBER. Invited users have no password until they accept (FR-ORG-07). GD-08: TOTP columns for client users.
ALTER TABLE app.app_user DROP CONSTRAINT app_user_role_check;
UPDATE app.app_user SET role = CASE role WHEN 'CLIENT_APPROVER' THEN 'ORG_ADMIN' ELSE 'MEMBER' END;
ALTER TABLE app.app_user ADD CONSTRAINT app_user_role_check CHECK (role IN ('ORG_ADMIN', 'MEMBER'));
ALTER TABLE app.app_user ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE app.app_user ADD CONSTRAINT app_user_password_when_active CHECK (status = 'INVITED' OR password_hash IS NOT NULL);
ALTER TABLE app.app_user
  ADD COLUMN mfa_secret_enc  text   CHECK (length(mfa_secret_enc) <= 512),
  ADD COLUMN mfa_enrolled_at timestamptz,
  ADD COLUMN totp_last_step  bigint CHECK (totp_last_step >= 0),
  ADD COLUMN activated_at    timestamptz,
  ADD COLUMN status_changed_at timestamptz,
  ADD CONSTRAINT app_user_mfa_consistent CHECK (mfa_enrolled_at IS NULL OR mfa_secret_enc IS NOT NULL);
UPDATE app.app_user SET activated_at = created_at WHERE status = 'ACTIVE';

-- True when the user is an active Organisation Admin of the current tenant (RLS applies to the lookup).
CREATE FUNCTION app.is_org_admin(p_user uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN EXISTS (SELECT 1 FROM app.app_user u WHERE u.id = p_user AND u.role = 'ORG_ADMIN' AND u.status = 'ACTIVE');
REVOKE ALL ON FUNCTION app.is_org_admin(uuid) FROM PUBLIC;

--changeset sdlc:0030-projects-and-members splitStatements:false
ALTER TABLE app.project
  ADD COLUMN description text CHECK (length(description) <= 2000),
  ADD COLUMN status      text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
  ADD COLUMN archived_at timestamptz,
  ADD CONSTRAINT project_archived_consistent CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL));

-- GD-02: a user's role in one project. Removal is soft (removed_at), so history and audit stay intact.
CREATE TABLE app.project_member (
  id          uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id   uuid        NOT NULL REFERENCES app.tenant (id),
  project_id  uuid        NOT NULL,
  user_id     uuid        NOT NULL,
  role        text        NOT NULL CHECK (role IN ('REQUESTER', 'APPROVER', 'VIEWER')),
  added_by    uuid        NOT NULL,
  added_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  removed_by  uuid,
  removed_at  timestamptz,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, user_id) REFERENCES app.app_user (tenant_id, id),
  FOREIGN KEY (tenant_id, added_by) REFERENCES app.app_user (tenant_id, id),
  FOREIGN KEY (tenant_id, removed_by) REFERENCES app.app_user (tenant_id, id),
  CHECK ((removed_at IS NULL) = (removed_by IS NULL))
);
CREATE UNIQUE INDEX project_member_active_uq ON app.project_member (project_id, user_id) WHERE removed_at IS NULL;
CREATE INDEX project_member_user_ix ON app.project_member (tenant_id, user_id) WHERE removed_at IS NULL;

-- FR-ORG-06 / FR-ACC-04: memberships are managed only by an active Organisation Admin of the tenant (app.actor_id);
-- the role of an active membership and its removal are the only changes allowed; nobody manages their own role.
CREATE FUNCTION app.project_member_guard() RETURNS trigger
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
    IF NOT EXISTS (SELECT 1 FROM app.project p WHERE p.id = NEW.project_id AND p.status = 'ACTIVE') THEN
      RAISE EXCEPTION 'members are added only to active projects' USING ERRCODE = 'P0001';
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
REVOKE ALL ON FUNCTION app.project_member_guard() FROM PUBLIC;
CREATE TRIGGER project_member_guard BEFORE INSERT OR UPDATE ON app.project_member
  FOR EACH ROW EXECUTE FUNCTION app.project_member_guard();

ALTER TABLE app.project_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.project_member FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.project_member
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());
GRANT SELECT, INSERT ON app.project_member TO sdlc_app;
GRANT UPDATE (role, removed_by, removed_at) ON app.project_member TO sdlc_app;

-- Projects are created, renamed and archived by an Organisation Admin.
CREATE FUNCTION app.project_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  actor uuid := nullif(current_setting('app.actor_id', true), '')::uuid;
BEGIN
  IF current_user = 'sdlc_app' AND (actor IS NULL OR NOT app.is_org_admin(actor)) THEN
    RAISE EXCEPTION 'projects are managed only by an Organisation Admin' USING ERRCODE = 'P0001';
  END IF;
  IF TG_OP = 'UPDATE' AND (NEW.id <> OLD.id OR NEW.created_by <> OLD.created_by OR NEW.created_at <> OLD.created_at
                           OR OLD.status = 'ARCHIVED') THEN
    RAISE EXCEPTION 'project identity is immutable and archived projects are final' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.project_guard() FROM PUBLIC;
CREATE TRIGGER project_guard BEFORE INSERT OR UPDATE ON app.project FOR EACH ROW EXECUTE FUNCTION app.project_guard();
GRANT UPDATE (name, description, status, archived_at) ON app.project TO sdlc_app;

-- Data migration: existing projects keep working for the people who used them. Every Organisation Admin becomes an
-- APPROVER of every project (they approved organisation-wide until now); every other user becomes a REQUESTER of each
-- project they created or have a requirement in. Runs as the migrator (the guard applies to sdlc_app only).
INSERT INTO app.project_member (tenant_id, project_id, user_id, role, added_by)
SELECT p.tenant_id, p.id, u.id, 'APPROVER', u.id
FROM app.project p JOIN app.app_user u ON u.tenant_id = p.tenant_id AND u.role = 'ORG_ADMIN';
INSERT INTO app.project_member (tenant_id, project_id, user_id, role, added_by)
SELECT DISTINCT x.tenant_id, x.project_id, x.user_id, 'REQUESTER', x.user_id FROM (
  SELECT p.tenant_id, p.id AS project_id, p.created_by AS user_id FROM app.project p
  UNION SELECT r.tenant_id, r.project_id, r.created_by FROM app.requirement r) x
JOIN app.app_user u ON u.id = x.user_id AND u.role = 'MEMBER';

--changeset sdlc:0030-restricted-requirements
-- GD-05 / FR-ACC-02: a restricted requirement is visible only to its owner and the project's Approvers.
ALTER TABLE app.requirement ADD COLUMN restricted boolean NOT NULL DEFAULT false;
GRANT UPDATE (restricted) ON app.requirement TO sdlc_app;

--changeset sdlc:0030-user-administration splitStatements:false
-- FR-ORG-05..07: invitations and password resets. Only a SHA-256 of the token is stored; links are single-use and
-- expire. Accepting one is the only way an INVITED user becomes ACTIVE or a password is set by someone who is not
-- signed in as that user.
CREATE TABLE app.user_invitation (
  id              uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id       uuid        NOT NULL REFERENCES app.tenant (id),
  user_id         uuid        NOT NULL,
  purpose         text        NOT NULL CHECK (purpose IN ('INVITE', 'PASSWORD_RESET')),
  token_sha256    text        NOT NULL UNIQUE CHECK (token_sha256 ~ '^[0-9a-f]{64}$'),
  created_by_user uuid,
  created_by_staff uuid       REFERENCES app.staff_user (id),
  created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at      timestamptz NOT NULL,
  used_at         timestamptz,
  used_txid       bigint,
  revoked_at      timestamptz,
  email_status    text        NOT NULL DEFAULT 'NOT_SENT' CHECK (email_status IN ('NOT_SENT', 'SENT', 'FAILED')),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, user_id) REFERENCES app.app_user (tenant_id, id),
  FOREIGN KEY (tenant_id, created_by_user) REFERENCES app.app_user (tenant_id, id),
  CHECK (expires_at > created_at AND expires_at <= created_at + interval '72 hours'),
  CHECK (NOT (used_at IS NOT NULL AND revoked_at IS NOT NULL)),
  CHECK ((used_at IS NULL) = (used_txid IS NULL))
);
CREATE INDEX user_invitation_user_ix ON app.user_invitation (tenant_id, user_id);

CREATE FUNCTION app.user_invitation_guard() RETURNS trigger
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
    -- Created by an Organisation Admin, by the user for their own password reset, or by the staff member who executes
    -- an onboarding request in this transaction (first admin).
    IF NOT ((NEW.created_by_user IS NOT NULL AND NEW.created_by_user = actor
             AND (app.is_org_admin(actor) OR (NEW.purpose = 'PASSWORD_RESET' AND NEW.user_id = actor)))
            OR (NEW.created_by_staff IS NOT NULL AND NEW.created_by_staff = actor AND NEW.purpose = 'INVITE'
                AND EXISTS (SELECT 1 FROM app.organisation_onboarding_request r
                            WHERE r.created_tenant_id = NEW.tenant_id AND r.executed_txid = txid_current()))
            -- Self-service "forgot password" by someone not signed in: the link is only ever emailed, never shown.
            OR (NEW.purpose = 'PASSWORD_RESET' AND actor IS NULL AND NEW.created_by_user IS NULL
                AND NEW.created_by_staff IS NULL)) THEN
      RAISE EXCEPTION 'invitations are created by an Organisation Admin' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.user_id <> OLD.user_id OR NEW.purpose <> OLD.purpose OR NEW.token_sha256 <> OLD.token_sha256
     OR NEW.expires_at <> OLD.expires_at OR OLD.used_at IS NOT NULL OR OLD.revoked_at IS NOT NULL THEN
    RAISE EXCEPTION 'an invitation is single-use and immutable' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.used_at IS NOT NULL THEN
    IF OLD.expires_at <= clock_timestamp() THEN
      RAISE EXCEPTION 'the invitation has expired' USING ERRCODE = 'P0001';
    END IF;
    NEW.used_txid := txid_current();
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.user_invitation_guard() FROM PUBLIC;
CREATE TRIGGER user_invitation_guard BEFORE INSERT OR UPDATE ON app.user_invitation
  FOR EACH ROW EXECUTE FUNCTION app.user_invitation_guard();

ALTER TABLE app.user_invitation ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.user_invitation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.user_invitation
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());
GRANT SELECT, INSERT ON app.user_invitation TO sdlc_app;
GRANT UPDATE (used_at, revoked_at, email_status) ON app.user_invitation TO sdlc_app;

-- User records: inserted INVITED by an Organisation Admin (or as the first admin of an organisation executed in this
-- transaction); role and status changed only by an Organisation Admin, never on oneself, and never leaving the
-- organisation without an active Organisation Admin; password set only by the user themselves or by consuming an
-- invitation in this transaction; INVITED becomes ACTIVE only by consuming an invitation.
CREATE FUNCTION app.app_user_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  actor uuid := nullif(current_setting('app.actor_id', true), '')::uuid;
  consumed boolean;
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
                  AND lower(r.admin_email) = lower(NEW.email)))) THEN
      RAISE EXCEPTION 'users are created only by an Organisation Admin' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.email <> OLD.email OR NEW.created_at <> OLD.created_at THEN
    RAISE EXCEPTION 'user identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  consumed := EXISTS (SELECT 1 FROM app.user_invitation i WHERE i.user_id = NEW.id AND i.used_txid = txid_current());
  IF NEW.role <> OLD.role OR (NEW.status <> OLD.status AND NOT (OLD.status = 'INVITED' AND NEW.status = 'ACTIVE')) THEN
    IF actor IS NULL OR actor = NEW.id OR NOT app.is_org_admin(actor) THEN
      RAISE EXCEPTION 'roles and account status are changed only by another Organisation Admin' USING ERRCODE = 'P0001';
    END IF;
  END IF;
  IF OLD.status = 'INVITED' AND NEW.status = 'ACTIVE' AND NOT consumed THEN
    RAISE EXCEPTION 'an invited user becomes active only by accepting the invitation' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.password_hash IS DISTINCT FROM OLD.password_hash AND NOT consumed AND actor IS DISTINCT FROM NEW.id THEN
    RAISE EXCEPTION 'a password is set only by its user' USING ERRCODE = 'P0001';
  END IF;
  -- MFA: a confirmed secret is never replaced (an unconfirmed one may be, when enrolment restarts); only another
  -- Organisation Admin may clear it (reset), which forces re-enrolment. The replay counter only moves forward.
  IF NEW.mfa_secret_enc IS DISTINCT FROM OLD.mfa_secret_enc THEN
    IF NEW.mfa_secret_enc IS NULL THEN
      IF actor IS NULL OR actor = NEW.id OR NOT app.is_org_admin(actor) THEN
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
REVOKE ALL ON FUNCTION app.app_user_guard() FROM PUBLIC;
CREATE TRIGGER app_user_guard BEFORE INSERT OR UPDATE ON app.app_user FOR EACH ROW EXECUTE FUNCTION app.app_user_guard();

GRANT INSERT (tenant_id, email, display_name, role, status) ON app.app_user TO sdlc_app;
GRANT UPDATE (display_name, role, status, password_hash, mfa_secret_enc, mfa_enrolled_at, totp_last_step,
              activated_at, status_changed_at) ON app.app_user TO sdlc_app;

--changeset sdlc:0030-project-row-security splitStatements:false
-- GD-13 / FR-ACC-04: for a signed-in client user (app.member_id, set per transaction from the session, never from
-- the request), rows of a project are visible only to its members, and a restricted requirement only to its owner and
-- the project's Approvers. Staff, the worker and other system work set no member, so their access is governed by the
-- existing tenant policy and their capability checks (unchanged). These policies are RESTRICTIVE: they narrow the
-- tenant_isolation policy and can never widen it.
CREATE FUNCTION app.current_member() RETURNS uuid
  LANGUAGE sql STABLE PARALLEL SAFE
  RETURN nullif(current_setting('app.member_id', true), '')::uuid;
REVOKE ALL ON FUNCTION app.current_member() FROM PUBLIC;

-- The checks live in invoker-rights functions, so a login that never sets a member (worker, retention) needs only
-- EXECUTE on them, never read access to the membership tables: the bodies run only for signed-in client users.
CREATE FUNCTION app.member_sees_project(p_project uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN app.is_org_admin(app.current_member())
      OR EXISTS (SELECT 1 FROM app.project_member m WHERE m.project_id = p_project
                 AND m.user_id = app.current_member() AND m.removed_at IS NULL);
CREATE FUNCTION app.member_sees_requirement(p_project uuid, p_restricted boolean, p_owner uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN EXISTS (SELECT 1 FROM app.project_member m WHERE m.project_id = p_project
                 AND m.user_id = app.current_member() AND m.removed_at IS NULL
                 AND (NOT p_restricted OR m.role = 'APPROVER' OR p_owner = app.current_member()));
CREATE FUNCTION app.member_writes_project(p_project uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN EXISTS (SELECT 1 FROM app.project_member m WHERE m.project_id = p_project
                 AND m.user_id = app.current_member() AND m.removed_at IS NULL AND m.role IN ('REQUESTER', 'APPROVER'));
-- RLS on requirement applies inside: the row exists for the member only if they may see the requirement.
CREATE FUNCTION app.member_sees_requirement_id(p_requirement uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN EXISTS (SELECT 1 FROM app.requirement r WHERE r.id = p_requirement);
DO $do$
DECLARE
  f text;
BEGIN
  FOREACH f IN ARRAY ARRAY['app.current_member()', 'app.is_org_admin(uuid)', 'app.member_sees_project(uuid)',
                           'app.member_sees_requirement(uuid, boolean, uuid)', 'app.member_writes_project(uuid)',
                           'app.member_sees_requirement_id(uuid)'] LOOP
    EXECUTE 'REVOKE ALL ON FUNCTION ' || f || ' FROM PUBLIC';
    EXECUTE 'GRANT EXECUTE ON FUNCTION ' || f || ' TO sdlc_app, sdlc_worker, sdlc_retention';
  END LOOP;
END
$do$;

-- Projects: members see their projects; Organisation Admins see every project of the organisation (names, members,
-- counts; GD-06 keeps their content hidden through the requirement policy).
CREATE POLICY project_access ON app.project AS RESTRICTIVE
  USING (app.current_member() IS NULL OR app.member_sees_project(id));

-- Memberships: a client user sees their own; an Organisation Admin sees all of the organisation.
CREATE POLICY project_access ON app.project_member AS RESTRICTIVE
  USING (app.current_member() IS NULL OR user_id = app.current_member() OR app.is_org_admin(app.current_member()));

-- Requirements: active members of the project; restricted ones only for the owner and Approvers. Writing needs the
-- REQUESTER or APPROVER role (a VIEWER cannot create or change a requirement).
CREATE POLICY project_access ON app.requirement AS RESTRICTIVE
  USING (app.current_member() IS NULL OR app.member_sees_requirement(project_id, restricted, created_by))
  WITH CHECK (app.current_member() IS NULL OR app.member_writes_project(project_id));

-- Every other tenant table that carries requirement_id follows the visibility of its requirement.
DO $do$
DECLARE
  t text;
BEGIN
  FOR t IN
    SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'requirement_id' AND NOT a.attisdropped
    JOIN pg_attribute ta ON ta.attrelid = c.oid AND ta.attname = 'tenant_id' AND NOT ta.attisdropped
    WHERE n.nspname = 'app' AND c.relkind = 'r' AND c.relname <> 'requirement'
  LOOP
    EXECUTE format('CREATE POLICY project_access ON app.%I AS RESTRICTIVE USING (app.current_member() IS NULL '
                   'OR app.member_sees_requirement_id(requirement_id))', t);
  END LOOP;
END
$do$;

--changeset sdlc:0030-repository-link-approver splitStatements:false
-- C-18 with GD-02: the person who redeems a repository link request and authorises the link must be an active
-- APPROVER of the requirement's project (until now: an organisation-wide CLIENT_APPROVER). Replaces the 0018 guards;
-- all other rules are unchanged. The intended_role column keeps its historical value 'CLIENT_APPROVER'.
CREATE FUNCTION app.is_project_approver(p_tenant uuid, p_user uuid, p_requirement uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN EXISTS (SELECT 1 FROM app.app_user u
                 JOIN app.requirement r ON r.tenant_id = u.tenant_id AND r.id = p_requirement
                 JOIN app.project_member m ON m.project_id = r.project_id AND m.user_id = u.id
                   AND m.role = 'APPROVER' AND m.removed_at IS NULL
                 WHERE u.tenant_id = p_tenant AND u.id = p_user AND u.status = 'ACTIVE');
REVOKE ALL ON FUNCTION app.is_project_approver(uuid, uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.is_project_approver(uuid, uuid, uuid) TO sdlc_app;

CREATE OR REPLACE FUNCTION app.repository_link_request_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF OLD.status <> 'PENDING' THEN
    RAISE EXCEPTION 'link request % is no longer pending', OLD.id USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status = 'USED' THEN
    IF clock_timestamp() >= OLD.expires_at THEN
      RAISE EXCEPTION 'link request % has expired', OLD.id USING ERRCODE = 'P0001';
    END IF;
    IF NOT app.is_project_approver(NEW.tenant_id, NEW.used_by, NEW.requirement_id) THEN
      RAISE EXCEPTION 'only an active Approver of the project may redeem a link request' USING ERRCODE = 'P0001';
    END IF;
  END IF;
  RETURN NEW;
END
$fn$;

CREATE OR REPLACE FUNCTION app.requirement_repository_link_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NOT app.is_project_approver(NEW.tenant_id, NEW.authorized_by, NEW.requirement_id) THEN
      RAISE EXCEPTION 'only an active Approver of the project may authorise a repository link' USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM app.repository_link_request r WHERE r.tenant_id = NEW.tenant_id
                   AND r.id = NEW.request_id AND r.requirement_id = NEW.requirement_id
                   AND r.status = 'USED' AND r.used_by = NEW.authorized_by) THEN
      RAISE EXCEPTION 'a repository link needs the redeemed request of the same requirement' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.status <> 'AUTHORIZED' AND NEW.status <> OLD.status THEN
    RAISE EXCEPTION 'repository link % is final', OLD.id USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
