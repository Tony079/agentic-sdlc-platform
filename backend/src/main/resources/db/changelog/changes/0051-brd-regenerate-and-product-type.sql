--liquibase formatted sql

-- GD-30/31 (2026-11-20): the requirement's product type can be cited; BA prompt v8 (one point per item, enforced
-- by the engine for numbered lists; product type citation; quote length).

--changeset sdlc:0051-product-type-citation
-- rls: schema change only; no rows are read or changed.
ALTER TABLE app.brd_item_evidence DROP CONSTRAINT brd_item_evidence_requirement_check;
ALTER TABLE app.brd_item_evidence ADD CONSTRAINT brd_item_evidence_requirement_check
  CHECK ((type = 'REQUIREMENT') = (requirement_revision_no IS NOT NULL AND chunk_label IS NOT NULL
         AND path IN ('title', 'description', 'businessObjective', 'expectedOutcomes', 'acceptanceCriteria',
                      'constraints', 'integrations', 'productType')));

--changeset sdlc:0051-ba-phase-b-v8
-- rls: inserts a prompt registry row (not a tenant table).
INSERT INTO app.prompt_version (prompt_id, version, agent, text_sha256, schema_version, status) VALUES
  ('ba.phase-b', 8, 'BA', '55e4a05951e71c4eda46dcc225fb0821cd96147eed415d03d06eb261c997eb75', 4, 'ACTIVE');
