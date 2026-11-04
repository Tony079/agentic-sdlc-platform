--liquibase formatted sql

--changeset sdlc:0013-m3-rls splitStatements:false
-- Forced RLS with exactly one tenant policy on every Milestone 3 tenant-owned table (PRD FR-AUTH-17). The worker
-- reaches a tenant's rows only inside that tenant's transaction context (decision Q-M3-2): no role bypasses RLS
-- and no privileged cross-tenant function exists.
DO $rls$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['outbox_event', 'processed_event', 'job', 'job_attempt', 'stage_execution'] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON app.%I '
                   'USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant())', t);
  END LOOP;
END
$rls$;

--changeset sdlc:0013-m3-grants-app
-- API login sdlc_app (decision Q-M3-3): may only *produce* events, with the five producer columns. It cannot read
-- the outbox, mark an event published or dead, write processed_event, or touch the job queue.
GRANT INSERT (tenant_id, aggregate_type, aggregate_id, event_type, payload) ON app.outbox_event TO sdlc_app;
GRANT SELECT ON app.workflow_definition TO sdlc_app;
GRANT SELECT ON app.stage_execution TO sdlc_app;

--changeset sdlc:0013-m3-grants-worker
-- Worker login sdlc_worker (decision Q-M3-3): outbox dispatch, idempotent consumers, job queue and the engine
-- transitions it performs (T02/T03, cancellation follow-up). Column-limited updates, no DELETE anywhere, no
-- access to requirement content, sessions or users.
GRANT EXECUTE ON FUNCTION app.current_tenant() TO sdlc_worker;
GRANT SELECT (id, status) ON app.tenant TO sdlc_worker;
GRANT INSERT ON app.audit_event TO sdlc_worker;

GRANT SELECT ON app.workflow_definition TO sdlc_worker;
GRANT SELECT ON app.workflow_instance TO sdlc_worker;
GRANT UPDATE (projected_state, last_seq, version, updated_at) ON app.workflow_instance TO sdlc_worker;
GRANT SELECT, INSERT ON app.workflow_transition TO sdlc_worker;
GRANT SELECT, INSERT ON app.stage_execution TO sdlc_worker;
GRANT UPDATE (status, updated_at) ON app.stage_execution TO sdlc_worker;

GRANT SELECT ON app.outbox_event TO sdlc_worker;
GRANT INSERT (tenant_id, aggregate_type, aggregate_id, event_type, payload) ON app.outbox_event TO sdlc_worker;
GRANT UPDATE (attempts, next_attempt_at, last_error_code, published_at, dead_at) ON app.outbox_event TO sdlc_worker;
GRANT SELECT, INSERT ON app.processed_event TO sdlc_worker;

GRANT SELECT ON app.job_type TO sdlc_worker;
GRANT SELECT ON app.job TO sdlc_worker;
GRANT INSERT (tenant_id, job_type, idempotency_key, aggregate_type, aggregate_id, stage_execution_id, payload,
              priority, available_at) ON app.job TO sdlc_worker;
GRANT UPDATE (status, available_at, lease_owner, lease_epoch, leased_at, lease_expires_at, claim_count,
              attempt_count, retryable_failure_count, infra_failure_count, deferral_count, last_error_code,
              dead_reason, finished_at) ON app.job TO sdlc_worker;
GRANT SELECT, INSERT ON app.job_attempt TO sdlc_worker;
GRANT UPDATE (finished_at, outcome, error_code) ON app.job_attempt TO sdlc_worker;
