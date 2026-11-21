--liquibase formatted sql

-- GD-35 (owner, 2026-11-21): agent delivery mode. Coding agents build the work and people review it, so an estimate
-- in agent mode records, per work item, the agent's working time and the person's review effort (in low / likely /
-- high days, so PERT, adjustments and approvals work unchanged), and marks EXTERNAL items: work outside the code that
-- waits on a person or supplier, with its calendar lead time. The delivery plan carries the same per story and lays
-- the work out in dependency waves instead of sprints. Design: docs/architecture/garuda-agent-delivery.md.

--changeset sdlc:0059-agent-delivery-columns
-- rls: adds columns to existing tables; no tenant rows are read or changed.
ALTER TABLE app.estimate_item ADD COLUMN kind text NOT NULL DEFAULT 'WORK' CHECK (kind IN ('WORK', 'EXTERNAL'));
ALTER TABLE app.estimate_item ADD COLUMN agent_hours numeric(7,2) CHECK (agent_hours >= 0 AND agent_hours <= 500);
ALTER TABLE app.estimate_item ADD COLUMN lead_days numeric(6,2) CHECK (lead_days >= 0 AND lead_days <= 365);
ALTER TABLE app.plan_story ADD COLUMN agent_hours numeric(8,2) NOT NULL DEFAULT 0 CHECK (agent_hours >= 0);
ALTER TABLE app.plan_story ADD COLUMN external boolean NOT NULL DEFAULT false;
ALTER TABLE app.plan_story ADD COLUMN lead_days numeric(6,2) CHECK (lead_days >= 0 AND lead_days <= 365);

--changeset sdlc:0059-agent-delivery-prompt
-- rls: inserts a prompt registry row (not a tenant table).
INSERT INTO app.prompt_version (prompt_id, version, agent, text_sha256, schema_version, status) VALUES
  ('est.items', 2, 'ESTIMATOR', 'a9e8ba2a343bab570e36b344c838b50e08717545c6ae428209cf084d1f5e6cbb', 2, 'ACTIVE');
