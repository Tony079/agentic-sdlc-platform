--liquibase formatted sql

--changeset sdlc:0001-current-tenant-helper
-- Tenant context of the current transaction. Set by the application with set_config(..., true)
-- (transaction-local). An empty or missing value yields NULL, so RLS policies match no rows (fail closed).
CREATE FUNCTION app.current_tenant() RETURNS uuid
  LANGUAGE sql STABLE PARALLEL SAFE
  RETURN nullif(current_setting('app.tenant_id', true), '')::uuid;
REVOKE ALL ON FUNCTION app.current_tenant() FROM PUBLIC;

--changeset sdlc:0001-tenant
-- Global tenant registry (no tenant_id). Holds identity only, no tenant business content.
CREATE TABLE app.tenant (
  id         uuid        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  slug       text        NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
  name       text        NOT NULL UNIQUE CHECK (length(name) BETWEEN 1 AND 200),
  status     text        NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSING', 'CLOSED')),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

--changeset sdlc:0001-app-user
-- Tenant users (PRD section 4 tenant roles only; staff/platform users are gated by O-06).
CREATE TABLE app.app_user (
  id            uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id     uuid        NOT NULL REFERENCES app.tenant (id),
  email         text        NOT NULL CHECK (length(email) BETWEEN 3 AND 320),
  display_name  text        NOT NULL CHECK (length(display_name) BETWEEN 1 AND 200),
  password_hash text        NOT NULL,
  role          text        NOT NULL CHECK (role IN ('CLIENT_USER', 'CLIENT_APPROVER')),
  status        text        NOT NULL CHECK (status IN ('INVITED', 'ACTIVE', 'DISABLED')),
  created_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX app_user_tenant_email_uq ON app.app_user (tenant_id, lower(email));
