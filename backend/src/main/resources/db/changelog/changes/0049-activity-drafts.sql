--liquibase formatted sql

-- GD-29 step B (2026-11-19): while the model writes, each statement or design item it has finished is shown as a
-- DRAFT line (the model's own text, not yet checked). Only people who may see the unapproved BRD or design see them.

--changeset sdlc:0049-activity-drafts
-- rls: constraint change only; no rows are updated.
ALTER TABLE app.analysis_activity DROP CONSTRAINT analysis_activity_kind_check;
ALTER TABLE app.analysis_activity ADD CONSTRAINT analysis_activity_kind_check
  CHECK (kind IN ('STEP', 'DONE', 'WARN', 'FAIL', 'DRAFT'));
