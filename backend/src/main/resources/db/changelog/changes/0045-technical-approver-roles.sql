--liquibase formatted sql

-- GD-27 (owner, 2026-11-18): a project member may hold several roles, and a new role, Technical Approver, owns the
-- Technical design stage: after BRD approval the design is handed to the project's Technical Approvers, who start,
-- review and approve it (one Technical Approver may approve alone, after an explicit confirmation, recorded on the
-- approval). The requirement's author follows the design and may request changes, but does not approve it.

--changeset sdlc:0045-member-roles splitStatements:false
CREATE FUNCTION app.distinct_elements(p text[]) RETURNS boolean
  LANGUAGE sql IMMUTABLE SET search_path = pg_catalog
  RETURN cardinality(p) = (SELECT count(DISTINCT x) FROM unnest(p) AS x);
REVOKE ALL ON FUNCTION app.distinct_elements(text[]) FROM PUBLIC;

ALTER TABLE app.project_member ADD COLUMN roles text[];
-- Row-level security is forced even for the table owner; the migration runs without a tenant, so the copy of existing
-- memberships lifts it for this one statement (same transaction, restored immediately).
ALTER TABLE app.project_member NO FORCE ROW LEVEL SECURITY;
UPDATE app.project_member SET roles = ARRAY[role];
ALTER TABLE app.project_member FORCE ROW LEVEL SECURITY;
ALTER TABLE app.project_member ALTER COLUMN roles SET NOT NULL;
ALTER TABLE app.project_member ADD CONSTRAINT project_member_roles_check CHECK (
  cardinality(roles) BETWEEN 1 AND 4
  AND roles <@ ARRAY['REQUIREMENT_AUTHOR', 'BRD_APPROVER', 'TECHNICAL_APPROVER', 'VIEWER']::text[]
  AND (NOT 'VIEWER' = ANY (roles) OR cardinality(roles) = 1)
  AND app.distinct_elements(roles));

-- Role checks read the roles list (functions replaced before the old column is dropped).
CREATE OR REPLACE FUNCTION app.member_sees_requirement(p_project uuid, p_restricted boolean, p_owner uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN EXISTS (SELECT 1 FROM app.project_member m WHERE m.project_id = p_project
                 AND m.user_id = app.current_member() AND m.removed_at IS NULL
                 AND (NOT p_restricted OR m.roles && ARRAY['BRD_APPROVER', 'TECHNICAL_APPROVER']::text[]
                      OR p_owner = app.current_member()));

CREATE OR REPLACE FUNCTION app.member_writes_project(p_project uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN EXISTS (SELECT 1 FROM app.project_member m WHERE m.project_id = p_project
                 AND m.user_id = app.current_member() AND m.removed_at IS NULL
                 AND m.roles && ARRAY['REQUIREMENT_AUTHOR', 'BRD_APPROVER']::text[]);

CREATE OR REPLACE FUNCTION app.is_project_approver(p_tenant uuid, p_user uuid, p_requirement uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN EXISTS (SELECT 1 FROM app.app_user u
                 JOIN app.requirement r ON r.tenant_id = u.tenant_id AND r.id = p_requirement
                 JOIN app.project_member m ON m.project_id = r.project_id AND m.user_id = u.id
                   AND 'BRD_APPROVER' = ANY (m.roles) AND m.removed_at IS NULL
                 WHERE u.tenant_id = p_tenant AND u.id = p_user AND u.status = 'ACTIVE');

CREATE OR REPLACE FUNCTION app.is_brd_approver(p_project uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN app.current_member() IS NOT NULL AND EXISTS (SELECT 1 FROM app.project_member m JOIN app.app_user u ON u.id = m.user_id
         WHERE m.project_id = p_project AND m.user_id = app.current_member() AND m.removed_at IS NULL
           AND 'BRD_APPROVER' = ANY (m.roles) AND u.status = 'ACTIVE');

CREATE FUNCTION app.is_technical_approver(p_project uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN app.current_member() IS NOT NULL AND EXISTS (SELECT 1 FROM app.project_member m JOIN app.app_user u ON u.id = m.user_id
         WHERE m.project_id = p_project AND m.user_id = app.current_member() AND m.removed_at IS NULL
           AND 'TECHNICAL_APPROVER' = ANY (m.roles) AND u.status = 'ACTIVE');
REVOKE ALL ON FUNCTION app.is_technical_approver(uuid) FROM PUBLIC;

ALTER TABLE app.project_member DROP COLUMN role;
GRANT UPDATE (roles) ON app.project_member TO sdlc_app;
GRANT EXECUTE ON FUNCTION app.distinct_elements(text[]) TO sdlc_app, sdlc_worker, sdlc_retention;
GRANT EXECUTE ON FUNCTION app.is_technical_approver(uuid) TO sdlc_app, sdlc_worker, sdlc_retention;

--changeset sdlc:0045-design-ownership splitStatements:false
-- The design is started by a Technical Approver; a new version for a change request may also come from the author.
CREATE OR REPLACE FUNCTION app.tdd_version_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  member uuid := app.current_member();
  owner uuid;
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'design versions are not deleted' USING ERRCODE = 'P0001';
  END IF;
  IF TG_OP = 'INSERT' THEN
    SELECT created_by INTO owner FROM app.requirement WHERE id = NEW.requirement_id;
    IF NEW.status <> 'GENERATING' OR NEW.content_hash IS NOT NULL THEN
      RAISE EXCEPTION 'a design version starts empty, generating' USING ERRCODE = 'P0001';
    END IF;
    IF member IS NULL OR member IS DISTINCT FROM NEW.requested_by
       OR NOT (app.is_technical_approver(NEW.project_id) OR (member = owner AND NEW.change_request IS NOT NULL)) THEN
      RAISE EXCEPTION 'a design is started by a Technical Approver of the project' USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM app.brd_version b WHERE b.id = NEW.brd_version_id
                   AND b.requirement_id = NEW.requirement_id AND b.status = 'RELEASED') THEN
      RAISE EXCEPTION 'a design is made from the approved BRD' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.version_no <> coalesce((SELECT max(version_no) FROM app.tdd_version WHERE requirement_id = NEW.requirement_id), 0) + 1 THEN
      RAISE EXCEPTION 'design versions are numbered in order' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.requirement_id <> OLD.requirement_id
     OR NEW.project_id <> OLD.project_id OR NEW.version_no <> OLD.version_no OR NEW.brd_version_id <> OLD.brd_version_id
     OR NEW.requested_by <> OLD.requested_by OR NEW.change_request IS DISTINCT FROM OLD.change_request
     OR NEW.created_at <> OLD.created_at THEN
    RAISE EXCEPTION 'design version identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  IF OLD.status <> 'GENERATING' AND (NEW.summary IS DISTINCT FROM OLD.summary
     OR NEW.open_questions IS DISTINCT FROM OLD.open_questions OR NEW.content_hash IS DISTINCT FROM OLD.content_hash
     OR NEW.model_provider IS DISTINCT FROM OLD.model_provider OR NEW.model_id IS DISTINCT FROM OLD.model_id) THEN
    RAISE EXCEPTION 'design content is immutable once produced' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status <> OLD.status AND NOT (
       (OLD.status = 'GENERATING' AND NEW.status IN ('IN_REVIEW', 'FAILED'))
    OR (OLD.status = 'FAILED' AND NEW.status = 'GENERATING')
    OR (OLD.status = 'IN_REVIEW' AND NEW.status = 'SUPERSEDED')
    OR (OLD.status = 'IN_REVIEW' AND NEW.status = 'APPROVED'
        AND EXISTS (SELECT 1 FROM app.tdd_decision d WHERE d.tdd_version_id = OLD.id AND d.kind = 'APPROVED'))
    OR (OLD.status = 'APPROVED' AND NEW.status = 'SUPERSEDED')) THEN
    RAISE EXCEPTION 'design status % cannot become %', OLD.status, NEW.status USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status <> OLD.status THEN
    NEW.status_at := clock_timestamp();
  END IF;
  RETURN NEW;
END
$fn$;

-- Technical Approvers review (accept flagged items) and approve; one may approve alone, with the confirmation noted on
-- the approval. The author (or a Technical Approver) may request changes.
CREATE OR REPLACE FUNCTION app.tdd_decision_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  member uuid := app.current_member();
  owner uuid;
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'design decisions are append-only' USING ERRCODE = 'P0001';
  END IF;
  SELECT created_by INTO owner FROM app.requirement WHERE id = NEW.requirement_id;
  IF NOT EXISTS (SELECT 1 FROM app.tdd_version v WHERE v.id = NEW.tdd_version_id
                 AND v.requirement_id = NEW.requirement_id AND v.status = 'IN_REVIEW') THEN
    RAISE EXCEPTION 'only a design in review can be decided' USING ERRCODE = 'P0001';
  END IF;
  IF member IS NULL OR member IS DISTINCT FROM NEW.decided_by THEN
    RAISE EXCEPTION 'a design decision is made by the signed-in member' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.kind = 'AUTHOR_CONFIRMED' THEN
    RAISE EXCEPTION 'the design is approved by a Technical Approver; the author does not confirm it' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.kind = 'FLAG_ACCEPTED' AND NOT app.is_technical_approver(NEW.project_id) THEN
    RAISE EXCEPTION 'flagged design items are decided by a Technical Approver' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.kind = 'APPROVED' AND (NOT app.is_technical_approver(NEW.project_id) OR NEW.note IS NULL) THEN
    RAISE EXCEPTION 'a Technical Approver approves the design, with the confirmation recorded' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.kind = 'CHANGES_REQUESTED' AND member <> owner AND NOT app.is_technical_approver(NEW.project_id) THEN
    RAISE EXCEPTION 'changes are requested by the author or a Technical Approver' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;

--changeset sdlc:0045-worker-handover-grants
-- The worker hands an approved BRD to the project's Technical Approvers (or tells the Organisation Admins that there
-- are none). It may read memberships and each user's role and status, never names or email addresses.
GRANT SELECT (tenant_id, project_id, user_id, roles, removed_at) ON app.project_member TO sdlc_worker;
GRANT SELECT (id, tenant_id, role, status) ON app.app_user TO sdlc_worker;

--changeset sdlc:0045-notification-email-backfill splitStatements:false
-- 0043 meant to mark notifications created before email existed as SKIPPED, but under forced row-level security its
-- UPDATE changed no rows on existing databases. Corrected here: notifications created before 0043 ran, and not yet
-- emailed, are never emailed late.
ALTER TABLE app.notification NO FORCE ROW LEVEL SECURITY;
UPDATE app.notification SET email_status = 'SKIPPED'
WHERE email_status = 'PENDING'
  AND created_at < coalesce((SELECT dateexecuted FROM migration_meta.databasechangelog
                             WHERE id = '0043-notification-email' AND author = 'sdlc'), '-infinity'::timestamp);
ALTER TABLE app.notification FORCE ROW LEVEL SECURITY;
