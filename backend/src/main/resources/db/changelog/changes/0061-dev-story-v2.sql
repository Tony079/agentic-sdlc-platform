--liquibase formatted sql

-- GD-36 follow-up (2026-11-21): Developer agent prompt v2: at most 3 rounds of exploration before writing, visible
-- progress every session, answer by round 8.

--changeset sdlc:0061-dev-story-v2
-- rls: inserts a prompt registry row (not a tenant table).
INSERT INTO app.prompt_version (prompt_id, version, agent, text_sha256, schema_version, status) VALUES
  ('dev.story', 2, 'DEVELOPER', '7866154bb7a44e3320fac744a250390d3835d5594a70088b309c55b7983a2d9b', 1, 'ACTIVE');
