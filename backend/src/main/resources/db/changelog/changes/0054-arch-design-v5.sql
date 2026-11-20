--liquibase formatted sql

-- Architect design prompt v5 (2026-11-20): a redesign revises the previous version instead of starting again.

--changeset sdlc:0054-arch-design-v5
-- rls: inserts a prompt registry row (not a tenant table).
INSERT INTO app.prompt_version (prompt_id, version, agent, text_sha256, schema_version, status) VALUES
  ('arch.design', 5, 'ARCHITECT', 'f2baf001946cc1a8a88b455f9fe4bc136f851f1f5d9f8bb0e33596882fd1051f', 2, 'ACTIVE');
