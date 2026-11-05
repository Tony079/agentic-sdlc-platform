--liquibase formatted sql

--changeset sdlc:0016-model-registry
-- Models the gateway may call (TDD 13.2). Global reference data, written only by migrations: enabling a hosted
-- model is a reviewed change (O-01 is open, so only the deterministic fake model exists).
CREATE TABLE app.model_registry (
  provider              text          NOT NULL CHECK (provider ~ '^[a-z][a-z0-9-]{1,31}$'),
  model_id              text          NOT NULL CHECK (length(model_id) BETWEEN 1 AND 100),
  data_class            text          NOT NULL CHECK (data_class IN ('FAKE', 'HOSTED', 'LOCAL')),
  max_output_tokens     int           NOT NULL CHECK (max_output_tokens BETWEEN 1 AND 200000),
  input_price_per_1k    numeric(12,6) NOT NULL CHECK (input_price_per_1k >= 0),
  output_price_per_1k   numeric(12,6) NOT NULL CHECK (output_price_per_1k >= 0),
  status                text          NOT NULL CHECK (status IN ('ENABLED', 'DISABLED')),
  PRIMARY KEY (provider, model_id),
  -- The fake provider is never used for a hosted or local data class, and vice versa.
  CHECK ((provider = 'fake') = (data_class = 'FAKE'))
);
INSERT INTO app.model_registry VALUES ('fake', 'fake-deterministic-1', 'FAKE', 8192, 0, 0, 'ENABLED');

--changeset sdlc:0016-prompt-version
-- Versioned prompt templates (TDD 13.4, PRD FR-LLM-07). The template text lives in code; this table holds the
-- reviewed (id, version, SHA-256) the gateway accepts, so an edited prompt cannot run without a new reviewed version.
CREATE TABLE app.prompt_version (
  prompt_id      text        NOT NULL CHECK (prompt_id ~ '^[a-z][a-z0-9.-]{2,63}$'),
  version        int         NOT NULL CHECK (version >= 1),
  agent          text        NOT NULL CHECK (agent ~ '^[A-Z][A-Z0-9_]{1,31}$'),
  text_sha256    text        NOT NULL CHECK (text_sha256 ~ '^[0-9a-f]{64}$'),
  schema_version int         NOT NULL CHECK (schema_version >= 1),
  status         text        NOT NULL CHECK (status IN ('ACTIVE', 'RETIRED')),
  created_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (prompt_id, version)
);

--changeset sdlc:0016-model-call-record
-- One row per gateway call (PRD FR-LLM-04, TDD 13.6): identifiers, counts, cost and latency only. Never prompt
-- text, document content or model output.
CREATE TABLE app.model_call_record (
  id              uuid          NOT NULL DEFAULT gen_random_uuid(),
  tenant_id       uuid          NOT NULL REFERENCES app.tenant (id),
  request_id      uuid          NOT NULL,
  correlation_id  text          CHECK (length(correlation_id) <= 64),
  requirement_id  uuid,
  provider        text          NOT NULL,
  model_id        text          NOT NULL,
  prompt_id       text          NOT NULL,
  prompt_version  int           NOT NULL,
  outcome         text          NOT NULL CHECK (outcome IN ('SUCCEEDED', 'INVALID_OUTPUT', 'EMPTY_OUTPUT', 'TIMEOUT',
                                  'PROVIDER_UNAVAILABLE', 'RATE_LIMITED', 'PROVIDER_ERROR', 'BUDGET_EXCEEDED')),
  attempts        int           NOT NULL CHECK (attempts BETWEEN 0 AND 20),
  input_tokens    int           NOT NULL CHECK (input_tokens >= 0),
  output_tokens   int           NOT NULL CHECK (output_tokens >= 0),
  cost_usd        numeric(14,6) NOT NULL CHECK (cost_usd >= 0),
  latency_ms      bigint        NOT NULL CHECK (latency_ms >= 0),
  created_at      timestamptz   NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (request_id),
  FOREIGN KEY (provider, model_id) REFERENCES app.model_registry (provider, model_id),
  FOREIGN KEY (prompt_id, prompt_version) REFERENCES app.prompt_version (prompt_id, version),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id)
);
CREATE INDEX model_call_record_tenant_time_ix ON app.model_call_record (tenant_id, created_at);
CREATE INDEX model_call_record_requirement_ix ON app.model_call_record (tenant_id, requirement_id);

--changeset sdlc:0016-model-gateway-rls splitStatements:false
DO $rls$
BEGIN
  EXECUTE 'ALTER TABLE app.model_call_record ENABLE ROW LEVEL SECURITY';
  EXECUTE 'ALTER TABLE app.model_call_record FORCE ROW LEVEL SECURITY';
  EXECUTE 'CREATE POLICY tenant_isolation ON app.model_call_record '
          'USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant())';
END
$rls$;

--changeset sdlc:0016-model-gateway-grants
-- Model calls run in the worker (agent jobs). Append-only usage records; registry and prompts are read-only.
-- The API login has no access.
GRANT SELECT ON app.model_registry, app.prompt_version TO sdlc_worker;
GRANT SELECT, INSERT ON app.model_call_record TO sdlc_worker;
