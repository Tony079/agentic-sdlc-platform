-- 00-roles.sql — cluster-level roles (run once per cluster by the database superuser).
-- Idempotent. Passwords are NOT set here; init.sh sets them from environment variables.
-- Role model follows TDD v1.0.4 section 8.1 (subset: owner, migrator, app) plus the background worker login
-- sdlc_worker (Milestone 3 decision Q-M3-3).
DO $roles$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sdlc_owner') THEN
    -- Owns every schema object. Cannot log in.
    CREATE ROLE sdlc_owner NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sdlc_migrator') THEN
    -- Liquibase login. Acts as sdlc_owner only through SET ROLE (startup option role=sdlc_owner).
    CREATE ROLE sdlc_migrator LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sdlc_app') THEN
    -- Runtime login for the API. Never an owner, never bypasses RLS, no DDL, no temp objects.
    CREATE ROLE sdlc_app LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sdlc_worker') THEN
    -- Runtime login for the background worker (outbox dispatcher, job queue). Same restrictions as sdlc_app;
    -- its own narrow grants (Liquibase 0013) are the only rights that differ.
    CREATE ROLE sdlc_worker LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sdlc_provisioner') THEN
    -- Privileged administrative path for staff identities, capability grants and tenant assignments (S-02).
    -- Used only by the one-shot "provision" process; never by the API or worker. No RLS bypass, not an owner.
    CREATE ROLE sdlc_provisioner LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sdlc_retention') THEN
    -- Controlled retention and deletion process (PRD FR-RET-14, S-01): the only login that may delete derived data
    -- and expired audit rows. Used only by the one-shot "retention" process. No RLS bypass, not an owner.
    CREATE ROLE sdlc_retention LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
END
$roles$;

GRANT sdlc_owner TO sdlc_migrator WITH SET TRUE, INHERIT FALSE, ADMIN FALSE;
