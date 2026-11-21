--liquibase formatted sql

--changeset sdlc:0063-dev-base-branch
-- rls: grants a column of app.requirement (row-level security unchanged).
-- GD-36: the Developer agent works from, and opens pull requests into, the branch the requirement's environment names
-- for the repository, so the worker reads which environment the requirement targets.
GRANT SELECT (target_environment_id) ON app.requirement TO sdlc_worker;
