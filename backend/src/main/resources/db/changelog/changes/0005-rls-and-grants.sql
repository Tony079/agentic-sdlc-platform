--liquibase formatted sql

--changeset sdlc:0005-rls splitStatements:false
-- Row-Level Security on every tenant-owned table (PRD FR-AUTH-17). ENABLE + FORCE, so the table owner is
-- also subject to the policy. Exactly one policy per table. Any new tenant table must be added here
-- (SchemaSecurityIT fails if a table with tenant_id lacks forced RLS).
DO $rls$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['app_user', 'audit_event', 'project'] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON app.%I '
                   'USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant())', t);
  END LOOP;
END
$rls$;

--changeset sdlc:0005-grants
-- Least privilege for the runtime role sdlc_app (PRD FR-AUTH-20). No UPDATE/DELETE on the audit trail (FR-AUD-02).
GRANT EXECUTE ON FUNCTION app.current_tenant() TO sdlc_app;
GRANT SELECT ON app.tenant TO sdlc_app;
GRANT SELECT ON app.app_user TO sdlc_app;
GRANT SELECT, INSERT ON app.project TO sdlc_app;
GRANT SELECT, INSERT ON app.audit_event TO sdlc_app;
GRANT INSERT ON app.platform_security_event TO sdlc_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON app.spring_session, app.spring_session_attributes TO sdlc_app;
