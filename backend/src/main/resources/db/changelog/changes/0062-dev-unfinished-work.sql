--liquibase formatted sql

--changeset sdlc:0062-dev-unfinished-work
-- rls: adds columns to app.dev_task, whose row-level security policies (0060) already cover them.
-- GD-36: a story that runs out of work sessions keeps its work on its garuda/ branch as a draft pull request, with
-- the agent's work log; a retry carries on from the branch head and the log instead of starting again.
ALTER TABLE app.dev_task
  ADD COLUMN work_log text CHECK (length(work_log) <= 20000),
  ADD COLUMN draft    boolean NOT NULL DEFAULT false;
GRANT UPDATE (work_log, draft) ON app.dev_task TO sdlc_worker;
