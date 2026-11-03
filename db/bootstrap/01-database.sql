-- 01-database.sql — per-database preparation (run by the superuser while connected to the target database).
-- Idempotent. Creates the schemas owned by sdlc_owner; Liquibase (as sdlc_migrator -> sdlc_owner) creates the objects.
DO $db$
BEGIN
  EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database());
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO sdlc_migrator, sdlc_app', current_database());
END
$db$;

REVOKE ALL ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA IF NOT EXISTS app AUTHORIZATION sdlc_owner;
CREATE SCHEMA IF NOT EXISTS migration_meta AUTHORIZATION sdlc_owner;
REVOKE ALL ON SCHEMA app, migration_meta FROM PUBLIC;
GRANT USAGE ON SCHEMA app TO sdlc_app;

-- New functions owned by sdlc_owner are not executable by PUBLIC unless granted explicitly.
ALTER DEFAULT PRIVILEGES FOR ROLE sdlc_owner REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
