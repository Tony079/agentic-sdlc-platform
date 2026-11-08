--liquibase formatted sql

--changeset sdlc:0025-tenant-soft-deletion splitStatements:false
-- O-25 tenant deletion (owner decision 2026-11-08): soft deletion only. A Client Approver of the tenant requests it,
-- a staff member holding TENANT_DELETE (assigned to the tenant) verifies it, a second, different TENANT_DELETE holder
-- approves it, and the approval executes it: the tenant becomes DELETED (row and data kept). Restoration follows the
-- same two-person path (requested by staff). Physical erasure is a separate operation that does not exist here.
ALTER TABLE app.tenant DROP CONSTRAINT tenant_status_check;
ALTER TABLE app.tenant ADD CONSTRAINT tenant_status_check
  CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSING', 'CLOSED', 'DELETED'));
ALTER TABLE app.tenant ADD COLUMN status_changed_at timestamptz;

-- Global table (like the staff tables): staff act on a tenant they may no longer enter once it is DELETED. Client
-- endpoints always use the session tenant; staff endpoints check TENANT_DELETE and the assignment to that tenant.
CREATE TABLE app.tenant_lifecycle_request (
  id                 uuid        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  -- Named subject_tenant_id (not tenant_id): a global table, readable before and after the tenant is DELETED.
  subject_tenant_id  uuid        NOT NULL REFERENCES app.tenant (id),
  kind               text        NOT NULL CHECK (kind IN ('DELETE', 'RESTORE')),
  status             text        NOT NULL DEFAULT 'REQUESTED'
                                 CHECK (status IN ('REQUESTED', 'VERIFIED', 'EXECUTED', 'REJECTED', 'CANCELLED')),
  reason             text        NOT NULL CHECK (length(reason) BETWEEN 10 AND 2000),
  requested_by_user  uuid,
  requested_by_staff uuid        REFERENCES app.staff_user (id),
  requested_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  verified_by        uuid        REFERENCES app.staff_user (id),
  verified_at        timestamptz,
  approved_by        uuid        REFERENCES app.staff_user (id),
  executed_at        timestamptz,
  executed_txid      bigint,
  decided_by         uuid        REFERENCES app.staff_user (id),
  decision_note      text        CHECK (length(decision_note) <= 2000),
  FOREIGN KEY (subject_tenant_id, requested_by_user) REFERENCES app.app_user (tenant_id, id),
  CHECK ((kind = 'DELETE') = (requested_by_user IS NOT NULL)),
  CHECK ((kind = 'RESTORE') = (requested_by_staff IS NOT NULL)),
  CHECK (verified_by IS NULL OR verified_by <> requested_by_staff OR requested_by_staff IS NULL),
  CHECK (approved_by IS NULL OR (verified_by IS NOT NULL AND approved_by <> verified_by
                                 AND (requested_by_staff IS NULL OR approved_by <> requested_by_staff))),
  CHECK ((status = 'EXECUTED') = (executed_at IS NOT NULL AND approved_by IS NOT NULL))
);
CREATE UNIQUE INDEX tenant_lifecycle_request_open_uq ON app.tenant_lifecycle_request (subject_tenant_id)
  WHERE status IN ('REQUESTED', 'VERIFIED');

CREATE FUNCTION app.staff_may_delete_tenant(p_staff uuid, p_tenant uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN app.staff_ready(p_staff)
     AND EXISTS (SELECT 1 FROM app.staff_capability_grant g WHERE g.staff_id = p_staff AND g.capability = 'TENANT_DELETE'
                 AND g.revoked_at IS NULL)
     AND EXISTS (SELECT 1 FROM app.staff_tenant_assignment a WHERE a.staff_id = p_staff
                 AND a.assigned_tenant_id = p_tenant AND a.revoked_at IS NULL);
REVOKE ALL ON FUNCTION app.staff_may_delete_tenant(uuid, uuid) FROM PUBLIC;

-- Database-side two-person rule: independent of the application code.
CREATE FUNCTION app.tenant_lifecycle_request_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'REQUESTED' OR NEW.verified_by IS NOT NULL OR NEW.approved_by IS NOT NULL THEN
      RAISE EXCEPTION 'a lifecycle request starts as REQUESTED' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.kind = 'DELETE' AND NOT EXISTS (SELECT 1 FROM app.tenant WHERE id = NEW.subject_tenant_id AND status = 'ACTIVE') THEN
      RAISE EXCEPTION 'only an active tenant can be deleted' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.kind = 'RESTORE' AND (NOT EXISTS (SELECT 1 FROM app.tenant WHERE id = NEW.subject_tenant_id AND status = 'DELETED')
                                 OR NOT app.staff_may_delete_tenant(NEW.requested_by_staff, NEW.subject_tenant_id)) THEN
      RAISE EXCEPTION 'restoration needs a deleted tenant and an authorised requester' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.subject_tenant_id <> OLD.subject_tenant_id OR NEW.kind <> OLD.kind OR NEW.reason <> OLD.reason
     OR NEW.requested_by_user IS DISTINCT FROM OLD.requested_by_user
     OR NEW.requested_by_staff IS DISTINCT FROM OLD.requested_by_staff THEN
    RAISE EXCEPTION 'lifecycle request identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status IN ('EXECUTED', 'REJECTED', 'CANCELLED') THEN
    RAISE EXCEPTION 'lifecycle request % is final', OLD.id USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status = 'VERIFIED' THEN
    IF OLD.status <> 'REQUESTED' OR NOT app.staff_may_delete_tenant(NEW.verified_by, NEW.subject_tenant_id) THEN
      RAISE EXCEPTION 'verification needs an authorised staff member' USING ERRCODE = 'P0001';
    END IF;
  ELSIF NEW.status = 'EXECUTED' THEN
    IF OLD.status <> 'VERIFIED' OR NEW.verified_by <> OLD.verified_by
       OR NOT app.staff_may_delete_tenant(NEW.approved_by, NEW.subject_tenant_id) THEN
      RAISE EXCEPTION 'approval needs a second authorised staff member' USING ERRCODE = 'P0001';
    END IF;
    NEW.executed_txid := txid_current();
  ELSIF NEW.status = 'REJECTED' THEN
    IF NEW.decided_by IS NULL OR NOT app.staff_may_delete_tenant(NEW.decided_by, NEW.subject_tenant_id) THEN
      RAISE EXCEPTION 'rejection needs an authorised staff member' USING ERRCODE = 'P0001';
    END IF;
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.tenant_lifecycle_request_guard() FROM PUBLIC;
CREATE TRIGGER tenant_lifecycle_request_guard BEFORE INSERT OR UPDATE ON app.tenant_lifecycle_request
  FOR EACH ROW EXECUTE FUNCTION app.tenant_lifecycle_request_guard();

-- The API login may change a tenant's status only as the execution of an approved request in the same transaction,
-- and only between ACTIVE and DELETED. No other login is granted UPDATE on tenant.status any more.
CREATE FUNCTION app.tenant_status_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status AND current_user = 'sdlc_app' THEN
    IF NOT ((OLD.status = 'ACTIVE' AND NEW.status = 'DELETED') OR (OLD.status = 'DELETED' AND NEW.status = 'ACTIVE'))
       OR NOT EXISTS (SELECT 1 FROM app.tenant_lifecycle_request r WHERE r.subject_tenant_id = NEW.id AND r.status = 'EXECUTED'
                      AND r.executed_txid = txid_current()
                      AND r.kind = CASE WHEN NEW.status = 'DELETED' THEN 'DELETE' ELSE 'RESTORE' END) THEN
      RAISE EXCEPTION 'tenant status changes only through an approved lifecycle request' USING ERRCODE = 'P0001';
    END IF;
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.tenant_status_guard() FROM PUBLIC;
CREATE TRIGGER tenant_status_guard BEFORE UPDATE ON app.tenant FOR EACH ROW EXECUTE FUNCTION app.tenant_status_guard();

GRANT SELECT, INSERT ON app.tenant_lifecycle_request TO sdlc_app;
GRANT UPDATE (status, verified_by, verified_at, approved_by, executed_at, decided_by, decision_note)
  ON app.tenant_lifecycle_request TO sdlc_app;
GRANT UPDATE (status, status_changed_at) ON app.tenant TO sdlc_app;
GRANT EXECUTE ON FUNCTION app.staff_may_delete_tenant(uuid, uuid) TO sdlc_app;
-- Retention no longer changes tenant status: tenant deletion is the soft-deletion workflow above.
REVOKE UPDATE (status) ON app.tenant FROM sdlc_retention;

--changeset sdlc:0025-retention-classes
-- O-25 retention (owner decision 2026-11-08): extracted document text is a temporary extraction artifact (30 days),
-- uploaded originals and BRDs are business artifacts of a closed requirement (365 days), snapshots 30, audit 365.
ALTER TABLE app.retention_policy DROP CONSTRAINT retention_policy_data_class_check;
ALTER TABLE app.retention_policy ADD CONSTRAINT retention_policy_data_class_check
  CHECK (data_class IN ('SNAPSHOT', 'EXTRACTED_TEXT', 'DOCUMENT', 'BRD', 'AUDIT'));
