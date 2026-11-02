-- 00-roles.sql — cluster-level roles (run once per cluster by the database superuser).
-- Idempotent. Passwords are NOT set here; init.sh sets them from environment variables.
-- Role model follows TDD v1.0.4 section 8.1 (Milestone 1 subset: owner, migrator, app).
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
END
$roles$;

GRANT sdlc_owner TO sdlc_migrator WITH SET TRUE, INHERIT FALSE, ADMIN FALSE;
