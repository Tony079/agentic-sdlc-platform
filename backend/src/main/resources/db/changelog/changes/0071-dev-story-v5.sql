--liquibase formatted sql

-- GD-36 follow-up (2026-11-22): Developer agent prompt v5: implemented work whose build or tests cannot run in the
-- workspace for reasons outside the code is delivered (DONE, saying what to run) instead of given up.

--changeset sdlc:0071-dev-story-v5
-- rls: inserts a prompt registry row (not a tenant table).
INSERT INTO app.prompt_version (prompt_id, version, agent, text_sha256, schema_version, status) VALUES
  ('dev.story', 5, 'DEVELOPER', 'dddcf23ca671d56bac4cccdc72e01e8aa988315aa4a257bd28699da2b9b44372', 1, 'ACTIVE');
