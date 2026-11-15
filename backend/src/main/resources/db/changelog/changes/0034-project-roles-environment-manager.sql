--liquibase formatted sql

-- Garuda v2 (owner decision GD-18, 2026-11-15): project roles are Requirement Author, BRD Approver and Viewer; each
-- project names one Environment Manager (an active member), who will register its environments (GD-20).

--changeset sdlc:0034-role-rename splitStatements:false
ALTER TABLE app.project_member DROP CONSTRAINT project_member_role_check;
UPDATE app.project_member SET role = CASE role WHEN 'REQUESTER' THEN 'REQUIREMENT_AUTHOR' WHEN 'APPROVER' THEN 'BRD_APPROVER'
                                              ELSE role END;
ALTER TABLE app.project_member ADD CONSTRAINT project_member_role_check
  CHECK (role IN ('REQUIREMENT_AUTHOR', 'BRD_APPROVER', 'VIEWER'));

CREATE OR REPLACE FUNCTION app.member_sees_requirement(p_project uuid, p_restricted boolean, p_owner uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN EXISTS (SELECT 1 FROM app.project_member m WHERE m.project_id = p_project
                 AND m.user_id = app.current_member() AND m.removed_at IS NULL
                 AND (NOT p_restricted OR m.role = 'BRD_APPROVER' OR p_owner = app.current_member()));

CREATE OR REPLACE FUNCTION app.member_writes_project(p_project uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN EXISTS (SELECT 1 FROM app.project_member m WHERE m.project_id = p_project
                 AND m.user_id = app.current_member() AND m.removed_at IS NULL
                 AND m.role IN ('REQUIREMENT_AUTHOR', 'BRD_APPROVER'));

CREATE OR REPLACE FUNCTION app.is_project_approver(p_tenant uuid, p_user uuid, p_requirement uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, app
  RETURN EXISTS (SELECT 1 FROM app.app_user u
                 JOIN app.requirement r ON r.tenant_id = u.tenant_id AND r.id = p_requirement
                 JOIN app.project_member m ON m.project_id = r.project_id AND m.user_id = u.id
                   AND m.role = 'BRD_APPROVER' AND m.removed_at IS NULL
                 WHERE u.tenant_id = p_tenant AND u.id = p_user AND u.status = 'ACTIVE');

--changeset sdlc:0034-environment-manager splitStatements:false
ALTER TABLE app.project ADD COLUMN environment_manager_id uuid;
ALTER TABLE app.project ADD CONSTRAINT project_environment_manager_fk
  FOREIGN KEY (tenant_id, environment_manager_id) REFERENCES app.app_user (tenant_id, id);

-- The Environment Manager must be an active member of the project; leaving the project clears the role.
CREATE FUNCTION app.project_environment_manager_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF NEW.environment_manager_id IS NOT NULL AND NEW.environment_manager_id IS DISTINCT FROM OLD.environment_manager_id
     AND NOT EXISTS (SELECT 1 FROM app.project_member m JOIN app.app_user u ON u.id = m.user_id
                     WHERE m.project_id = NEW.id AND m.user_id = NEW.environment_manager_id AND m.removed_at IS NULL
                       AND u.status IN ('INVITED', 'ACTIVE')) THEN
    RAISE EXCEPTION 'the Environment Manager must be a member of the project' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.project_environment_manager_guard() FROM PUBLIC;
CREATE TRIGGER project_environment_manager_guard BEFORE UPDATE OF environment_manager_id ON app.project
  FOR EACH ROW EXECUTE FUNCTION app.project_environment_manager_guard();

CREATE FUNCTION app.project_member_removed_clears_manager() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF NEW.removed_at IS NOT NULL AND OLD.removed_at IS NULL THEN
    UPDATE app.project SET environment_manager_id = NULL
    WHERE id = NEW.project_id AND environment_manager_id = NEW.user_id;
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.project_member_removed_clears_manager() FROM PUBLIC;
CREATE TRIGGER project_member_removed_clears_manager AFTER UPDATE OF removed_at ON app.project_member
  FOR EACH ROW EXECUTE FUNCTION app.project_member_removed_clears_manager();

GRANT UPDATE (environment_manager_id) ON app.project TO sdlc_app;
