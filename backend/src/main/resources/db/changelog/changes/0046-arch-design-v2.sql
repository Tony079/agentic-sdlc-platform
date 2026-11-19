--liquibase formatted sql

-- Architect agent design prompt v2 (2026-11-19): BRD coverage checklist, concrete decisions (stack, schema, API
-- contracts, rollout), new-product rule for repositories with little code, and citations only where the item depends
-- on the cited code.

--changeset sdlc:0046-arch-design-v2
-- rls: inserts a prompt registry row (not a tenant table).
INSERT INTO app.prompt_version (prompt_id, version, agent, text_sha256, schema_version, status) VALUES
  ('arch.design', 2, 'ARCHITECT', '4f1f78f93e1f3f547af46cee80318f54bc0a0a8ccf09ff33819a8ed3102ca5f9', 1, 'ACTIVE');
