--liquibase formatted sql

-- Phase B v5: v4 plus the rule that answered clarifications are settled and never asked again. The answers are now
-- given to the model in their own data block instead of among the reviewer correction notes.

--changeset sdlc:0042-prompt-v5
INSERT INTO app.prompt_version (prompt_id, version, agent, text_sha256, schema_version, status) VALUES
  ('ba.phase-b', 5, 'BA', 'c755ff3e8ac64bb3c755e759cd5421ef7eb59061aa1990d67ac007d336254610', 3, 'ACTIVE');
