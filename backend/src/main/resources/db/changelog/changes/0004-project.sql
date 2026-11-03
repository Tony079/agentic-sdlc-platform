--liquibase formatted sql

--changeset sdlc:0004-project
-- Tenant-owned project (PRD section 4A). Composite foreign key prevents cross-tenant creator references.
CREATE TABLE app.project (
  id         uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id  uuid        NOT NULL REFERENCES app.tenant (id),
  name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
  created_by uuid        NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, name),
  FOREIGN KEY (tenant_id, created_by) REFERENCES app.app_user (tenant_id, id)
);
