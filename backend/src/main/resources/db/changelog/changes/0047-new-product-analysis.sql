--liquibase formatted sql

-- GD-28 (owner, 2026-11-19): a New product requirement is analysed by the agents like an enhancement, with no code:
-- the BRD comes from the requirement and its documents, then the Architect agent designs it from scratch. Its
-- analysis input set therefore has no repository; manual intake remains only where it is configured.

--changeset sdlc:0047-input-set-without-code
-- rls: schema change only; no rows are updated.
ALTER TABLE app.analysis_input_set ALTER COLUMN repository_id DROP NOT NULL;
ALTER TABLE app.analysis_input_set ALTER COLUMN commit_sha DROP NOT NULL;
ALTER TABLE app.analysis_input_set ALTER COLUMN snapshot_id DROP NOT NULL;
ALTER TABLE app.analysis_input_set ADD CONSTRAINT analysis_input_set_code_check
  CHECK ((repository_id IS NULL) = (commit_sha IS NULL) AND (repository_id IS NULL) = (snapshot_id IS NULL)
         AND (repository_id IS NOT NULL OR sources = '[]'::jsonb));
ALTER TABLE app.clarification ALTER COLUMN commit_sha DROP NOT NULL;

--changeset sdlc:0047-prompt-ba-phase-b-v6
-- rls: inserts a prompt registry row (not a tenant table).
INSERT INTO app.prompt_version (prompt_id, version, agent, text_sha256, schema_version, status) VALUES
  ('ba.phase-b', 6, 'BA', 'c331b7767384288129630be1944907a25608cf5ba3e73358dd76a4bed19bc048', 3, 'ACTIVE');
