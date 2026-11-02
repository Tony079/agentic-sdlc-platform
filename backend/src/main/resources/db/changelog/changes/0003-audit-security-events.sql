--liquibase formatted sql

--changeset sdlc:0003-audit-event
-- Tenant audit trail (PRD FR-AUD-01/05). Append-only for the application role (grants in 0005).
-- Content-free: identifiers, codes and references only.
CREATE TABLE app.audit_event (
  id             uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id      uuid        NOT NULL REFERENCES app.tenant (id),
  occurred_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  actor_type     text        NOT NULL CHECK (actor_type IN ('USER', 'SYSTEM')),
  actor_id       uuid,
  action         text        NOT NULL CHECK (action ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  resource_type  text        NOT NULL CHECK (resource_type ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  resource_id    uuid,
  result         text        NOT NULL CHECK (result IN ('SUCCESS', 'DENIED', 'FAILED')),
  correlation_id text        CHECK (length(correlation_id) <= 64),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id)
);
CREATE INDEX audit_event_tenant_time_ix ON app.audit_event (tenant_id, occurred_at);
CREATE INDEX audit_event_tenant_resource_ix ON app.audit_event (tenant_id, resource_type, resource_id);

--changeset sdlc:0003-platform-security-event
-- Security monitoring (PRD FR-MON-01), separate from the audit trail. Global: some events (failed logins)
-- happen before any tenant is known. Holds references and hashes only, never content or credentials.
CREATE TABLE app.platform_security_event (
  id             uuid        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  occurred_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  type           text        NOT NULL CHECK (type ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  severity       text        NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
  tenant_ref     uuid,
  actor_ref      text        CHECK (length(actor_ref) <= 128),
  target_ref     text        CHECK (length(target_ref) <= 128),
  correlation_id text        CHECK (length(correlation_id) <= 64)
);
CREATE INDEX platform_security_event_type_time_ix ON app.platform_security_event (type, occurred_at);
