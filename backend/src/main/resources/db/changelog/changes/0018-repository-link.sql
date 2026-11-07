--liquibase formatted sql

--changeset sdlc:0018-repository-link-request
-- C-18 (Option 1, decision register section 9): a one-time request issued by the requirement owner that lets a Client
-- Approver attach a repository link without any visibility into the requirement. Only the SHA-256 of the 256-bit token
-- is stored; the token itself is returned once to the owner and never persisted or logged.
CREATE TABLE app.repository_link_request (
  id             uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id      uuid        NOT NULL REFERENCES app.tenant (id),
  requirement_id uuid        NOT NULL,
  created_by     uuid        NOT NULL,
  token_hash     text        NOT NULL CHECK (token_hash ~ '^[0-9a-f]{64}$'),
  operation      text        NOT NULL CHECK (operation = 'ATTACH_REPOSITORY_LINK'),
  intended_role  text        NOT NULL CHECK (intended_role = 'CLIENT_APPROVER'),
  status         text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'USED', 'REVOKED')),
  created_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at     timestamptz NOT NULL,
  used_at        timestamptz,
  used_by        uuid,
  revoked_at     timestamptz,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, created_by) REFERENCES app.app_user (tenant_id, id),
  FOREIGN KEY (tenant_id, used_by) REFERENCES app.app_user (tenant_id, id),
  CHECK (expires_at >= created_at + interval '1 hour' AND expires_at <= created_at + interval '168 hours'),
  CHECK ((status = 'USED') = (used_at IS NOT NULL AND used_by IS NOT NULL)),
  CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);
CREATE UNIQUE INDEX repository_link_request_token_uq ON app.repository_link_request (token_hash);
CREATE UNIQUE INDEX repository_link_request_pending_uq ON app.repository_link_request (tenant_id, requirement_id)
  WHERE status = 'PENDING';

--changeset sdlc:0018-requirement-repository-link
-- The Client Approver's recorded authorisation of one repository for one requirement (Q-A). Staff registration (C-01)
-- takes the repository identity only from an AUTHORIZED link, never from staff input.
CREATE TABLE app.requirement_repository_link (
  id             uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id      uuid        NOT NULL REFERENCES app.tenant (id),
  requirement_id uuid        NOT NULL,
  request_id     uuid        NOT NULL,
  repository_url text        NOT NULL CHECK (length(repository_url) <= 300
                                             AND repository_url ~ '^https://github\.com/[A-Za-z0-9-]+/[A-Za-z0-9._-]+$'),
  github_owner   text        NOT NULL CHECK (github_owner ~ '^[A-Za-z0-9-]{1,39}$'),
  github_name    text        NOT NULL CHECK (github_name ~ '^[A-Za-z0-9._-]{1,100}$'),
  authorized_by  uuid        NOT NULL,
  authorized_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  status         text        NOT NULL DEFAULT 'AUTHORIZED' CHECK (status IN ('AUTHORIZED', 'REGISTERED', 'SUPERSEDED')),
  status_at      timestamptz,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id),
  UNIQUE (request_id),
  FOREIGN KEY (tenant_id, requirement_id) REFERENCES app.requirement (tenant_id, id),
  FOREIGN KEY (tenant_id, request_id) REFERENCES app.repository_link_request (tenant_id, id),
  FOREIGN KEY (tenant_id, authorized_by) REFERENCES app.app_user (tenant_id, id),
  CHECK ((status = 'AUTHORIZED') = (status_at IS NULL))
);
CREATE UNIQUE INDEX requirement_repository_link_current_uq ON app.requirement_repository_link (tenant_id, requirement_id)
  WHERE status IN ('AUTHORIZED', 'REGISTERED');

--changeset sdlc:0018-repository-link-guards splitStatements:false
-- Invoker-rights guards (no SECURITY DEFINER, fixed search_path). Status moves forward only; redemption and
-- authorisation must be by an ACTIVE Client Approver of the same tenant (the database rechecks the role).
CREATE FUNCTION app.repository_link_request_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF OLD.status <> 'PENDING' THEN
    RAISE EXCEPTION 'link request % is no longer pending', OLD.id USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status = 'USED' THEN
    IF clock_timestamp() >= OLD.expires_at THEN
      RAISE EXCEPTION 'link request % has expired', OLD.id USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM app.app_user WHERE tenant_id = NEW.tenant_id AND id = NEW.used_by
                   AND role = 'CLIENT_APPROVER' AND status = 'ACTIVE') THEN
      RAISE EXCEPTION 'only an active Client Approver may redeem a link request' USING ERRCODE = 'P0001';
    END IF;
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.repository_link_request_guard() FROM PUBLIC;
CREATE TRIGGER repository_link_request_guard BEFORE UPDATE ON app.repository_link_request
  FOR EACH ROW EXECUTE FUNCTION app.repository_link_request_guard();

CREATE FUNCTION app.requirement_repository_link_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, app AS $fn$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NOT EXISTS (SELECT 1 FROM app.app_user WHERE tenant_id = NEW.tenant_id AND id = NEW.authorized_by
                   AND role = 'CLIENT_APPROVER' AND status = 'ACTIVE') THEN
      RAISE EXCEPTION 'only an active Client Approver may authorise a repository link' USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM app.repository_link_request r WHERE r.tenant_id = NEW.tenant_id
                   AND r.id = NEW.request_id AND r.requirement_id = NEW.requirement_id
                   AND r.status = 'USED' AND r.used_by = NEW.authorized_by) THEN
      RAISE EXCEPTION 'a repository link needs the redeemed request of the same requirement' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.status <> 'AUTHORIZED' AND NEW.status <> OLD.status THEN
    RAISE EXCEPTION 'repository link % is final', OLD.id USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END
$fn$;
REVOKE ALL ON FUNCTION app.requirement_repository_link_guard() FROM PUBLIC;
CREATE TRIGGER requirement_repository_link_guard BEFORE INSERT OR UPDATE ON app.requirement_repository_link
  FOR EACH ROW EXECUTE FUNCTION app.requirement_repository_link_guard();

--changeset sdlc:0018-repository-link-rls splitStatements:false
DO $rls$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['repository_link_request', 'requirement_repository_link'] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON app.%I '
                   'USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant())', t);
  END LOOP;
END
$rls$;

--changeset sdlc:0018-repository-link-grants
-- API only: issue, revoke, redeem. Column-limited; no DELETE. The worker has no access to link requests.
GRANT SELECT ON app.repository_link_request TO sdlc_app;
GRANT INSERT (tenant_id, requirement_id, created_by, token_hash, operation, intended_role, expires_at)
  ON app.repository_link_request TO sdlc_app;
GRANT UPDATE (status, used_at, used_by, revoked_at) ON app.repository_link_request TO sdlc_app;
GRANT SELECT ON app.requirement_repository_link TO sdlc_app;
GRANT INSERT (tenant_id, requirement_id, request_id, repository_url, github_owner, github_name, authorized_by)
  ON app.requirement_repository_link TO sdlc_app;
GRANT UPDATE (status, status_at) ON app.requirement_repository_link TO sdlc_app;
