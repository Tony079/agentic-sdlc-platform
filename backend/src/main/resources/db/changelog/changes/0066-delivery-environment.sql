--liquibase formatted sql

-- GD-36 follow-up (2026-11-22): a requirement may name the environment its code is delivered into. For a new product
-- this is the only environment it has (the analysed environment, target_environment_id, is for existing products and
-- drives source analysis; this one never does). The Developer agent works on that environment's branch of the
-- repository it pushes to.

--changeset sdlc:0066-delivery-environment
-- rls: adds a column to app.requirement, whose row-level security policies already cover it.
ALTER TABLE app.requirement ADD COLUMN delivery_environment_id uuid;
ALTER TABLE app.requirement ADD CONSTRAINT requirement_delivery_environment_fk
  FOREIGN KEY (tenant_id, delivery_environment_id) REFERENCES app.environment (tenant_id, id);
GRANT UPDATE (delivery_environment_id) ON app.requirement TO sdlc_app;
GRANT SELECT (delivery_environment_id) ON app.requirement TO sdlc_worker;
