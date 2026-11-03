--liquibase formatted sql

--changeset sdlc:0008-idempotency-record
-- Idempotency keys for creating operations (TDD 20.1). The key is claimed in the creating transaction with
-- INSERT ... ON CONFLICT DO NOTHING; a concurrent claimer of the same key waits for the first transaction and
-- then reads the committed record in a separate statement. Scoped per tenant, user and operation.
-- Known limit (decision register): records are kept; the 24-hour replay window and cleanup come with retention.
CREATE TABLE app.idempotency_record (
  tenant_id    uuid        NOT NULL REFERENCES app.tenant (id),
  user_id      uuid        NOT NULL,
  operation    text        NOT NULL CHECK (operation ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  idem_key     uuid        NOT NULL,
  request_hash text        NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
  resource_id  uuid        NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, user_id, operation, idem_key),
  FOREIGN KEY (tenant_id, user_id) REFERENCES app.app_user (tenant_id, id)
);
