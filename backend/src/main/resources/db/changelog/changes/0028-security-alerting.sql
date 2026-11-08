--liquibase formatted sql

--changeset sdlc:0028-security-alerting
-- C-17 (owner decision 2026-11-08): configurable security alerting. Security events stay in platform_security_event
-- (written independently of delivery). The worker evaluates new events against fixed rules, deduplicates them into
-- alerts (one alert per rule and key per window, with a count), rate-limits alerts, and delivers each alert once per
-- configured channel (email, webhook) with bounded retries. Delivery failure never blocks or weakens a security control.
-- Global tables (no tenant data): alerts carry the event type, severity, times, count and correlation id only.
CREATE TABLE app.security_alert (
  id             uuid        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  rule           text        NOT NULL CHECK (rule ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  dedup_key      text        NOT NULL CHECK (length(dedup_key) <= 200),
  event_type     text        NOT NULL CHECK (event_type ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  severity       text        NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
  first_event_at timestamptz NOT NULL,
  last_event_at  timestamptz NOT NULL,
  event_count    int         NOT NULL CHECK (event_count >= 1),
  correlation_id text        CHECK (length(correlation_id) <= 64),
  created_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE (rule, dedup_key)
);
CREATE TABLE app.security_alert_delivery (
  id              uuid        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  alert_id        uuid        NOT NULL REFERENCES app.security_alert (id),
  channel         text        NOT NULL CHECK (channel IN ('EMAIL', 'WEBHOOK')),
  status          text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
  attempts        int         NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 20),
  next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  last_error_code text        CHECK (last_error_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  sent_at         timestamptz,
  UNIQUE (alert_id, channel),
  CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);
CREATE INDEX security_alert_delivery_due_ix ON app.security_alert_delivery (next_attempt_at) WHERE status = 'PENDING';
-- Evaluation cursor: the last security event already evaluated (single row).
CREATE TABLE app.security_alert_cursor (
  id               boolean     NOT NULL DEFAULT true PRIMARY KEY CHECK (id),
  last_occurred_at timestamptz NOT NULL DEFAULT '-infinity',
  last_event_id    uuid
);
INSERT INTO app.security_alert_cursor DEFAULT VALUES;

GRANT SELECT ON app.platform_security_event TO sdlc_worker;
GRANT SELECT, INSERT ON app.security_alert TO sdlc_worker;
GRANT UPDATE (last_event_at, event_count) ON app.security_alert TO sdlc_worker;
GRANT SELECT, INSERT ON app.security_alert_delivery TO sdlc_worker;
GRANT UPDATE (status, attempts, next_attempt_at, last_error_code, sent_at) ON app.security_alert_delivery TO sdlc_worker;
GRANT SELECT, UPDATE ON app.security_alert_cursor TO sdlc_worker;
GRANT SELECT ON app.security_alert, app.security_alert_delivery TO sdlc_app;
-- Worker-side security events (GitHub credential failures, C-17).
GRANT INSERT ON app.platform_security_event TO sdlc_worker;
