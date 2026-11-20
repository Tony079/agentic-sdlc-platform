--liquibase formatted sql

-- Architect design prompt v4 (2026-11-20): up to 120 items (one per endpoint, table and test area), concise details.

--changeset sdlc:0053-arch-design-v4
-- rls: inserts a prompt registry row (not a tenant table).
INSERT INTO app.prompt_version (prompt_id, version, agent, text_sha256, schema_version, status) VALUES
  ('arch.design', 4, 'ARCHITECT', 'b0eddf4e88372d60930be2545eee35c2b7b6b498871e955ce0ffeed3411fd852', 2, 'ACTIVE');
