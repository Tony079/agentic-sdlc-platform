--liquibase formatted sql

-- Garuda v2 (owner decision GD-22, 2026-11-15, "B with C"): a project with a billing plan is charged per BRD by size.
-- The quote opens when the requirement is submitted and is refreshed from the analysis inputs until accepted.
-- Before analysis starts, Garuda quotes the BRD (Small, Medium or Large) from its inputs; the Requirement Author who owns
-- the requirement, or a BRD Approver of the project, accepts the quote. The plan's monthly allowance of included BRDs
-- applies per project and calendar month (UTC); beyond it each BRD is charged at its size price (overage). Sizing is
-- rule-based and labelled ESTIMATED until the BRD cost analysis agent is approved (O-01).

--changeset sdlc:0036-brd-quote splitStatements:false
CREATE TABLE app.brd_quote (
  id              uuid          NOT NULL DEFAULT gen_random_uuid(),
  tenant_id       uuid          NOT NULL REFERENCES app.tenant (id),
  project_id      uuid          NOT NULL,
  requirement_id  uuid          NOT NULL,
  plan_id         uuid          NOT NULL REFERENCES app.billing_plan (id),
  currency        text          NOT NULL CHECK (currency IN ('INR', 'USD', 'EUR', 'GBP')),
  size            text          NOT NULL CHECK (size IN ('SMALL', 'MEDIUM', 'LARGE')),
  sizing_method   text          NOT NULL CHECK (sizing_method IN ('RULES_V1')),
  documents       int           NOT NULL CHECK (documents >= 0),
  has_repository  boolean       NOT NULL,
  text_chars      int           NOT NULL CHECK (text_chars >= 0),
  list_price      numeric(12,2) NOT NULL CHECK (list_price >= 0),
  status          text          NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED', 'ACCEPTED', 'DECLINED')),
  quoted_at       timestamptz   NOT NULL DEFAULT clock_timestamp(),
  -- Fixed at acceptance: whether the BRD falls within the month's allowance, and what it costs the organisation.
  included        boolean,
  charge          numeric(12,2) CHECK (charge >= 0),
  billing_month   date          CHECK (billing_month = date_trunc('month', billing_month)::date),
  accepted_by     uuid          REFERENCES app.app_user (id),
  accepted_at     timestamptz,
  declined_by     uuid          REFERENCES app.app_user (id),
  declined_at     timestamptz,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (requirement_id),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  CHECK ((status = 'ACCEPTED') = (accepted_by IS NOT NULL AND accepted_at IS NOT NULL AND included IS NOT NULL
                                  AND charge IS NOT NULL AND billing_month IS NOT NULL)),
  CHECK ((status = 'DECLINED') = (declined_by IS NOT NULL AND declined_at IS NOT NULL)),
  CHECK (included IS NOT TRUE OR charge = 0)
);
CREATE INDEX brd_quote_month_idx ON app.brd_quote (tenant_id, project_id, billing_month) WHERE status = 'ACCEPTED';

CREATE FUNCTION app.brd_quote_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  member uuid := app.current_member();
  owner uuid;
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'PROPOSED' OR NEW.accepted_by IS NOT NULL THEN
      RAISE EXCEPTION 'a BRD quote starts as proposed' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.status = 'ACCEPTED' THEN
    RAISE EXCEPTION 'an accepted BRD quote is final' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.project_id <> OLD.project_id
     OR NEW.requirement_id <> OLD.requirement_id THEN
    RAISE EXCEPTION 'BRD quote identity is immutable' USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status IN ('ACCEPTED', 'DECLINED') AND NEW.status <> OLD.status THEN
    SELECT created_by INTO owner FROM app.requirement WHERE id = NEW.requirement_id;
    IF member IS NULL OR member IS DISTINCT FROM coalesce(NEW.accepted_by, NEW.declined_by)
       OR NOT (member = owner OR app.is_brd_approver(NEW.project_id)) THEN
      RAISE EXCEPTION 'a BRD quote is decided by the requirement owner or a BRD Approver' USING ERRCODE = 'P0001';
    END IF;
    -- Price terms cannot change in the same statement that accepts them.
    IF NEW.size <> OLD.size OR NEW.list_price <> OLD.list_price OR NEW.plan_id <> OLD.plan_id THEN
      RAISE EXCEPTION 'a BRD quote is accepted as quoted' USING ERRCODE = 'P0001';
    END IF;
  ELSIF NEW.status = 'PROPOSED' AND OLD.status = 'DECLINED' THEN
    RAISE EXCEPTION 'a declined quote is reconsidered by accepting it' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.brd_quote_guard() FROM PUBLIC;
CREATE TRIGGER brd_quote_guard BEFORE INSERT OR UPDATE ON app.brd_quote FOR EACH ROW EXECUTE FUNCTION app.brd_quote_guard();

ALTER TABLE app.brd_quote ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.brd_quote FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.brd_quote
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());
CREATE POLICY project_access ON app.brd_quote AS RESTRICTIVE
  USING (app.current_member() IS NULL OR app.member_sees_project(project_id));

--changeset sdlc:0036-ai-cost-daily splitStatements:false
-- GD-22: AI cost per organisation, project and requirement. The API login never reads raw model-call records; it reads
-- this daily rollup, kept by a trigger in the worker's insert transaction (so it cannot drift from the records).
CREATE TABLE app.ai_cost_daily (
  tenant_id        uuid          NOT NULL REFERENCES app.tenant (id),
  day              date          NOT NULL,
  project_id       uuid,
  requirement_id   uuid,
  calls            bigint        NOT NULL CHECK (calls >= 0),
  input_tokens     bigint        NOT NULL CHECK (input_tokens >= 0),
  output_tokens    bigint        NOT NULL CHECK (output_tokens >= 0),
  -- Sum of known costs; calls without usage data add nothing (counted in unmetered_calls).
  cost_usd         numeric(16,6) NOT NULL CHECK (cost_usd >= 0),
  estimated_calls  bigint        NOT NULL CHECK (estimated_calls >= 0),
  unmetered_calls  bigint        NOT NULL CHECK (unmetered_calls >= 0)
);
CREATE UNIQUE INDEX ai_cost_daily_key ON app.ai_cost_daily
  (tenant_id, day, (coalesce(requirement_id, '00000000-0000-0000-0000-000000000000'::uuid)));

CREATE FUNCTION app.ai_cost_rollup() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
DECLARE
  project uuid;
BEGIN
  IF NEW.requirement_id IS NOT NULL THEN
    SELECT r.project_id INTO project FROM app.requirement r WHERE r.id = NEW.requirement_id;
  END IF;
  INSERT INTO app.ai_cost_daily AS d (tenant_id, day, project_id, requirement_id, calls, input_tokens, output_tokens,
                                      cost_usd, estimated_calls, unmetered_calls)
  VALUES (NEW.tenant_id, (NEW.created_at AT TIME ZONE 'UTC')::date, project, NEW.requirement_id, 1,
          coalesce(NEW.input_tokens, 0), coalesce(NEW.output_tokens, 0), coalesce(NEW.cost_usd, 0),
          (NEW.usage_source = 'ESTIMATED')::int, (NEW.usage_source = 'UNAVAILABLE')::int)
  ON CONFLICT (tenant_id, day, (coalesce(requirement_id, '00000000-0000-0000-0000-000000000000'::uuid)))
  DO UPDATE SET calls = d.calls + 1, input_tokens = d.input_tokens + EXCLUDED.input_tokens,
                output_tokens = d.output_tokens + EXCLUDED.output_tokens, cost_usd = d.cost_usd + EXCLUDED.cost_usd,
                estimated_calls = d.estimated_calls + EXCLUDED.estimated_calls,
                unmetered_calls = d.unmetered_calls + EXCLUDED.unmetered_calls;
  RETURN NULL;
END
$fn$;
REVOKE ALL ON FUNCTION app.ai_cost_rollup() FROM PUBLIC;
CREATE TRIGGER ai_cost_rollup AFTER INSERT ON app.model_call_record FOR EACH ROW EXECUTE FUNCTION app.ai_cost_rollup();

INSERT INTO app.ai_cost_daily (tenant_id, day, project_id, requirement_id, calls, input_tokens, output_tokens, cost_usd,
                               estimated_calls, unmetered_calls)
SELECT c.tenant_id, (c.created_at AT TIME ZONE 'UTC')::date, max(r.project_id::text)::uuid, c.requirement_id, count(*),
       coalesce(sum(c.input_tokens), 0), coalesce(sum(c.output_tokens), 0), coalesce(sum(c.cost_usd), 0),
       count(*) FILTER (WHERE c.usage_source = 'ESTIMATED'), count(*) FILTER (WHERE c.usage_source = 'UNAVAILABLE')
FROM app.model_call_record c LEFT JOIN app.requirement r ON r.id = c.requirement_id
GROUP BY c.tenant_id, (c.created_at AT TIME ZONE 'UTC')::date, c.requirement_id;

ALTER TABLE app.ai_cost_daily ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.ai_cost_daily FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.ai_cost_daily
  USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant());
CREATE POLICY project_access ON app.ai_cost_daily AS RESTRICTIVE
  USING (app.current_member() IS NULL OR app.member_sees_project(project_id));

--changeset sdlc:0036-events splitStatements:false
ALTER TABLE app.outbox_event DROP CONSTRAINT outbox_event_event_type_check;
ALTER TABLE app.outbox_event ADD CONSTRAINT outbox_event_event_type_check CHECK (event_type IN (
  'RequirementSubmitted', 'RepoValidationStarted', 'RepoValidated', 'DocumentDecisionRequested',
  'DocumentDecisionRecorded', 'ManualIntakeEntered', 'ManualIntakeResumed', 'AnalysisStarted',
  'AnalysisResumed', 'ExecutionStale', 'ClarificationOpened', 'ClarificationResolved',
  'ClarificationEscalated', 'BrdVersionCreated', 'ReviewQueued', 'BrdApprovedInternal',
  'BrdReleasedToClient', 'RevisionRequested', 'RequirementRejected', 'ClientChangesRequested',
  'RequirementCancelled', 'WorkflowFailed', 'JobDead', 'AdminRetryRequested',
  'AdminRetryCompleted', 'AdminRetryRejected', 'DocumentUploaded', 'DocumentScanned',
  'DocumentProcessed', 'DocumentFailed', 'RetentionDue', 'TenantProvisioned', 'RepositoryRegistered',
  'RepositorySnapshotIndexed', 'ApprovalInvalidated', 'ClarificationAnswered', 'ReleaseApproved',
  'ChangeRequestSubmitted', 'ChangeRequestAccepted', 'EnvironmentBranchesChanged', 'BrdQuoteAccepted'));

--changeset sdlc:0036-grants
GRANT SELECT ON app.brd_quote TO sdlc_worker;
GRANT UPDATE (size, documents, has_repository, text_chars, list_price, quoted_at) ON app.brd_quote TO sdlc_worker;
GRANT SELECT ON app.billing_plan TO sdlc_worker;
GRANT SELECT, INSERT ON app.brd_quote TO sdlc_app;
GRANT UPDATE (status, included, charge, billing_month, accepted_by, accepted_at, declined_by, declined_at) ON app.brd_quote TO sdlc_app;
GRANT SELECT ON app.brd_quote TO sdlc_retention;
GRANT SELECT, INSERT ON app.ai_cost_daily TO sdlc_worker;
GRANT UPDATE (calls, input_tokens, output_tokens, cost_usd, estimated_calls, unmetered_calls) ON app.ai_cost_daily TO sdlc_worker;
GRANT SELECT ON app.ai_cost_daily TO sdlc_app;
