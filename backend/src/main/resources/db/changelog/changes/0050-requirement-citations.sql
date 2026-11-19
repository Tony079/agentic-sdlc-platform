--liquibase formatted sql

-- GD-30 (2026-11-20): a BRD statement may cite the client's own requirement (a field of the analysed revision,
-- verified by the platform like a document quote); stricter BA prompt v7 and Architect design prompt v3.

--changeset sdlc:0050-requirement-citations
-- rls: schema change only; no rows are read or changed.
ALTER TABLE app.brd_item_evidence ADD COLUMN requirement_revision_no int CHECK (requirement_revision_no >= 1);
ALTER TABLE app.brd_item_evidence DROP CONSTRAINT brd_item_evidence_type_check;
ALTER TABLE app.brd_item_evidence ADD CONSTRAINT brd_item_evidence_type_check
  CHECK (type IN ('REPOSITORY', 'DOCUMENT', 'REQUIREMENT'));
ALTER TABLE app.brd_item_evidence ADD CONSTRAINT brd_item_evidence_requirement_check
  CHECK ((type = 'REQUIREMENT') = (requirement_revision_no IS NOT NULL AND chunk_label IS NOT NULL
         AND path IN ('title', 'description', 'businessObjective', 'expectedOutcomes', 'acceptanceCriteria',
                      'constraints', 'integrations')));

--changeset sdlc:0050-prompts-v7
-- rls: inserts prompt registry rows (not a tenant table).
INSERT INTO app.prompt_version (prompt_id, version, agent, text_sha256, schema_version, status) VALUES
  ('ba.phase-b', 7, 'BA', 'aaf257cfdb390b22d9359276b38bec6a94f9aae79e38d8708aec39fac5a0b1d9', 4, 'ACTIVE'),
  ('arch.design', 3, 'ARCHITECT', 'dbb7c78e97ba33dca1c60ca44c94a7f73edf4e967a5d4cc7457e8c22c3150405', 2, 'ACTIVE');
