--liquibase formatted sql

--changeset sdlc:0030-development-mock-model splitStatements:false
-- Working prototype (2026-11-12): registers the development mock provider next to the deterministic fake one. Both
-- are data class FAKE: no network, no hosted model. The mock is selected only by server configuration
-- (sdlc.model.provider=development-mock) and is refused in production mode. Hosted models are still not registered
-- (owner decision O-01 selects none yet).
DO $do$
DECLARE
  constraint_name text;
BEGIN
  SELECT c.conname INTO constraint_name
  FROM pg_constraint c
  WHERE c.conrelid = 'app.model_registry'::regclass AND c.contype = 'c'
    AND pg_get_constraintdef(c.oid) LIKE '%provider = ''fake''%';
  IF constraint_name IS NULL THEN
    RAISE EXCEPTION 'model_registry provider/data-class check not found';
  END IF;
  EXECUTE format('ALTER TABLE app.model_registry DROP CONSTRAINT %I', constraint_name);
END
$do$;
ALTER TABLE app.model_registry ADD CONSTRAINT model_registry_fake_class_check
  CHECK ((provider IN ('fake', 'development-mock')) = (data_class = 'FAKE'));
INSERT INTO app.model_registry VALUES ('development-mock', 'development-mock-1', 'FAKE', 8192, 0, 0, 'ENABLED');
