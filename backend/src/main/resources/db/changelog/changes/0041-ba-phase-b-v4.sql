--liquibase formatted sql

-- Phase B v4: v3 with explicit "kind" and "section" values (a hosted model put section names in "kind").

--changeset sdlc:0041-prompt-v4
INSERT INTO app.prompt_version (prompt_id, version, agent, text_sha256, schema_version, status) VALUES
  ('ba.phase-b', 4, 'BA', 'ef26e1ece2235058025f46a0f95b6303abc371be53a4fb2894a4c5ca31419ff9', 3, 'ACTIVE');
