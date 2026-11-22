--liquibase formatted sql

-- GD-36 follow-up (2026-11-22): Developer agent prompt v4: the edit_file tool for changes to existing files.

--changeset sdlc:0069-dev-story-v4
-- rls: inserts a prompt registry row (not a tenant table).
INSERT INTO app.prompt_version (prompt_id, version, agent, text_sha256, schema_version, status) VALUES
  ('dev.story', 4, 'DEVELOPER', 'eeed260c58b41833e92e4f69f98e88928e546cae77ab3929f520dbff8ca4144b', 1, 'ACTIVE');
