--liquibase formatted sql

--changeset sdlc:0009-m2-rls splitStatements:false
-- Forced RLS with exactly one tenant policy on every Milestone 2 tenant-owned table (PRD FR-AUTH-17).
DO $rls$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['requirement_key_counter', 'requirement', 'requirement_revision',
                           'workflow_instance', 'workflow_transition', 'idempotency_record'] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON app.%I '
                   'USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant())', t);
  END LOOP;
END
$rls$;

--changeset sdlc:0009-m2-grants
-- Least privilege for sdlc_app. History tables are append-only (SELECT, INSERT). Mutable rows expose only the
-- columns the application must change. No DELETE anywhere.
GRANT SELECT, INSERT ON app.requirement_key_counter TO sdlc_app;
GRANT UPDATE (last_value) ON app.requirement_key_counter TO sdlc_app;

GRANT SELECT, INSERT ON app.requirement TO sdlc_app;
GRANT UPDATE (current_revision_no, version, updated_at) ON app.requirement TO sdlc_app;

GRANT SELECT, INSERT ON app.requirement_revision TO sdlc_app;

GRANT SELECT, INSERT ON app.workflow_instance TO sdlc_app;
GRANT UPDATE (projected_state, last_seq, version, updated_at) ON app.workflow_instance TO sdlc_app;

GRANT SELECT, INSERT ON app.workflow_transition TO sdlc_app;

GRANT SELECT, INSERT ON app.idempotency_record TO sdlc_app;
