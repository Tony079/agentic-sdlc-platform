--liquibase formatted sql

-- GD-36 follow-up (2026-11-21): Developer agent prompt v3: no Docker in the workspace; container and deployment work is
-- written and delivered with what a person must still run, instead of being given up.

--changeset sdlc:0064-dev-story-v3
-- rls: inserts a prompt registry row (not a tenant table).
INSERT INTO app.prompt_version (prompt_id, version, agent, text_sha256, schema_version, status) VALUES
  ('dev.story', 3, 'DEVELOPER', 'b39a75c9faa52358f97072afc4f8f0fe1f4f03947722a848ad69243ba803f9b4', 1, 'ACTIVE');
