--liquibase formatted sql

--changeset sdlc:0006-requirement-key-counter
-- Per-tenant counter for the human-readable requirement key (decision C-03). Incremented with
-- INSERT ... ON CONFLICT DO UPDATE in the creating transaction: the row lock serialises concurrent creates
-- in one tenant, and a rollback also rolls back the increment, so committed keys have no gaps.
CREATE TABLE app.requirement_key_counter (
  tenant_id  uuid   NOT NULL PRIMARY KEY REFERENCES app.tenant (id),
  last_value bigint NOT NULL CHECK (last_value >= 1)
);

--changeset sdlc:0006-requirement
-- A business change request (PRD 4A). Content lives in immutable revisions; this row holds identity, the
-- pointer to the current revision and the optimistic-locking version shown to clients.
CREATE TABLE app.requirement (
  id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id           uuid        NOT NULL REFERENCES app.tenant (id),
  project_id          uuid        NOT NULL,
  key                 text        NOT NULL CHECK (key ~ '^REQ-[0-9]{6,}$'),
  created_by          uuid        NOT NULL,
  created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
  current_revision_no int         NOT NULL CHECK (current_revision_no >= 1),
  version             bigint      NOT NULL DEFAULT 0 CHECK (version >= 0),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, key),
  FOREIGN KEY (tenant_id, project_id) REFERENCES app.project (tenant_id, id),
  FOREIGN KEY (tenant_id, created_by) REFERENCES app.app_user (tenant_id, id)
);
CREATE INDEX requirement_tenant_creator_ix ON app.requirement (tenant_id, created_by, updated_at DESC);

--changeset sdlc:0006-requirement-revision
-- Immutable revisions (PRD FR-REQ-04): every edit inserts a new row; the application role has no UPDATE or
-- DELETE (grants in 0009). Field lengths follow the approved Milestone 2 decision Q1. Completeness is
-- validated at submission by the application; drafts may be partial (decision Q2).
CREATE TABLE app.requirement_revision (
  id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id           uuid        NOT NULL,
  requirement_id      uuid        NOT NULL,
  revision_no         int         NOT NULL CHECK (revision_no >= 1),
  kind                text        NOT NULL CHECK (kind IN ('EXISTING_REPOSITORY', 'NEW_PRODUCT')),
  title               text        NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
  description         text        CHECK (length(description) <= 20000),
  business_objective  text        CHECK (length(business_objective) <= 5000),
  expected_outcomes   text        CHECK (length(expected_outcomes) <= 10000),
  acceptance_criteria text        CHECK (length(acceptance_criteria) <= 10000),
  -- Unvalidated text in Milestone 2 (syntax only). Existence/ownership/access is the repository milestone.
  repository_url      text        CHECK (length(repository_url) <= 300
                                         AND repository_url ~ '^https://github\.com/[A-Za-z0-9-]+/[A-Za-z0-9._-]+$'),
  constraints         text        CHECK (length(constraints) <= 10000),
  integrations        text        CHECK (length(integrations) <= 10000),
  content_hash        text        NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
  created_by          uuid        NOT NULL,
  created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
  -- A new-product requirement never carries a repository URL.
  CHECK (kind = 'EXISTING_REPOSITORY' OR repository_url IS NULL),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (requirement_id, revision_no),
  UNIQUE (tenant_id, requirement_id, revision_no),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, created_by) REFERENCES app.app_user (tenant_id, id)
);

--changeset sdlc:0006-requirement-current-revision-fk
-- The current-revision pointer must always name an existing revision of the same requirement and tenant.
-- Deferred, because a requirement and its first revision are inserted in one transaction.
ALTER TABLE app.requirement ADD CONSTRAINT requirement_current_revision_fk
  FOREIGN KEY (tenant_id, id, current_revision_no)
  REFERENCES app.requirement_revision (tenant_id, requirement_id, revision_no)
  DEFERRABLE INITIALLY DEFERRED;
