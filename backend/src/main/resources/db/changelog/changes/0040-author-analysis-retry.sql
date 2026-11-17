--liquibase formatted sql

-- Owner decision (2026-11-17, with GD-24): the author of a requirement whose analysis failed may retry it from the
-- requirement page, at most three times, with no new charge. The request reuses the operator retry path (O-27): the
-- worker re-validates it (requirement still FAILED, job still dead, failure not permanent) and performs T26.

--changeset sdlc:0040-author-retry splitStatements:false
ALTER TABLE app.admin_retry_request ALTER COLUMN requested_by DROP NOT NULL;
ALTER TABLE app.admin_retry_request ADD COLUMN requested_by_user uuid;
ALTER TABLE app.admin_retry_request ADD CONSTRAINT admin_retry_request_user_fk
  FOREIGN KEY (tenant_id, requested_by_user) REFERENCES app.app_user (tenant_id, id);
ALTER TABLE app.admin_retry_request ADD CONSTRAINT admin_retry_request_requester_check
  CHECK ((requested_by IS NULL) <> (requested_by_user IS NULL));

CREATE FUNCTION app.author_retry_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF NEW.requested_by_user IS NULL THEN
    RETURN NEW;
  END IF;
  IF app.current_member() IS DISTINCT FROM NEW.requested_by_user
     OR NOT EXISTS (SELECT 1 FROM app.requirement r WHERE r.id = NEW.requirement_id AND r.created_by = NEW.requested_by_user) THEN
    RAISE EXCEPTION 'only the requirement''s author, signed in, retries its analysis' USING ERRCODE = 'P0001';
  END IF;
  IF (SELECT count(*) FROM app.admin_retry_request a
      WHERE a.requirement_id = NEW.requirement_id AND a.requested_by_user IS NOT NULL) >= 3 THEN
    RAISE EXCEPTION 'the author''s three analysis retries are used' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.author_retry_guard() FROM PUBLIC;
CREATE TRIGGER author_retry_guard BEFORE INSERT ON app.admin_retry_request
  FOR EACH ROW EXECUTE FUNCTION app.author_retry_guard();
